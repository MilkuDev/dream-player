package org.milkdev.dreamplayer.library.metadata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.milkdev.dreamplayer.currentTimeMillis
import org.milkdev.dreamplayer.database.dao.AlbumCoverLookupCandidate
import org.milkdev.dreamplayer.database.dao.MusicDao
import org.milkdev.dreamplayer.database.entities.AlbumEntity
import org.milkdev.dreamplayer.database.entities.GenreEntity
import org.milkdev.dreamplayer.database.entities.MetadataEntityType
import org.milkdev.dreamplayer.database.entities.MetadataProvider
import org.milkdev.dreamplayer.database.entities.MetadataResolutionEntity
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.extensions.data.CoverArtLookup
import org.milkdev.dreamplayer.extensions.data.CoverArtRepository
import org.milkdev.dreamplayer.extensions.data.LastFmAlbumLookup
import org.milkdev.dreamplayer.extensions.data.LastFmRepository
import org.milkdev.dreamplayer.extensions.data.MusicBrainzMetadataLookup
import org.milkdev.dreamplayer.extensions.data.MusicBrainzMetadataRepository
import org.milkdev.dreamplayer.extensions.secrets.LastFmSecretStore
import org.milkdev.dreamplayer.library.CoverLookupState
import org.milkdev.dreamplayer.library.CoverSource
import org.milkdev.dreamplayer.library.MetadataState
import org.milkdev.dreamplayer.library.MetadataSyncUiState
import kotlin.time.Duration.Companion.milliseconds

