package org.milkdev.dreamplayer.library.metadata

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
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
import org.milkdev.dreamplayer.library.GenreDictionary
import org.milkdev.dreamplayer.database.entities.MetadataEntityType
import org.milkdev.dreamplayer.database.entities.MetadataProvider
import org.milkdev.dreamplayer.database.entities.MetadataResolutionEntity
import org.milkdev.dreamplayer.diagnostics.MetadataDebugLog
import org.milkdev.dreamplayer.extensions.data.CoverArtLookup
import org.milkdev.dreamplayer.extensions.data.CoverArtRepository
import org.milkdev.dreamplayer.extensions.data.LastFmAlbumLookup
import org.milkdev.dreamplayer.extensions.data.LastFmRepository
import org.milkdev.dreamplayer.extensions.data.MusicBrainzMetadataLookup
import org.milkdev.dreamplayer.extensions.data.MusicBrainzMetadataRepository
import org.milkdev.dreamplayer.extensions.secrets.LastFmSecretStore
import org.milkdev.dreamplayer.library.CoverLookupState
import org.milkdev.dreamplayer.library.CoverSource
import org.milkdev.dreamplayer.library.LocalArtworkState
import org.milkdev.dreamplayer.library.MetadataState
import org.milkdev.dreamplayer.library.MetadataSyncUiState
import kotlin.time.Duration.Companion.milliseconds

