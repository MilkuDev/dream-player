@file:Suppress("ConvertCallChainIntoSequence")

package org.milkdev.dreamplayer.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.currentTimeMillis
import org.milkdev.dreamplayer.database.dao.GenreIdResult
import org.milkdev.dreamplayer.database.dao.MusicDao
import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.ArtistEntity
import org.milkdev.dreamplayer.database.entities.EmbeddedMbidStatus
import org.milkdev.dreamplayer.database.entities.GenreEntity
import org.milkdev.dreamplayer.database.entities.PlaybackHistoryEntity
import org.milkdev.dreamplayer.database.entities.SyncAuditEntity
import org.milkdev.dreamplayer.database.entities.SyncStatus
import org.milkdev.dreamplayer.database.entities.TrackEntity
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.extensions.data.CoverArtRepository
import org.milkdev.dreamplayer.extensions.data.LastFmRepository
import org.milkdev.dreamplayer.extensions.data.MusicBrainzMetadataRepository
import org.milkdev.dreamplayer.extensions.network.LastFmService
import org.milkdev.dreamplayer.library.metadata.EmbeddedMetadataReader
import org.milkdev.dreamplayer.library.metadata.MetadataSyncService
import org.milkdev.dreamplayer.model.AlbumSortOrder
import org.milkdev.dreamplayer.model.TrackSortOrder
import org.milkdev.dreamplayer.playback.PlaybackItemRef
import org.milkdev.dreamplayer.playback.ResolvedPlaybackItem
import org.milkdev.dreamplayer.playback.TrackAvailability
import org.milkdev.dreamplayer.playback.TrackPlaybackMetadata
import kotlin.text.iterator
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@OptIn(FlowPreview::class)
class MusicRepository(
    private val musicDao: MusicDao,
    private val scanner: MusicScanner,
    private val scope: CoroutineScope
) {
    private val _isSyncing = MutableStateFlow(false)
    private val playbackItemCache = object : LinkedHashMap<Long, ResolvedPlaybackItem>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ResolvedPlaybackItem>?): Boolean {
            return size > MAX_PLAYBACK_ITEM_CACHE_SIZE
        }
    }
    private val playbackItemCacheMutex = Mutex()
    private val metadataSyncService = MetadataSyncService(
        musicDao = musicDao,
        lastFmRepository = LastFmRepository(LastFmService()),
        musicBrainzRepository = MusicBrainzMetadataRepository(),
        coverArtRepository = CoverArtRepository(),
        scope = scope
    )
    val metadataSyncState: StateFlow<MetadataSyncUiState> = metadataSyncService.state

    init {
        scope.launch {
            scanner.observeChanges()
                .debounce(2000.milliseconds)
                .collect {
                    AppDebugLog.log("library_change_observed")
                    sync()
                }
        }
    }

    suspend fun sync() = withContext(Dispatchers.IO) {
        if (_isSyncing.value) {
            AppDebugLog.log("library_sync_skip reason=already_syncing")
            return@withContext
        }

        _isSyncing.value = true
        val syncStart = TimeSource.Monotonic.markNow()
        val currentSyncTimestamp = currentTimeMillis()
        var tracksFound = 0

        try {
            val scanStart = TimeSource.Monotonic.markNow()
            val rawTracks = scanner.scan().toList()
            tracksFound = rawTracks.size
            val scanDurationMs = scanStart.elapsedMs()

            val artistMap = syncArtists(rawTracks)
            val albumMap = syncAlbums(rawTracks, artistMap, currentSyncTimestamp)
            val movedTrackQueues = musicDao.getMissingTracks().toMovedTrackQueues()
            var changedTracks = 0
            var skippedTracks = 0
            var movedTracks = 0
            val batches = rawTracks.chunked(BATCH_SIZE)
            batches.forEach { batch ->
                val batchStats = processBatch(
                    batch = batch,
                    timestamp = currentSyncTimestamp,
                    artistMap = artistMap,
                    albumMap = albumMap,
                    movedTrackQueues = movedTrackQueues,
                )
                changedTracks += batchStats.changed
                skippedTracks += batchStats.skipped
                movedTracks += batchStats.moved
            }

            val cleanupStart = TimeSource.Monotonic.markNow()
            val missingTracks = markMissingTracks(rawTracks, currentSyncTimestamp)
            val threshold = currentSyncTimestamp - 30 * 24 * 60 * 60 * 1000L
            val tombstoneThreshold = currentSyncTimestamp - TOMBSTONE_RETENTION_MS
            musicDao.markOldAlbumsDeleted(threshold = threshold, timestamp = currentSyncTimestamp)
            musicDao.purgeDeletedTracks(tombstoneThreshold)
            musicDao.purgeDeletedAlbums(tombstoneThreshold)
            musicDao.purgeUnusedGenres()
            val cleanupDurationMs = cleanupStart.elapsedMs()
            AppDebugLog.log(
                "library_sync_summary tracks=$tracksFound changed=$changedTracks " +
                    "skipped=$skippedTracks moved=$movedTracks missing=$missingTracks " +
                    "scanMs=$scanDurationMs cleanupMs=$cleanupDurationMs " +
                    "durationMs=${syncStart.elapsedMs()}"
            )

            musicDao.insertAudit(
                SyncAuditEntity(
                    timestamp = currentSyncTimestamp,
                    durationMs = syncStart.elapsedMs(),
                    tracksFound = tracksFound,
                    status = SyncStatus.SUCCESS
                )
            )
            clearPlaybackItemCache()
            metadataSyncService.refreshPendingCount()
            metadataSyncService.startAutoBatch()
        } catch (e: Exception) {
            AppDebugLog.log(
                "library_sync_error tracks=$tracksFound durationMs=${syncStart.elapsedMs()} " +
                    "message=${e.message.orEmpty()}"
            )
            musicDao.insertAudit(
                SyncAuditEntity(
                    timestamp = currentSyncTimestamp,
                    durationMs = syncStart.elapsedMs(),
                    tracksFound = tracksFound,
                    status = SyncStatus.ERROR,
                    errorLog = e.message
                )
            )
        } finally {
            _isSyncing.value = false
        }
    }

    private suspend fun syncArtists(rawTracks: List<RawTrackData>): Map<String, Long> {
        val artistNames = rawTracks
            .map { it.artist ?: UNKNOWN_ARTIST }
            .distinct()

        if (artistNames.isNotEmpty()) {
            musicDao.insertArtists(artistNames.map { ArtistEntity(name = it) })
        }

        val artistMap = musicDao.getArtistIdMap().associate { it.name to it.id }
        return artistMap
    }

    private suspend fun syncAlbums(
        rawTracks: List<RawTrackData>,
        artistMap: Map<String, Long>,
        timestamp: Long
    ): Map<Pair<String, Long>, Long> {
        val albums = rawTracks.mapNotNull {
            val artistName = it.artist ?: UNKNOWN_ARTIST
            val artistId = artistMap[artistName] ?: return@mapNotNull null
            AlbumData(
                title = it.album ?: UNKNOWN_ALBUM,
                artistId = artistId,
                artUri = it.albumArtUri,
                coverSource = it.albumArtSource,
            )
        }.distinctBy { it.title to it.artistId }

        if (albums.isNotEmpty()) {
            val artistNameMap = artistMap.entries.associate { it.value to it.key }
            musicDao.insertAlbums(
                albums.map {
                    AlbumEntity(
                        title = it.title,
                        artistId = it.artistId,
                        albumArtUri = it.artUri,
                        coverSource = it.coverSource.name,
                        lastSeenTimestamp = timestamp,
                        titleSortKey = it.title.toSortKey(),
                        artistSortKey = (artistNameMap[it.artistId] ?: "").toSortKey()
                    )
                }
            )
        }

        val albumIdMap = musicDao.getAlbumIdMap()
        val albumMap = albumIdMap.associate { (it.title to it.artistId) to it.id }
        val artistNameMap = artistMap.entries.associate { it.value to it.key }
        albums.forEach { album ->
            val albumId = albumMap[album.title to album.artistId] ?: return@forEach
            musicDao.updateAlbumScanFields(
                albumId = albumId,
                albumArtUri = album.artUri,
                coverSource = album.coverSource.name,
                timestamp = timestamp,
                titleSortKey = album.title.toSortKey(),
                artistSortKey = (artistNameMap[album.artistId] ?: "").toSortKey(),
            )
        }

        return albumMap
    }

    suspend fun getAllTrackIds(order: TrackSortOrder): LongArray = withContext(Dispatchers.IO) {
        val idList = when (order) {
            TrackSortOrder.TRACK_NAME -> musicDao.getAllTrackIdsSortedByTitle()
            TrackSortOrder.ARTIST -> musicDao.getAllTrackIdsSortedByArtist()
            TrackSortOrder.ALBUM -> musicDao.getAllTrackIdsSortedByAlbum()
            TrackSortOrder.YEAR -> musicDao.getAllTrackIdsSortedByYear()
            TrackSortOrder.GENRE -> musicDao.getAllTrackIdsSortedByGenre()
        }
        idList.toLongArray()
    }

    suspend fun addTrackToHistory(trackId: Long) = withContext(Dispatchers.IO) {
        val now = currentTimeMillis()
        musicDao.insertHistoryEntry(
            PlaybackHistoryEntity(trackId = trackId, timestamp = now)
        )
    }

    fun getRecentlyPlayedTracks(): Flow<List<LibraryTrack>> {
        return musicDao.getRecentlyPlayedTracks().map { list ->
            list.map { it.toLibraryTrack() }
        }
    }

    suspend fun getRandomGenreWithTracks(): GenreIdResult? = withContext(Dispatchers.IO) {
        musicDao.getRandomGenreWithTracks()
    }
    private suspend fun processBatch(
        batch: List<RawTrackData>,
        timestamp: Long,
        artistMap: Map<String, Long>,
        albumMap: Map<Pair<String, Long>, Long>,
        movedTrackQueues: MutableMap<TrackSignature, MutableList<TrackEntity>>,
    ): BatchSyncStats {
        val existingTracksByPath = batch
            .map { it.path }
            .distinct()
            .chunked(QUERY_BATCH_SIZE)
            .flatMap { musicDao.getTracksByPaths(it) }
            .associateBy { it.filePath }
        val existingTracksByMediaStoreId = batch
            .mapNotNull { it.mediaStoreId }
            .distinct()
            .chunked(QUERY_BATCH_SIZE)
            .flatMap { musicDao.getTracksByMediaStoreIds(it) }
            .associateBy { it.mediaStoreId }

        var movedTracks = 0
        var skippedTracks = 0
        val changedRawTracks = mutableListOf<RawTrackData>()
        val trackEntities = batch.mapNotNull { raw ->
            val artistId = artistMap[raw.artist ?: UNKNOWN_ARTIST] ?: -1L
            val albumId = albumMap[(raw.album ?: UNKNOWN_ALBUM) to artistId] ?: -1L
            val existingByMediaStoreId = raw.mediaStoreId?.let(existingTracksByMediaStoreId::get)
            val existingByPath = existingTracksByPath[raw.path]
            val existing = existingByMediaStoreId ?: existingByPath ?: movedTrackQueues
                .removeFirst(raw.toSignature(artistId))
                ?.also { movedTracks += 1 }

            if (existing != null && existing.hasSameLibraryData(raw, artistId, albumId)) {
                skippedTracks += 1
                return@mapNotNull null
            }

            changedRawTracks += raw
            TrackEntity(
                id = existing?.id ?: 0L,
                filePath = raw.path,
                title = raw.title ?: UNKNOWN_TITLE,
                artistName = raw.artist ?: UNKNOWN_ARTIST,
                albumName = raw.album ?: UNKNOWN_ALBUM,
                artistId = artistId,
                albumId = albumId,
                durationMs = raw.durationMs,
                fileSize = raw.fileSize,
                lastModified = raw.lastModified,
                mediaStoreId = raw.mediaStoreId,
                contentFingerprint = raw.contentFingerprint(),
                fileHash = existing?.fileHash,
                availability = TrackAvailability.AVAILABLE.name,
                albumArtUri = raw.albumArtUri,
                isPresent = true,
                lastSeenTimestamp = timestamp,
                titleSortKey = (raw.title ?: UNKNOWN_TITLE).toSortKey(),
                artistSortKey = (raw.artist ?: UNKNOWN_ARTIST).toSortKey()
            )
        }

        if (trackEntities.isNotEmpty()) {
            musicDao.upsertTracks(trackEntities)
            scheduleEmbeddedMetadataExtraction(changedRawTracks, timestamp)
        }

        return BatchSyncStats(
            changed = trackEntities.size,
            skipped = skippedTracks,
            moved = movedTracks,
        )
    }

    private fun scheduleEmbeddedMetadataExtraction(
        rawTracks: List<RawTrackData>,
        generation: Long,
    ) {
        if (rawTracks.isEmpty()) return

        scope.launch(Dispatchers.IO) {
            rawTracks.forEach { raw ->
                val track = musicDao.getTracksByPaths(listOf(raw.path)).firstOrNull() ?: return@forEach
                if (!track.isPresent || track.deletedAt != null) return@forEach

                val metadata = EmbeddedMetadataReader.read(raw)
                val now = currentTimeMillis()
                val mbidStatus = when {
                    metadata == null -> EmbeddedMbidStatus.FAILED
                    !metadata.recordingMbid.isNullOrBlank() -> EmbeddedMbidStatus.FOUND
                    else -> EmbeddedMbidStatus.NOT_PRESENT
                }

                musicDao.updateTrackEmbeddedIdentity(
                    trackId = track.id,
                    recordingMbid = metadata?.recordingMbid,
                    identityTrust = TRUST_EMBEDDED_IDENTITY,
                )
                musicDao.upsertTrackEmbeddedMetadataState(
                    trackId = track.id,
                    embeddedMbidStatus = mbidStatus.name,
                    embeddedFingerprint = metadata?.tagFingerprint,
                    generation = generation,
                    timestamp = now,
                )
                metadata?.genres?.takeIf { it.isNotEmpty() }?.let { genres ->
                    musicDao.replaceTrackGenres(
                        trackId = track.id,
                        genres = genres.map { genre ->
                            GenreEntity(
                                name = genre,
                                sortKey = genre.toSortKey(),
                                createdAt = now,
                            )
                        },
                        sourceTrust = TRUST_EMBEDDED_GENRE,
                    )
                }
            }
        }
    }

    private suspend fun markMissingTracks(rawTracks: List<RawTrackData>, timestamp: Long): Int {
        val presentPaths = musicDao.getPresentTrackPaths()
        if (presentPaths.isEmpty()) return 0

        val scannedPaths = rawTracks.map { it.path }.toSet()
        val missingPaths = presentPaths.filter { it !in scannedPaths }

        missingPaths
            .chunked(QUERY_BATCH_SIZE)
            .forEach { musicDao.markTracksMissingByPaths(it, timestamp) }

        return missingPaths.size
    }

    private fun TrackEntity.hasSameLibraryData(
        raw: RawTrackData,
        resolvedArtistId: Long,
        resolvedAlbumId: Long,
    ): Boolean {
        return filePath == raw.path &&
            mediaStoreId == raw.mediaStoreId &&
            contentFingerprint == raw.contentFingerprint() &&
            availability == TrackAvailability.AVAILABLE.name &&
            title == (raw.title ?: UNKNOWN_TITLE) &&
            artistName == (raw.artist ?: UNKNOWN_ARTIST) &&
            albumName == (raw.album ?: UNKNOWN_ALBUM) &&
            artistId == resolvedArtistId &&
            albumId == resolvedAlbumId &&
            durationMs == raw.durationMs &&
            fileSize == raw.fileSize &&
            lastModified == raw.lastModified &&
            albumArtUri == raw.albumArtUri &&
            isPresent
    }

    private fun RawTrackData.toSignature(artistId: Long): TrackSignature {
        return TrackSignature(
            title = title ?: UNKNOWN_TITLE,
            artistId = artistId,
            durationMs = durationMs,
            fileSize = fileSize,
        )
    }

    private fun TrackEntity.toSignature(): TrackSignature {
        return TrackSignature(
            title = title,
            artistId = artistId,
            durationMs = durationMs,
            fileSize = fileSize,
        )
    }

    private fun List<TrackEntity>.toMovedTrackQueues(): MutableMap<TrackSignature, MutableList<TrackEntity>> {
        return groupBy { it.toSignature() }
            .mapValues { (_, tracks) -> tracks.toMutableList() }
            .toMutableMap()
    }

    private fun MutableMap<TrackSignature, MutableList<TrackEntity>>.removeFirst(
        signature: TrackSignature,
    ): TrackEntity? {
        val candidates = this[signature] ?: return null
        val candidate = candidates.removeAt(0)
        if (candidates.isEmpty()) {
            remove(signature)
        }
        return candidate
    }

    private fun TimeMark.elapsedMs(): Long {
        return elapsedNow().inWholeMilliseconds
    }

    private data class AlbumData(
        val title: String,
        val artistId: Long,
        val artUri: String?,
        val coverSource: CoverSource,
    )
    private data class BatchSyncStats(
        val changed: Int,
        val skipped: Int,
        val moved: Int,
    )
    private data class TrackSignature(
        val title: String,
        val artistId: Long,
        val durationMs: Long,
        val fileSize: Long,
    )

    suspend fun getLibrarySummary(): LibrarySummary = withContext(Dispatchers.IO) {
        musicDao.getLibrarySummary()
    }

    suspend fun getTrackPage(
        order: TrackSortOrder,
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<TrackListItem> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        val queryLimit = safeLimit + 1
        val currentCursor = cursor ?: LibraryPageCursor()
        val rows = when (order) {
            TrackSortOrder.TRACK_NAME -> musicDao.getTrackListItemsByTitle(
                limit = queryLimit,
                cursorKey = currentCursor.sortKey,
                cursorId = currentCursor.id,
            )
            TrackSortOrder.ARTIST -> musicDao.getTrackListItemsByArtist(
                limit = queryLimit,
                cursorKey = currentCursor.sortKey,
                cursorTieKey = currentCursor.tieKey,
                cursorId = currentCursor.id,
            )
            TrackSortOrder.ALBUM -> musicDao.getTrackListItemsByAlbum(
                limit = queryLimit,
                cursorKey = currentCursor.sortKey,
                cursorTieKey = currentCursor.tieKey,
                cursorId = currentCursor.id,
            )
            TrackSortOrder.YEAR -> musicDao.getTrackListItemsByYear(
                limit = queryLimit,
                cursorNumber = currentCursor.numericKey ?: 0,
                cursorMissingBucket = currentCursor.missingBucket,
                cursorTieKey = currentCursor.tieKey,
                cursorId = currentCursor.id,
            )
            TrackSortOrder.GENRE -> musicDao.getTrackListItemsByGenre(
                limit = queryLimit,
                cursorKey = currentCursor.sortKey,
                cursorMissingBucket = currentCursor.missingBucket,
                cursorTieKey = currentCursor.tieKey,
                cursorId = currentCursor.id,
            )
        }
        rows.toPage(safeLimit) { it.cursorFor(order) }
    }

    suspend fun getAlbumPage(
        order: AlbumSortOrder,
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<AlbumListItem> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        val queryLimit = safeLimit + 1
        val currentCursor = cursor ?: LibraryPageCursor()
        val rows = when (order) {
            AlbumSortOrder.TITLE -> musicDao.getAlbumListItemsByTitle(
                limit = queryLimit,
                cursorKey = currentCursor.sortKey,
                cursorId = currentCursor.id,
            )
            AlbumSortOrder.ARTIST -> musicDao.getAlbumListItemsByArtist(
                limit = queryLimit,
                cursorKey = currentCursor.sortKey,
                cursorTieKey = currentCursor.tieKey,
                cursorId = currentCursor.id,
            )
            AlbumSortOrder.YEAR -> musicDao.getAlbumListItemsByYear(
                limit = queryLimit,
                cursorNumber = currentCursor.numericKey ?: 0,
                cursorMissingBucket = currentCursor.missingBucket,
                cursorTieKey = currentCursor.tieKey,
                cursorId = currentCursor.id,
            )
            AlbumSortOrder.GENRE -> musicDao.getAlbumListItemsByGenre(
                limit = queryLimit,
                cursorKey = currentCursor.sortKey,
                cursorMissingBucket = currentCursor.missingBucket,
                cursorTieKey = currentCursor.tieKey,
                cursorId = currentCursor.id,
            )
        }
        rows.toPage(safeLimit) { it.cursorFor(order) }
    }

    suspend fun getArtistPage(
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<ArtistListItem> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        val currentCursor = cursor ?: LibraryPageCursor()
        musicDao.getArtistListItemsByName(
            limit = safeLimit + 1,
            cursorKey = currentCursor.sortKey,
            cursorId = currentCursor.id,
        ).toPage(safeLimit) { item ->
            LibraryPageCursor(sortKey = item.name.toSortKey(), id = item.id)
        }
    }

    suspend fun getGenrePage(
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<GenreListItem> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        val currentCursor = cursor ?: LibraryPageCursor()
        musicDao.getGenreListItemsByName(
            limit = safeLimit + 1,
            cursorKey = currentCursor.sortKey,
            cursorId = currentCursor.id,
        ).toPage(safeLimit) { item ->
            LibraryPageCursor(sortKey = item.name.toSortKey(), id = item.id)
        }
    }

    suspend fun searchTrackPage(
        query: String,
        mode: TrackSearchMode,
        cursor: LibraryPageCursor?,
        limit: Int,
    ): LibraryPage<TrackListItem> = withContext(Dispatchers.IO) {
        val safeLimit = limit.coerceAtLeast(1)
        val currentCursor = cursor ?: LibraryPageCursor()
        val fields = mode.config.fields
        musicDao.searchTrackListItems(
            query = query.toSortKey(),
            searchTitle = TrackSearchField.TITLE in fields,
            searchArtist = TrackSearchField.ARTIST in fields,
            searchAlbum = TrackSearchField.ALBUM in fields,
            limit = safeLimit + 1,
            cursorKey = currentCursor.sortKey,
            cursorId = currentCursor.id,
        ).toPage(safeLimit) { item ->
            LibraryPageCursor(sortKey = item.title.toSortKey(), id = item.id)
        }
    }

    fun startMetadataSync() {
        metadataSyncService.startDrain()
    }

    fun startMusicBrainzCoverSync() {
        metadataSyncService.startForcedCoverLookup()
    }

    suspend fun resolvePlayableItems(ids: LongArray): List<ResolvedPlaybackItem> {
        if (ids.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            val uniqueIds = ids.distinct()
            val cachedById = playbackItemCacheMutex.withLock {
                uniqueIds.mapNotNull { id -> playbackItemCache[id]?.let { id to it } }.toMap()
            }
            val cacheMisses = uniqueIds.filter { it !in cachedById }
            val loadedById = cacheMisses
                .chunked(QUERY_BATCH_SIZE)
                .flatMap { chunk -> musicDao.getTracksByIds(chunk) }
                .associate { entity -> entity.id to entity.toResolvedPlaybackItem() }

            if (loadedById.isNotEmpty()) {
                playbackItemCacheMutex.withLock {
                    playbackItemCache.putAll(loadedById)
                }
            }

            ids.map { id ->
                cachedById[id] ?: loadedById[id] ?: missingPlaybackItem(id)
            }
        }
    }

    suspend fun resolveDisplayQueue(ids: LongArray): List<LibraryTrack> {
        return resolvePlayableItems(ids).map { item ->
            item.toLibraryTrack()
        }
    }

    private suspend fun clearPlaybackItemCache() {
        playbackItemCacheMutex.withLock {
            playbackItemCache.clear()
        }
    }

    fun getTracksByAlbum(albumId: Long): Flow<List<LibraryTrack>> {
        return musicDao.getTracksByAlbum(albumId).map { tracks -> tracks.map { it.toLibraryTrack() } }
    }

    fun getTracksByArtist(artistId: Long): Flow<List<LibraryTrack>> {
        return musicDao.getTracksByArtist(artistId).map { tracks -> tracks.map { it.toLibraryTrack() } }
    }

    fun getAlbumsByGenre(genreId: Long): Flow<List<AlbumListItem>> {
        return musicDao.getAlbumsByGenre(genreId)
    }

    fun getTracksByGenre(genreId: Long): Flow<List<LibraryTrack>> {
        return musicDao.getTracksByGenre(genreId).map { tracks -> tracks.map { it.toLibraryTrack() } }
    }

    private fun TrackListItem.cursorFor(order: TrackSortOrder): LibraryPageCursor {
        return when (order) {
            TrackSortOrder.TRACK_NAME -> LibraryPageCursor(sortKey = title.toSortKey(), id = id)
            TrackSortOrder.ARTIST -> LibraryPageCursor(
                sortKey = artistName.toSortKey(),
                tieKey = title.toSortKey(),
                id = id,
            )
            TrackSortOrder.ALBUM -> LibraryPageCursor(
                sortKey = albumTitle.toSortKey(),
                tieKey = title.toSortKey(),
                id = id,
            )
            TrackSortOrder.YEAR -> LibraryPageCursor(
                numericKey = year ?: 0,
                missingBucket = if (year == null) 1 else 0,
                tieKey = title.toSortKey(),
                id = id,
            )
            TrackSortOrder.GENRE -> LibraryPageCursor(
                sortKey = genre.orEmpty().toSortKey(),
                missingBucket = if (genre.isNullOrBlank()) 1 else 0,
                tieKey = title.toSortKey(),
                id = id,
            )
        }
    }

    private fun AlbumListItem.cursorFor(order: AlbumSortOrder): LibraryPageCursor {
        return when (order) {
            AlbumSortOrder.TITLE -> LibraryPageCursor(sortKey = title.toSortKey(), id = id)
            AlbumSortOrder.ARTIST -> LibraryPageCursor(
                sortKey = artistName.toSortKey(),
                tieKey = title.toSortKey(),
                id = id,
            )
            AlbumSortOrder.YEAR -> LibraryPageCursor(
                numericKey = year ?: 0,
                missingBucket = if (year == null) 1 else 0,
                tieKey = title.toSortKey(),
                id = id,
            )
            AlbumSortOrder.GENRE -> LibraryPageCursor(
                sortKey = genre.orEmpty().toSortKey(),
                missingBucket = if (genre.isNullOrBlank()) 1 else 0,
                tieKey = title.toSortKey(),
                id = id,
            )
        }
    }

    private inline fun <T> List<T>.toPage(
        limit: Int,
        cursorFactory: (T) -> LibraryPageCursor,
    ): LibraryPage<T> {
        val hasMore = size > limit
        val pageItems = if (hasMore) take(limit) else this
        return LibraryPage(
            items = pageItems,
            nextCursor = pageItems.lastOrNull()?.let(cursorFactory),
            hasMore = hasMore,
        )
    }

    private fun TrackEntity.toLibraryTrack(): LibraryTrack {
        return LibraryTrack(
            id = id,
            title = title,
            artistName = artistName,
            albumName = albumName,
            durationMs = durationMs,
            albumArtUri = albumArtUri,
            isPresent = isPresent,
            contentVersion = contentVersion(),
        )
    }

    private fun TrackEntity.toResolvedPlaybackItem(): ResolvedPlaybackItem {
        val resolvedAvailability = when {
            !isPresent -> TrackAvailability.MISSING
            filePath.isBlank() -> TrackAvailability.NEEDS_RESOLVE
            else -> availability.toTrackAvailability()
        }

        return ResolvedPlaybackItem(
            ref = PlaybackItemRef(
                trackId = id,
                uri = filePath,
                availability = resolvedAvailability,
                contentVersion = contentVersion(),
            ),
            metadata = TrackPlaybackMetadata(
                title = title,
                artistName = artistName,
                albumName = albumName,
                durationMs = durationMs,
                albumArtUri = albumArtUri,
            ),
        )
    }

    private fun ResolvedPlaybackItem.toLibraryTrack(): LibraryTrack {
        return LibraryTrack(
            id = trackId,
            title = metadata.title,
            artistName = metadata.artistName,
            albumName = metadata.albumName,
            durationMs = metadata.durationMs,
            albumArtUri = metadata.albumArtUri,
            isPresent = ref.availability == TrackAvailability.AVAILABLE,
            contentVersion = ref.contentVersion,
        )
    }

    private fun missingPlaybackItem(trackId: Long): ResolvedPlaybackItem {
        return ResolvedPlaybackItem(
            ref = PlaybackItemRef(
                trackId = trackId,
                uri = "",
                availability = TrackAvailability.MISSING,
                contentVersion = 0L,
            ),
            metadata = TrackPlaybackMetadata(
                title = UNKNOWN_TITLE,
                artistName = UNKNOWN_ARTIST,
                albumName = UNKNOWN_ALBUM,
                durationMs = 0L,
                albumArtUri = null,
            ),
        )
    }

    private fun String.toTrackAvailability(): TrackAvailability {
        return TrackAvailability.entries.firstOrNull { it.name == this }
            ?: TrackAvailability.NEEDS_RESOLVE
    }

    private fun TrackEntity.contentVersion(): Long {
        return contentFingerprint
            .takeIf { it.isNotBlank() }
            ?.stableHash64()
            ?: "${mediaStoreId.orEmptyKey()}:$filePath:$fileSize:$lastModified:$durationMs:${albumArtUri.orEmpty()}".stableHash64()
    }

    private fun RawTrackData.contentFingerprint(): String {
        return "${mediaStoreId.orEmptyKey()}:$path:$fileSize:$lastModified:$durationMs:${albumArtUri.orEmpty()}"
    }

    private fun Long?.orEmptyKey(): String = this?.toString().orEmpty()

    private fun String.stableHash64(): Long {
        var hash = STABLE_HASH_SEED
        for (character in this) {
            hash = (hash xor character.code.toLong()) * STABLE_HASH_PRIME
        }
        return hash
    }

    private fun String.toSortKey(): String {
        return this.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    private companion object {
        const val BATCH_SIZE = 500
        const val QUERY_BATCH_SIZE = 500
        const val MAX_PLAYBACK_ITEM_CACHE_SIZE = 500
        const val TOMBSTONE_RETENTION_MS = 14L * 24 * 60 * 60 * 1000
        const val TRUST_EMBEDDED_IDENTITY = 100
        const val TRUST_EMBEDDED_GENRE = 80
        const val UNKNOWN_ARTIST = "Unknown Artist"
        const val UNKNOWN_ALBUM = "Unknown Album"
        const val UNKNOWN_TITLE = "Unknown Title"
        const val STABLE_HASH_SEED = -3750763034362895579L
        const val STABLE_HASH_PRIME = 1099511628211L
    }
}