class MetadataSyncService(
    private val musicDao: MusicDao,
    private val lastFmRepository: LastFmRepository,
    private val musicBrainzRepository: MusicBrainzMetadataRepository,
    private val coverArtRepository: CoverArtRepository,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var drainJob: Job? = null
    private var forcedCoverJob: Job? = null
    private val _state = MutableStateFlow(MetadataSyncUiState())
    val state: StateFlow<MetadataSyncUiState> = _state.asStateFlow()

    fun startAutoBatch(limit: Int = AUTO_COVER_BATCH_SIZE) {
        scope.launch(Dispatchers.IO) {
            syncCoverBatch(limit = limit)
        }
    }

    fun startDrain() {
        if (drainJob?.isActive == true) return
        drainJob = scope.launch(Dispatchers.IO) {
            _state.update {
                it.copy(isSyncing = true, message = "Синхронизирую обложки...")
            }

            var finalStopReason = MetadataSyncStopReason.EMPTY_QUEUE
            try {
                while (isActive) {
                    val result = syncCoverBatch(limit = MANUAL_COVER_BATCH_SIZE)
                    finalStopReason = result.stopReason
                    if (result.stopReason == MetadataSyncStopReason.RATE_LIMITED) break
                    if (result.processed == 0) break
                    delay(BATCH_PAUSE_MS.milliseconds)
                }

                if (isActive && finalStopReason != MetadataSyncStopReason.RATE_LIMITED) {
                    while (isActive) {
                        val result = syncLastFmBatch(limit = MANUAL_LASTFM_BATCH_SIZE)
                        finalStopReason = result.stopReason
                        if (result.stopReason == MetadataSyncStopReason.NO_API_KEY) break
                        if (result.stopReason == MetadataSyncStopReason.RATE_LIMITED) break
                        if (result.processed == 0) break
                        delay(BATCH_PAUSE_MS.milliseconds)
                    }
                }
            } finally {
                val counts = pendingCounts()
                _state.update {
                    it.copy(
                        isSyncing = false,
                        pendingCount = counts.total,
                        coverPendingCount = counts.cover,
                        lastFmPendingCount = counts.lastFm,
                        message = finalStopReason.toFinalStatusMessage(counts),
                    )
                }
            }
        }
    }

    fun startForcedCoverLookup() {
        if (drainJob?.isActive == true || forcedCoverJob?.isActive == true) return
        forcedCoverJob = scope.launch(Dispatchers.IO) {
            syncCoverBatch(
                limit = MANUAL_COVER_BATCH_SIZE,
                forceMissingCovers = true,
            )
        }
    }

    suspend fun refreshPendingCount() {
        val counts = pendingCounts()
        _state.update {
            it.copy(
                pendingCount = counts.total,
                coverPendingCount = counts.cover,
                lastFmPendingCount = counts.lastFm,
            )
        }
    }

    suspend fun syncBatch(limit: Int = MANUAL_LASTFM_BATCH_SIZE): MetadataSyncBatchResult {
        return syncLastFmBatch(limit = limit)
    } // TODO:

    suspend fun syncCoverBatch(
        limit: Int = MANUAL_COVER_BATCH_SIZE,
        forceMissingCovers: Boolean = false,
    ): MetadataSyncBatchResult {
        return mutex.withLock {
            val countsBefore = pendingCounts()
            _state.update {
                it.copy(
                    isSyncing = true,
                    pendingCount = countsBefore.total,
                    coverPendingCount = countsBefore.cover,
                    lastFmPendingCount = countsBefore.lastFm,
                    message = if (forceMissingCovers) {
                        "Принудительно проверяю отсутствующие обложки MusicBrainz..."
                    } else {
                        "Ищу обложки MusicBrainz..."
                    },
                )
            }

            val now = currentTimeMillis()
            val albums = if (forceMissingCovers) {
                musicDao.getAlbumsForForcedCoverLookup(limit = limit)
            } else {
                musicDao.getAlbumsForCoverLookup(
                    limit = limit,
                    retryTimestamp = now - COVER_RETRY_DELAY_MS,
                    pendingTimeoutTimestamp = now - PENDING_TIMEOUT_MS,
                    currentVersion = CURRENT_COVER_LOOKUP_VERSION,
                )
            }

            if (albums.isEmpty()) {
                updateCountsAfterBatch(
                    processed = 0,
                    stopReason = MetadataSyncStopReason.EMPTY_QUEUE,
                    forceMissingCovers = forceMissingCovers,
                )
                return MetadataSyncBatchResult(processed = 0, stopReason = MetadataSyncStopReason.EMPTY_QUEUE)
            }

            var processed = 0
            var stopReason = MetadataSyncStopReason.BATCH_LIMIT
            for (candidate in albums) {
                when (processCoverAlbum(candidate)) {
                    SyncItemOutcome.PROCESSED -> processed += 1
                    SyncItemOutcome.NO_API_KEY -> {
                        stopReason = MetadataSyncStopReason.NO_API_KEY
                        break
                    }
                    SyncItemOutcome.RATE_LIMITED -> {
                        stopReason = MetadataSyncStopReason.RATE_LIMITED
                        break
                    }
                }
            }

            updateCountsAfterBatch(
                processed = processed,
                stopReason = stopReason,
                forceMissingCovers = forceMissingCovers,
            )
            MetadataSyncBatchResult(processed = processed, stopReason = stopReason)
        }
    }

    private suspend fun syncLastFmBatch(limit: Int = MANUAL_LASTFM_BATCH_SIZE): MetadataSyncBatchResult {
        return mutex.withLock {
            val countsBefore = pendingCounts()
            _state.update {
                it.copy(
                    isSyncing = true,
                    pendingCount = countsBefore.total,
                    coverPendingCount = countsBefore.cover,
                    lastFmPendingCount = countsBefore.lastFm,
                    message = "Обогащаю альбомы Last.fm...",
                )
            }

            val now = currentTimeMillis()
            val albums = musicDao.getAlbumsToSync(
                limit = limit,
                retryTimestamp = now - LASTFM_RETRY_DELAY_MS,
                pendingTimeoutTimestamp = now - PENDING_TIMEOUT_MS,
                currentVersion = CURRENT_METADATA_VERSION,
            )
            if (albums.isEmpty()) {
                updateCountsAfterBatch(processed = 0, stopReason = MetadataSyncStopReason.EMPTY_QUEUE)
                return MetadataSyncBatchResult(processed = 0, stopReason = MetadataSyncStopReason.EMPTY_QUEUE)
            }

            var processed = 0
            var stopReason = MetadataSyncStopReason.BATCH_LIMIT
            for (album in albums) {
                when (processLastFmAlbum(album)) {
                    SyncItemOutcome.PROCESSED -> processed += 1
                    SyncItemOutcome.NO_API_KEY -> {
                        stopReason = MetadataSyncStopReason.NO_API_KEY
                        break
                    }
                    SyncItemOutcome.RATE_LIMITED -> {
                        stopReason = MetadataSyncStopReason.RATE_LIMITED
                        break
                    }
                }
                delay(LASTFM_REQUEST_INTERVAL_MS.milliseconds)
            }

            updateCountsAfterBatch(processed = processed, stopReason = stopReason)
            MetadataSyncBatchResult(processed = processed, stopReason = stopReason)
        }
    }

    private suspend fun updateCountsAfterBatch(
        processed: Int,
        stopReason: MetadataSyncStopReason,
        forceMissingCovers: Boolean = false,
    ) {
        val counts = pendingCounts()
        _state.update {
            it.copy(
                isSyncing = drainJob?.isActive == true,
                pendingCount = counts.total,
                coverPendingCount = counts.cover,
                lastFmPendingCount = counts.lastFm,
                processedCount = it.processedCount + processed,
                message = if (forceMissingCovers) {
                    stopReason.toForcedCoverStatusMessage(processed)
                } else {
                    stopReason.toStatusMessage(
                        processed = processed,
                        pending = counts.total,
                    )
                },
            )
        }
    }

    private suspend fun processCoverAlbum(candidate: AlbumCoverLookupCandidate): SyncItemOutcome {
        val album = candidate.album
        val now = currentTimeMillis()
        musicDao.updateAlbumCoverLookupState(album.id, CoverLookupState.PENDING.name, now)

        return when (val lookup = coverArtRepository.lookupAlbumCover(
            albumId = album.id,
            artist = candidate.artistName,
            album = album.title,
        )) {
            is CoverArtLookup.Found -> {
                musicDao.updateAlbum(
                    album.copy(
                        coverUri = lookup.localUri,
                        coverSource = CoverSource.REMOTE.name,
                        coverUpdatedAt = now,
                        coverLookupState = CoverLookupState.DONE.name,
                        coverLookupAttemptAt = now,
                        coverLookupUpdatedAt = now,
                        coverLookupVersion = CURRENT_COVER_LOOKUP_VERSION,
                        musicBrainzReleaseGroupMbid = lookup.musicBrainzReleaseGroupMbid
                            ?: album.musicBrainzReleaseGroupMbid,
                    )
                )
                SyncItemOutcome.PROCESSED
            }
            CoverArtLookup.NoMatch -> updateCoverNoMatch(album, now)
            is CoverArtLookup.PermanentFailure -> updateCoverFailed(album, now)
            is CoverArtLookup.RetryableFailure -> {
                updateCoverFailed(album, now)
                if (lookup.isRateLimited) SyncItemOutcome.RATE_LIMITED else SyncItemOutcome.PROCESSED
            }
        }
    }

    private suspend fun processLastFmAlbum(album: AlbumEntity): SyncItemOutcome {
        val now = currentTimeMillis()
        musicDao.updateAlbumSyncState(album.id, MetadataState.PENDING.name, now)

        val artistName = musicDao.getArtistName(album.artistId)
        if (artistName == null) {
            AppDebugLog.log("MetadataSync: Артист для альбома '${album.title}' не найден в БД. Пропускаем.")
            return updateNoMatch(album, now)
        }

        AppDebugLog.log("MetadataSync: Начинаем синхронизацию альбома: '${album.title}' от '$artistName'")

        val musicBrainz = when (val lookup = musicBrainzRepository.lookupAlbumMetadata(
            artist = artistName,
            album = album.title,
            releaseGroupMbid = album.musicBrainzReleaseGroupMbid,
        )) {
            is MusicBrainzMetadataLookup.Found -> lookup.metadata.also {
                musicDao.insertMetadataResolution(
                    MetadataResolutionEntity(
                        entityType = MetadataEntityType.ALBUM.name,
                        entityId = album.id,
                        provider = MetadataProvider.MUSICBRAINZ.name,
                        sourceId = it.releaseGroupMbid,
                        confidence = it.confidence,
                        identityTrust = TRUST_MUSICBRAINZ_IDENTITY,
                        yearTrust = TRUST_MUSICBRAINZ_YEAR,
                        genreTrust = TRUST_MUSICBRAINZ_GENRE,
                        artworkTrust = TRUST_RELEASE_GROUP_ARTWORK,
                        resolvedAt = now,
                    )
                )
            }
            MusicBrainzMetadataLookup.NoMatch -> null
            is MusicBrainzMetadataLookup.RetryableFailure -> null
        }

        val apiKey = LastFmSecretStore.getApiKey()?.takeIf { it.isNotBlank() }
        val lastFmLookup = if (apiKey == null) {
            LastFmAlbumLookup.NoApiKey
        } else {
            lastFmRepository.lookupAlbumMetadata(
                artist = artistName,
                album = album.title,
                apiKey = apiKey,
                mbid = musicBrainz?.releaseGroupMbid ?: album.musicBrainzReleaseGroupMbid,
                expectedYear = musicBrainz?.year ?: album.year,
            )
        }

        var cachedCoverUri: String? = null
        var coverRateLimited = false
        val lastFmMetadata = (lastFmLookup as? LastFmAlbumLookup.Found)?.metadata
        if (lastFmMetadata != null) {
            musicDao.insertMetadataResolution(
                MetadataResolutionEntity(
                    entityType = MetadataEntityType.ALBUM.name,
                    entityId = album.id,
                    provider = MetadataProvider.LASTFM.name,
                    sourceId = lastFmMetadata.sourceMbid,
                    confidence = lastFmMetadata.confidence,
                    identityTrust = TRUST_LASTFM_IDENTITY,
                    yearTrust = TRUST_LASTFM_YEAR,
                    genreTrust = TRUST_LASTFM_GENRE,
                    artworkTrust = TRUST_LASTFM_ARTWORK,
                    resolvedAt = now,
                )
            )

            if (
                !lastFmMetadata.coverUrl.isNullOrBlank() &&
                album.albumArtUri.isNullOrBlank() &&
                album.coverUri.isNullOrBlank() &&
                TRUST_LASTFM_ARTWORK > album.artworkSourceTrust
            ) {
                when (val coverLookup = coverArtRepository.cacheLastFmCover(
                    albumId = album.id,
                    coverUrl = lastFmMetadata.coverUrl,
                )) {
                    is CoverArtLookup.Found -> cachedCoverUri = coverLookup.localUri
                    is CoverArtLookup.RetryableFailure -> coverRateLimited = coverLookup.isRateLimited
                    CoverArtLookup.NoMatch,
                    is CoverArtLookup.PermanentFailure -> Unit
                }
            }
        }

        val yearChoice = chooseTrustedValue(
            current = album.year,
            currentTrust = album.yearSourceTrust,
            candidates = listOf(
                TrustedValue(musicBrainz?.year, TRUST_MUSICBRAINZ_YEAR),
                TrustedValue(lastFmMetadata?.year, TRUST_LASTFM_YEAR),
            ),
        )
        val releaseGroupMbidChoice = chooseTrustedValue(
            current = album.musicBrainzReleaseGroupMbid,
            currentTrust = album.identitySourceTrust,
            candidates = listOf(
                TrustedValue(musicBrainz?.releaseGroupMbid, TRUST_MUSICBRAINZ_IDENTITY),
                TrustedValue(lastFmMetadata?.sourceMbid, TRUST_LASTFM_IDENTITY),
            ),
        )
        val genreChoice = chooseGenreList(
            lastFmGenres = lastFmMetadata?.genres.orEmpty(),
            musicBrainzGenres = musicBrainz?.genres.orEmpty(),
        )

        val hasNewCover = !cachedCoverUri.isNullOrBlank()
        val hasUsefulData = yearChoice.value != album.year ||
            releaseGroupMbidChoice.value != album.musicBrainzReleaseGroupMbid ||
            genreChoice.genres.isNotEmpty() ||
            hasNewCover

        if (!hasUsefulData) {
            AppDebugLog.log("MetadataSync: Нет новых данных (ни жанра, ни года, ни обложки) для: '${album.title}'")
            return if (coverRateLimited) {
                musicDao.updateAlbumSyncState(album.id, MetadataState.FAILED.name, now)
                SyncItemOutcome.RATE_LIMITED
            } else {
                updateNoMatch(album, now)
            }
        }

        if (genreChoice.genres.isNotEmpty()) {
            musicDao.replaceAlbumGenres(
                albumId = album.id,
                genres = genreChoice.genres.map { genre ->
                    GenreEntity(
                        name = genre,
                        sortKey = genre.toSortKey(),
                        createdAt = now,
                    )
                },
                sourceTrust = genreChoice.trust,
            )
        }

        AppDebugLog.log(
            "MetadataSync: Сохраняем в БД '${album.title}': " +
                "Жанры='${genreChoice.genres.joinToString(", ")}', Год='${yearChoice.value}'"
        )

        musicDao.updateAlbum(
            album.copy(
                year = yearChoice.value,
                musicBrainzReleaseGroupMbid = releaseGroupMbidChoice.value,
                coverUri = cachedCoverUri ?: album.coverUri,
                coverSource = if (hasNewCover) CoverSource.REMOTE.name else album.coverSource,
                artworkSourceTrust = if (hasNewCover) TRUST_LASTFM_ARTWORK else album.artworkSourceTrust,
                yearSourceTrust = yearChoice.trust,
                identitySourceTrust = releaseGroupMbidChoice.trust,
                metadataState = MetadataState.DONE.name,
                metadataUpdatedAt = now,
                lastMetadataAttemptAt = now,
                metadataVersion = CURRENT_METADATA_VERSION,
                coverUpdatedAt = if (hasNewCover) now else album.coverUpdatedAt,
                coverLookupState = if (hasNewCover) CoverLookupState.DONE.name else album.coverLookupState,
                coverLookupAttemptAt = if (hasNewCover) now else album.coverLookupAttemptAt,
                coverLookupUpdatedAt = if (hasNewCover) now else album.coverLookupUpdatedAt,
                coverLookupVersion = if (hasNewCover) CURRENT_COVER_LOOKUP_VERSION else album.coverLookupVersion,
            )
        )
        return when (lastFmLookup) {
            is LastFmAlbumLookup.RetryableFailure -> if (lastFmLookup.isRateLimited) SyncItemOutcome.RATE_LIMITED else SyncItemOutcome.PROCESSED
            LastFmAlbumLookup.NoApiKey -> SyncItemOutcome.PROCESSED
            else -> SyncItemOutcome.PROCESSED
        }
    }

    private suspend fun updateCoverNoMatch(album: AlbumEntity, timestamp: Long): SyncItemOutcome {
        musicDao.updateAlbum(
            album.copy(
                coverLookupState = CoverLookupState.NO_MATCH.name,
                coverLookupAttemptAt = timestamp,
                coverLookupUpdatedAt = timestamp,
                coverLookupVersion = CURRENT_COVER_LOOKUP_VERSION,
            )
        )
        return SyncItemOutcome.PROCESSED
    }

    private suspend fun updateCoverFailed(album: AlbumEntity, timestamp: Long): SyncItemOutcome {
        musicDao.updateAlbum(
            album.copy(
                coverLookupState = CoverLookupState.FAILED.name,
                coverLookupAttemptAt = timestamp,
                coverLookupVersion = CURRENT_COVER_LOOKUP_VERSION,
            )
        )
        return SyncItemOutcome.PROCESSED
    }

    private suspend fun updateNoMatch(album: AlbumEntity, timestamp: Long): SyncItemOutcome {
        musicDao.updateAlbum(
            album.copy(
                metadataState = MetadataState.NO_MATCH.name,
                lastMetadataAttemptAt = timestamp,
                metadataUpdatedAt = timestamp,
                metadataVersion = CURRENT_METADATA_VERSION,
            )
        )
        return SyncItemOutcome.PROCESSED
    }

    private suspend fun pendingCounts(): MetadataPendingCounts {
        val now = currentTimeMillis()
        val coverPending = musicDao.getPendingCoverLookupCount(
            retryTimestamp = now - COVER_RETRY_DELAY_MS,
            pendingTimeoutTimestamp = now - PENDING_TIMEOUT_MS,
            currentVersion = CURRENT_COVER_LOOKUP_VERSION,
        )
        val lastFmPending = musicDao.getPendingMetadataCount()
        return MetadataPendingCounts(cover = coverPending, lastFm = lastFmPending)
    }

    companion object {
        const val CURRENT_METADATA_VERSION = 2
        const val CURRENT_COVER_LOOKUP_VERSION = 1
        private const val AUTO_COVER_BATCH_SIZE = 6
        private const val MANUAL_COVER_BATCH_SIZE = 20
        private const val MANUAL_LASTFM_BATCH_SIZE = 20
        private const val LASTFM_REQUEST_INTERVAL_MS = 1500L
        private const val BATCH_PAUSE_MS = 500L
        private const val COVER_RETRY_DELAY_MS = 12 * 60 * 60 * 1000L
        private const val LASTFM_RETRY_DELAY_MS = 12 * 60 * 60 * 1000L
        private const val PENDING_TIMEOUT_MS = 30 * 60 * 1000L
    }
}