class MetadataSyncService(
    private val musicDao: MusicDao,
    private val lastFmRepository: LastFmRepository,
    private val musicBrainzRepository: MusicBrainzMetadataRepository,
    private val coverArtRepository: CoverArtRepository,
    private val scope: CoroutineScope,
    private val lastFmApiKeyProvider: suspend () -> String? = { LastFmSecretStore.getApiKey() },
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
                    if (result.stopReason.stopsRemoteSync()) break
                    if (result.processed == 0) break
                    delay(BATCH_PAUSE_MS.milliseconds)
                }

                if (isActive && !finalStopReason.stopsRemoteSync()) {
                    while (isActive) {
                        val result = syncLastFmBatch(limit = MANUAL_LASTFM_BATCH_SIZE)
                        finalStopReason = result.stopReason
                        if (result.stopReason == MetadataSyncStopReason.NO_API_KEY) break
                        if (result.stopReason.stopsRemoteSync()) break
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
            val batchKind = if (forceMissingCovers) "forced_cover" else "cover"
            MetadataDebugLog.event(
                "metadata_sync_batch_start",
                "kind" to batchKind,
                "limit" to limit,
                "queued" to albums.size,
            )

            if (albums.isEmpty()) {
                updateCountsAfterBatch(
                    processed = 0,
                    stopReason = MetadataSyncStopReason.EMPTY_QUEUE,
                    forceMissingCovers = forceMissingCovers,
                )
                MetadataDebugLog.event(
                    "metadata_sync_batch_result",
                    "kind" to batchKind,
                    "processed" to 0,
                    "stopReason" to MetadataSyncStopReason.EMPTY_QUEUE,
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
                    SyncItemOutcome.SOURCE_UNAVAILABLE -> {
                        stopReason = MetadataSyncStopReason.SOURCE_UNAVAILABLE
                        break
                    }
                }
            }

            updateCountsAfterBatch(
                processed = processed,
                stopReason = stopReason,
                forceMissingCovers = forceMissingCovers,
            )
            MetadataDebugLog.event(
                "metadata_sync_batch_result",
                "kind" to batchKind,
                "processed" to processed,
                "stopReason" to stopReason,
            )
            MetadataSyncBatchResult(processed = processed, stopReason = stopReason)
        }
    }

    internal suspend fun syncLastFmBatch(limit: Int = MANUAL_LASTFM_BATCH_SIZE): MetadataSyncBatchResult {
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
            MetadataDebugLog.event(
                "metadata_sync_batch_start",
                "kind" to "album_metadata",
                "limit" to limit,
                "queued" to albums.size,
            )
            if (albums.isEmpty()) {
                updateCountsAfterBatch(processed = 0, stopReason = MetadataSyncStopReason.EMPTY_QUEUE)
                MetadataDebugLog.event(
                    "metadata_sync_batch_result",
                    "kind" to "album_metadata",
                    "processed" to 0,
                    "stopReason" to MetadataSyncStopReason.EMPTY_QUEUE,
                )
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
                    SyncItemOutcome.SOURCE_UNAVAILABLE -> {
                        stopReason = MetadataSyncStopReason.SOURCE_UNAVAILABLE
                        break
                    }
                }
                delay(LASTFM_REQUEST_INTERVAL_MS.milliseconds)
            }

            updateCountsAfterBatch(processed = processed, stopReason = stopReason)
            MetadataDebugLog.event(
                "metadata_sync_batch_result",
                "kind" to "album_metadata",
                "processed" to processed,
                "stopReason" to stopReason,
            )
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
                    stopReason.toForcedCoverStatusMessage(
                        processed = processed,
                        verificationPending = counts.localArtworkVerification,
                    )
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
        MetadataDebugLog.event(
            "metadata_cover_lookup_start",
            "albumId" to album.id,
        )

        return when (val lookup = coverArtRepository.lookupAlbumCover(
            albumId = album.id,
            artist = candidate.artistName,
            album = album.title,
        )) {
            is CoverArtLookup.Found -> {
                musicDao.updateAlbumPreservingLocalArtwork(
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
                MetadataDebugLog.event(
                    "metadata_cover_lookup_result",
                    "albumId" to album.id,
                    "result" to "found",
                    "releaseGroupMbidPresent" to !lookup.musicBrainzReleaseGroupMbid.isNullOrBlank(),
                )
                SyncItemOutcome.PROCESSED
            }
            CoverArtLookup.NoMatch -> {
                val outcome = updateCoverNoMatch(album, now)
                MetadataDebugLog.event(
                    "metadata_cover_lookup_result",
                    "albumId" to album.id,
                    "result" to "no_match",
                )
                outcome
            }
            is CoverArtLookup.PermanentFailure -> {
                val outcome = updateCoverFailed(album, now)
                MetadataDebugLog.event(
                    "metadata_cover_lookup_result",
                    "albumId" to album.id,
                    "result" to "failed",
                    "reason" to "permanent_failure",
                )
                outcome
            }
            is CoverArtLookup.RetryableFailure -> {
                updateCoverFailed(album, now)
                MetadataDebugLog.event(
                    "metadata_cover_lookup_result",
                    "albumId" to album.id,
                    "result" to "failed",
                    "reason" to if (lookup.isRateLimited) "rate_limited" else "source_unavailable",
                )
                if (lookup.isRateLimited) SyncItemOutcome.RATE_LIMITED else SyncItemOutcome.PROCESSED
            }
        }
    }

    private suspend fun processLastFmAlbum(album: AlbumEntity): SyncItemOutcome {
        val apiKey = lastFmApiKeyProvider()?.takeIf { it.isNotBlank() }
            ?: run {
                MetadataDebugLog.event(
                    "metadata_sync_album_result",
                    "albumId" to album.id,
                    "result" to "skipped",
                    "reason" to "no_api_key",
                )
                return SyncItemOutcome.NO_API_KEY
            }
        val now = currentTimeMillis()
        musicDao.updateAlbumSyncState(album.id, MetadataState.PENDING.name, now)

        val artistName = musicDao.getArtistName(album.artistId)
        if (artistName == null) {
            updateMetadataFailed(album, now)
            MetadataDebugLog.event(
                "metadata_sync_album_result",
                "albumId" to album.id,
                "result" to "failed",
                "reason" to "artist_missing",
            )
            return SyncItemOutcome.PROCESSED
        }

        MetadataDebugLog.event(
            "metadata_sync_album_start",
            "albumId" to album.id,
            "hasReleaseGroupMbid" to !album.musicBrainzReleaseGroupMbid.isNullOrBlank(),
            "hasYear" to (album.year != null),
            "hasCover" to (
                album.localArtworkState == LocalArtworkState.PRESENT.name ||
                    !album.coverUri.isNullOrBlank()
            ),
        )

        val musicBrainzLookup = musicBrainzRepository.lookupAlbumMetadata(
            artist = artistName,
            album = album.title,
            releaseGroupMbid = album.musicBrainzReleaseGroupMbid,
        )
        val musicBrainz = when (musicBrainzLookup) {
            is MusicBrainzMetadataLookup.Found -> musicBrainzLookup.metadata.also {
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
        MetadataDebugLog.event(
            "metadata_sync_provider_result",
            "albumId" to album.id,
            "provider" to "musicbrainz",
            "result" to musicBrainzLookup.debugResult(),
            "rateLimited" to (
                (musicBrainzLookup as? MusicBrainzMetadataLookup.RetryableFailure)?.isRateLimited == true
            ),
        )

        val lastFmLookup = lastFmRepository.lookupAlbumMetadata(
            artist = artistName,
            album = album.title,
            apiKey = apiKey,
            mbid = musicBrainz?.releaseGroupMbid ?: album.musicBrainzReleaseGroupMbid,
            expectedYear = musicBrainz?.year ?: album.year,
        )
        MetadataDebugLog.event(
            "metadata_sync_provider_result",
            "albumId" to album.id,
            "provider" to "lastfm",
            "result" to lastFmLookup.debugResult(),
            "rateLimited" to (
                (lastFmLookup as? LastFmAlbumLookup.RetryableFailure)?.isRateLimited == true
            ),
        )

        var cachedCoverUri: String? = null
        var coverFailure: CoverArtLookup.RetryableFailure? = null
        var coverPermanentFailure = false
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
                album.localArtworkState == LocalArtworkState.MISSING.name &&
                album.coverUri.isNullOrBlank() &&
                TRUST_LASTFM_ARTWORK > album.artworkSourceTrust
            ) {
                when (val coverLookup = coverArtRepository.cacheLastFmCover(
                    albumId = album.id,
                    coverUrl = lastFmMetadata.coverUrl,
                )) {
                    is CoverArtLookup.Found -> {
                        cachedCoverUri = coverLookup.localUri
                        MetadataDebugLog.event(
                            "metadata_sync_provider_result",
                            "albumId" to album.id,
                            "provider" to "lastfm_cover",
                            "result" to "found",
                        )
                    }
                    is CoverArtLookup.RetryableFailure -> {
                        coverFailure = coverLookup
                        MetadataDebugLog.event(
                            "metadata_sync_provider_result",
                            "albumId" to album.id,
                            "provider" to "lastfm_cover",
                            "result" to "retryable_failure",
                            "rateLimited" to coverLookup.isRateLimited,
                        )
                    }
                    CoverArtLookup.NoMatch,
                    is CoverArtLookup.PermanentFailure -> {
                        coverPermanentFailure = true
                        MetadataDebugLog.event(
                            "metadata_sync_provider_result",
                            "albumId" to album.id,
                            "provider" to "lastfm_cover",
                            "result" to if (coverLookup is CoverArtLookup.NoMatch) {
                                "no_match"
                            } else {
                                "permanent_failure"
                            },
                        )
                    }
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
        val hasConfirmedMatch = musicBrainzLookup is MusicBrainzMetadataLookup.Found ||
            lastFmLookup is LastFmAlbumLookup.Found
        val hasProviderFailure = musicBrainzLookup is MusicBrainzMetadataLookup.RetryableFailure ||
            lastFmLookup is LastFmAlbumLookup.RetryableFailure ||
            lastFmLookup is LastFmAlbumLookup.PermanentFailure ||
            coverFailure != null ||
            coverPermanentFailure
        val isRateLimited =
            (musicBrainzLookup as? MusicBrainzMetadataLookup.RetryableFailure)?.isRateLimited == true ||
                (lastFmLookup as? LastFmAlbumLookup.RetryableFailure)?.isRateLimited == true ||
                coverFailure?.isRateLimited == true

        if (!hasUsefulData) {
            val result = when {
                hasProviderFailure -> {
                    updateMetadataFailed(album, now)
                    MetadataAlbumDebugResult(
                        outcome = if (isRateLimited) {
                            SyncItemOutcome.RATE_LIMITED
                        } else {
                            SyncItemOutcome.SOURCE_UNAVAILABLE
                        },
                        result = "failed",
                        reason = if (isRateLimited) "rate_limited" else "source_unavailable",
                    )
                }
                hasConfirmedMatch -> {
                    MetadataAlbumDebugResult(
                        outcome = updateDoneWithoutChanges(album, now),
                        result = "done",
                        reason = "matched_no_changes",
                    )
                }
                else -> {
                    MetadataAlbumDebugResult(
                        outcome = updateNoMatch(album, now),
                        result = "no_match",
                        reason = "providers_confirmed_no_match",
                    )
                }
            }
            MetadataDebugLog.event(
                "metadata_sync_album_result",
                "albumId" to album.id,
                "result" to result.result,
                "reason" to result.reason,
                "changed" to false,
            )
            return result.outcome
        }

        if (genreChoice.genres.isNotEmpty()) {
            val parentGenres = genreChoice.genres.flatMap {
                GenreDictionary.resolveParentGenres(it)
            }.distinct()
            if (parentGenres.isNotEmpty()) {
                musicDao.replaceAlbumGenres(
                    albumId = album.id,
                    genres = parentGenres.map { genre ->
                        GenreEntity(
                            name = genre,
                            sortKey = genre.toSortKey(),
                            createdAt = now,
                        )
                    },
                    sourceTrust = genreChoice.trust,
                )
            }
        }

        musicDao.updateAlbumPreservingLocalArtwork(
            album.copy(
                year = yearChoice.value,
                musicBrainzReleaseGroupMbid = releaseGroupMbidChoice.value,
                coverUri = cachedCoverUri ?: album.coverUri,
                coverSource = if (hasNewCover) CoverSource.REMOTE.name else album.coverSource,
                artworkSourceTrust = if (hasNewCover) TRUST_LASTFM_ARTWORK else album.artworkSourceTrust,
                yearSourceTrust = yearChoice.trust,
                identitySourceTrust = releaseGroupMbidChoice.trust,
                metadataState = if (hasProviderFailure) MetadataState.FAILED.name else MetadataState.DONE.name,
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
        MetadataDebugLog.event(
            "metadata_sync_album_result",
            "albumId" to album.id,
            "result" to if (hasProviderFailure) "partial_failure" else "done",
            "reason" to when {
                isRateLimited -> "rate_limited"
                hasProviderFailure -> "source_unavailable"
                else -> "updated"
            },
            "changed" to true,
            "yearChanged" to (yearChoice.value != album.year),
            "identityChanged" to (
                releaseGroupMbidChoice.value != album.musicBrainzReleaseGroupMbid
            ),
            "genreCandidateCount" to genreChoice.genres.size,
            "coverChanged" to hasNewCover,
        )
        return when {
            isRateLimited -> SyncItemOutcome.RATE_LIMITED
            hasProviderFailure -> SyncItemOutcome.SOURCE_UNAVAILABLE
            else -> SyncItemOutcome.PROCESSED
        }
    }

    private suspend fun updateMetadataFailed(album: AlbumEntity, timestamp: Long) {
        musicDao.updateAlbumPreservingLocalArtwork(
            album.copy(
                metadataState = MetadataState.FAILED.name,
                lastMetadataAttemptAt = timestamp,
                metadataVersion = CURRENT_METADATA_VERSION,
            )
        )
    }

    private suspend fun updateDoneWithoutChanges(
        album: AlbumEntity,
        timestamp: Long,
    ): SyncItemOutcome {
        musicDao.updateAlbumPreservingLocalArtwork(
            album.copy(
                metadataState = MetadataState.DONE.name,
                metadataUpdatedAt = timestamp,
                lastMetadataAttemptAt = timestamp,
                metadataVersion = CURRENT_METADATA_VERSION,
            )
        )
        return SyncItemOutcome.PROCESSED
    }

    private suspend fun updateCoverNoMatch(album: AlbumEntity, timestamp: Long): SyncItemOutcome {
        musicDao.updateAlbumPreservingLocalArtwork(
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
        musicDao.updateAlbumPreservingLocalArtwork(
            album.copy(
                coverLookupState = CoverLookupState.FAILED.name,
                coverLookupAttemptAt = timestamp,
                coverLookupVersion = CURRENT_COVER_LOOKUP_VERSION,
            )
        )
        return SyncItemOutcome.PROCESSED
    }

    private suspend fun updateNoMatch(album: AlbumEntity, timestamp: Long): SyncItemOutcome {
        musicDao.updateAlbumPreservingLocalArtwork(
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
        val lastFmPending = musicDao.getPendingMetadataCount(CURRENT_METADATA_VERSION)
        val localArtworkVerification =
            musicDao.getPendingLocalArtworkVerificationCount()
        return MetadataPendingCounts(
            cover = coverPending,
            lastFm = lastFmPending,
            localArtworkVerification = localArtworkVerification,
        )
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

private fun MusicBrainzMetadataLookup.debugResult(): String {
    return when (this) {
        is MusicBrainzMetadataLookup.Found -> "found"
        MusicBrainzMetadataLookup.NoMatch -> "no_match"
        is MusicBrainzMetadataLookup.RetryableFailure -> "retryable_failure"
    }
}

private fun LastFmAlbumLookup.debugResult(): String {
    return when (this) {
        is LastFmAlbumLookup.Found -> "found"
        LastFmAlbumLookup.NoApiKey -> "no_api_key"
        LastFmAlbumLookup.NoMatch -> "no_match"
        is LastFmAlbumLookup.PermanentFailure -> "permanent_failure"
        is LastFmAlbumLookup.RetryableFailure -> "retryable_failure"
    }
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
    SOURCE_UNAVAILABLE,
}

private data class MetadataPendingCounts(
    val cover: Int,
    val lastFm: Int,
    val localArtworkVerification: Int,
) {
    val total: Int = cover + lastFm + localArtworkVerification
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
        MetadataSyncStopReason.SOURCE_UNAVAILABLE -> "Источник временно недоступен, продолжу позже"
    }
}

private fun MetadataSyncStopReason.toFinalStatusMessage(
    counts: MetadataPendingCounts,
): String {
    return when {
        this == MetadataSyncStopReason.RATE_LIMITED -> "Источник ограничил частоту запросов, продолжу позже"
        this == MetadataSyncStopReason.SOURCE_UNAVAILABLE -> "Источник временно недоступен, продолжу позже"
        this == MetadataSyncStopReason.NO_API_KEY &&
            counts.cover == 0 &&
            counts.localArtworkVerification == 0 ->
            "Обложки обновлены, API-ключ Last.fm не задан"
        counts.total == 0 -> "Метаданные и обложки обновлены"
        else -> "Осталось: локальная проверка ${counts.localArtworkVerification}, " +
            "обложки ${counts.cover}, Last.fm ${counts.lastFm}"
    }
}

private fun MetadataSyncStopReason.toForcedCoverStatusMessage(
    processed: Int,
    verificationPending: Int,
): String {
    return when (this) {
        MetadataSyncStopReason.EMPTY_QUEUE -> if (verificationPending > 0) {
            "Сначала проверяю локальные обложки: осталось $verificationPending"
        } else {
            "MusicBrainz: отсутствующих обложек для проверки нет"
        }
        MetadataSyncStopReason.BATCH_LIMIT -> "MusicBrainz: проверено обложек $processed"
        MetadataSyncStopReason.RATE_LIMITED -> "MusicBrainz ограничил частоту запросов, попробуй позже"
        MetadataSyncStopReason.SOURCE_UNAVAILABLE -> "MusicBrainz временно недоступен, попробуй позже"
        MetadataSyncStopReason.NO_API_KEY -> "MusicBrainz: API-ключ не нужен"
    }
}

private fun MetadataSyncStopReason.stopsRemoteSync(): Boolean {
    return this == MetadataSyncStopReason.RATE_LIMITED ||
        this == MetadataSyncStopReason.SOURCE_UNAVAILABLE
}

private enum class SyncItemOutcome {
    PROCESSED,
    NO_API_KEY,
    RATE_LIMITED,
    SOURCE_UNAVAILABLE,
}

private data class MetadataAlbumDebugResult(
    val outcome: SyncItemOutcome,
    val result: String,
    val reason: String,
)