private const val TRUST_MUSICBRAINZ_IDENTITY = 80
private const val TRUST_LASTFM_IDENTITY = 60
private const val TRUST_MUSICBRAINZ_YEAR = 90
private const val TRUST_LASTFM_YEAR = 60
private const val TRUST_LASTFM_GENRE = 90
private const val TRUST_MUSICBRAINZ_GENRE = 60
private const val TRUST_RELEASE_GROUP_ARTWORK = 70
private const val TRUST_LASTFM_ARTWORK = 60

private data class TrustedValue<T>(
    val value: T?,
    val trust: Int,
)

private data class GenreChoice(
    val genres: List<String>,
    val trust: Int,
)

private fun <T> chooseTrustedValue(
    current: T?,
    currentTrust: Int,
    candidates: List<TrustedValue<T>>,
): TrustedValue<T> {
    val best = candidates
        .filter { it.value != null }
        .maxByOrNull { it.trust }
    return if (best != null && (current == null || best.trust > currentTrust)) {
        best
    } else {
        TrustedValue(current, currentTrust)
    }
}

private fun chooseGenreList(
    lastFmGenres: List<String>,
    musicBrainzGenres: List<String>,
): GenreChoice {
    return when {
        lastFmGenres.isNotEmpty() -> GenreChoice(lastFmGenres, TRUST_LASTFM_GENRE)
        musicBrainzGenres.isNotEmpty() -> GenreChoice(musicBrainzGenres, TRUST_MUSICBRAINZ_GENRE)
        else -> GenreChoice(emptyList(), 0)
    }
}

private fun String.toSortKey(): String {
    return trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
}

data class MetadataSyncBatchResult(
    val processed: Int,
    val stopReason: MetadataSyncStopReason,
)

enum class MetadataSyncStopReason {
    BATCH_LIMIT,
    EMPTY_QUEUE,
    NO_API_KEY,
    RATE_LIMITED,
}

private data class MetadataPendingCounts(
    val cover: Int,
    val lastFm: Int,
) {
    val total: Int = cover + lastFm
}

private fun MetadataSyncStopReason.toStatusMessage(
    processed: Int,
    pending: Int,
): String {
    return when (this) {
        MetadataSyncStopReason.NO_API_KEY -> "API-ключ Last.fm не задан"
        MetadataSyncStopReason.EMPTY_QUEUE -> "Очередь метаданных пуста"
        MetadataSyncStopReason.BATCH_LIMIT -> "Обработано $processed, осталось $pending"
        MetadataSyncStopReason.RATE_LIMITED -> "Источник ограничил частоту запросов, продолжу позже"
    }
}

private fun MetadataSyncStopReason.toFinalStatusMessage(
    counts: MetadataPendingCounts,
): String {
    return when {
        this == MetadataSyncStopReason.RATE_LIMITED -> "Источник ограничил частоту запросов, продолжу позже"
        this == MetadataSyncStopReason.NO_API_KEY && counts.cover == 0 -> "Обложки обновлены, API-ключ Last.fm не задан"
        counts.total == 0 -> "Метаданные и обложки обновлены"
        else -> "Осталось: обложки ${counts.cover}, Last.fm ${counts.lastFm}"
    }
}

private fun MetadataSyncStopReason.toForcedCoverStatusMessage(processed: Int): String {
    return when (this) {
        MetadataSyncStopReason.EMPTY_QUEUE -> "MusicBrainz: отсутствующих обложек для проверки нет"
        MetadataSyncStopReason.BATCH_LIMIT -> "MusicBrainz: проверено обложек $processed"
        MetadataSyncStopReason.RATE_LIMITED -> "MusicBrainz ограничил частоту запросов, попробуй позже"
        MetadataSyncStopReason.NO_API_KEY -> "MusicBrainz: API-ключ не нужен"
    }
}

private enum class SyncItemOutcome {
    PROCESSED,
    NO_API_KEY,
    RATE_LIMITED,
}
