package org.milkdev.dreamplayer.extensions.data

import org.milkdev.dreamplayer.diagnostics.MetadataDebugLog
import org.milkdev.dreamplayer.extensions.network.LastFmErrorType
import org.milkdev.dreamplayer.extensions.network.LastFmResult
import org.milkdev.dreamplayer.extensions.network.LastFmService
import org.milkdev.dreamplayer.extensions.secrets.LastFmSecretStore

class LastFmRepository(private val lastFmService: LastFmService) {

    suspend fun getAlbumCoverUrl(
        artist: String,
        album: String,
        apiKey: String,
    ): String? {
        val result = getAlbumInfo(artist, album, apiKey)
        return (result as? LastFmResult.Success)?.value?.album?.image?.let { selectBestLastFmImageUrl(it) }
    }

    suspend fun lookupAlbumMetadata(artist: String, album: String): LastFmAlbumLookup {
        if (artist.isBlank() || album.isBlank()) {
            MetadataDebugLog.event(
                "metadata_provider_result",
                "provider" to "lastfm",
                "result" to "no_match",
                "reason" to "blank_query",
            )
            return LastFmAlbumLookup.NoMatch
        }

        val apiKey = LastFmSecretStore.getApiKey()?.takeIf { it.isNotBlank() }
        if (apiKey == null) {
            MetadataDebugLog.event(
                "metadata_provider_result",
                "provider" to "lastfm",
                "result" to "skipped",
                "reason" to "no_api_key",
            )
            return LastFmAlbumLookup.NoApiKey
        }

        return lookupAlbumMetadata(artist = artist, album = album, apiKey = apiKey)
    }

    suspend fun lookupAlbumMetadata(
        artist: String,
        album: String,
        apiKey: String,
        mbid: String? = null,
        expectedYear: Int? = null,
    ): LastFmAlbumLookup {
        MetadataDebugLog.event(
            "metadata_provider_lookup_start",
            "provider" to "lastfm",
            "hasMbid" to !mbid.isNullOrBlank(),
            "hasExpectedYear" to (expectedYear != null),
        )
        return when (val response = resolveAlbumInfo(artist = artist, album = album, apiKey = apiKey, mbid = mbid, expectedYear = expectedYear)) {
            is LastFmResult.Success -> {
                val albumData = response.value.album
                if (albumData == null) {
                    MetadataDebugLog.event(
                        "metadata_provider_result",
                        "provider" to "lastfm",
                        "result" to "no_match",
                        "reason" to "empty_album",
                    )
                    return LastFmAlbumLookup.NoMatch
                }

                val metadata = albumData.toMetadata()
                if (metadata.hasAnyValue()) {
                    MetadataDebugLog.event(
                        "metadata_provider_result",
                        "provider" to "lastfm",
                        "result" to "found",
                        "yearPresent" to (metadata.year != null),
                        "genreCount" to metadata.genres.size,
                        "coverPresent" to !metadata.coverUrl.isNullOrBlank(),
                        "sourceMbidPresent" to !metadata.sourceMbid.isNullOrBlank(),
                    )
                    LastFmAlbumLookup.Found(metadata)
                } else {
                    MetadataDebugLog.event(
                        "metadata_provider_result",
                        "provider" to "lastfm",
                        "result" to "no_match",
                        "reason" to "no_useful_fields",
                    )
                    LastFmAlbumLookup.NoMatch
                }
            }
            is LastFmResult.Failure -> {
                MetadataDebugLog.event(
                    "metadata_provider_result",
                    "provider" to "lastfm",
                    "result" to "failure",
                    "failureType" to response.type,
                    "apiCode" to response.code,
                    "httpStatus" to response.httpStatusCode,
                )
                when (response.type) {
                    LastFmErrorType.NotFound -> LastFmAlbumLookup.NoMatch
                    LastFmErrorType.InvalidApiKey,
                    LastFmErrorType.SuspendedApiKey,
                    LastFmErrorType.Blocked -> LastFmAlbumLookup.PermanentFailure(response.message)
                    LastFmErrorType.RateLimited -> LastFmAlbumLookup.RetryableFailure(
                        message = response.message,
                        isRateLimited = true,
                    )
                    LastFmErrorType.Temporary,
                    LastFmErrorType.HttpError,
                    LastFmErrorType.Network,
                    LastFmErrorType.InvalidResponse -> LastFmAlbumLookup.RetryableFailure(response.message)
                }
            }
        }
    }

    private suspend fun getAlbumInfo(artist: String, album: String, apiKey: String): LastFmResult<LastFmAlbumResponse> {
        return lastFmService.getAlbumInfo(
            apiKey = apiKey,
            artist = artist.trim(),
            album = album.trim(),
        )
    }

    private suspend fun resolveAlbumInfo(
        artist: String,
        album: String,
        apiKey: String,
        mbid: String?,
        expectedYear: Int?,
    ): LastFmResult<LastFmAlbumResponse> {
        val cleanMbid = mbid?.takeIf { it.isNotBlank() }
        if (cleanMbid != null) {
            when (val byMbid = lastFmService.getAlbumInfoByMbid(apiKey = apiKey, mbid = cleanMbid)) {
                is LastFmResult.Success -> if (byMbid.value.album != null) return byMbid
                is LastFmResult.Failure -> if (byMbid.type != LastFmErrorType.NotFound) return byMbid
            }
        }

        when (val direct = getAlbumInfo(artist = artist, album = album, apiKey = apiKey)) {
            is LastFmResult.Success -> if (direct.value.album != null) return direct
            is LastFmResult.Failure -> if (direct.type != LastFmErrorType.NotFound) return direct
        }

        val search = lastFmService.searchAlbums(apiKey = apiKey, album = album)
        if (search is LastFmResult.Failure) return search

        val bestCandidate = (search as LastFmResult.Success).value.results
            ?.albummatches
            ?.album
            .orEmpty()
            .map { candidate -> candidate to candidate.scoreAgainst(artist = artist, album = album, expectedYear = expectedYear) }
            .filter { (_, score) -> score >= MIN_LASTFM_CANDIDATE_SCORE }
            .maxByOrNull { (_, score) -> score }
            ?.first
            ?: return LastFmResult.Failure(
                type = LastFmErrorType.NotFound,
                message = "Last.fm не нашел подходящий альбом",
            )

        return getAlbumInfo(artist = bestCandidate.artist, album = bestCandidate.name, apiKey = apiKey)
    }

    private fun parseYear(dateString: String?): Int? {
        if (dateString == null) return null
        return yearRegex.find(dateString)?.value?.toIntOrNull()
    }

    private fun String.normalizeGenre(): String {
        return this.trim()
            .lowercase()
            .replace(Regex("\\s+"), " ")
    }

    @Suppress("RemoveExplicitTypeArguments")
    private fun LastFmAlbum.toMetadata(): LastFmAlbumMetadata {
        val receivedTagCount = toptags?.tag.orEmpty().size
        val validGenres = toptags?.tag
            .orEmpty()
            .asSequence()
            .sortedWith(compareByDescending<LastFmTag> { it.count ?: 0 })
            .map { it.name.normalizeGenre() }
            .filter {
                it.isNotBlank() &&
                        !it.contains("albums i own") &&
                        !it.contains("favorite") &&
                        !it.contains("seen live")
            }
            .distinct()
            .take(MAX_LASTFM_GENRES)
            .toList()
        MetadataDebugLog.event(
            "metadata_provider_tags",
            "provider" to "lastfm",
            "received" to receivedTagCount,
            "accepted" to validGenres.size,
        )

        return LastFmAlbumMetadata(
            year = parseYear(releasedate),
            genre = validGenres.takeIf { it.isNotEmpty() }?.joinToString(", "),
            coverUrl = selectBestLastFmImageUrl(image.orEmpty()),
            genres = validGenres,
            sourceMbid = mbid?.takeIf { it.isNotBlank() },
            confidence = 1f,
        )
    }

    private fun LastFmAlbumSearchItem.scoreAgainst(
        artist: String,
        album: String,
        expectedYear: Int?,
    ): Int {
        val artistMatches = this.artist.normalizedMusicKey() == artist.normalizedMusicKey()
        val albumMatches = name.normalizedMusicKey() == album.normalizedMusicKey()
        val hasEditionPenalty = EDITION_WORDS.any { name.contains(it, ignoreCase = true) } &&
            EDITION_WORDS.none { album.contains(it, ignoreCase = true) }
        return (if (artistMatches) 100 else 0) +
            (if (albumMatches) 100 else 0) +
            (if (!mbid.isNullOrBlank()) 40 else 0) +
            (expectedYear?.let { 10 } ?: 0) -
            (if (hasEditionPenalty) 30 else 0)
    }

    private fun String.normalizedMusicKey(): String {
        val characters = buildString {
            this@normalizedMusicKey.trim().lowercase().forEach { character ->
                append(if (character.isLetterOrDigit()) character else ' ')
            }
        }
        return characters.splitToSequence(' ')
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private companion object {
        val yearRegex = Regex("\\d{4}")
        const val MAX_LASTFM_GENRES = 5
        const val MIN_LASTFM_CANDIDATE_SCORE = 180
        val EDITION_WORDS = listOf("deluxe", "remaster", "remastered", "anniversary", "edition", "bonus")
    }
}

sealed interface LastFmAlbumLookup {
    data class Found(val metadata: LastFmAlbumMetadata) : LastFmAlbumLookup
    object NoApiKey : LastFmAlbumLookup
    object NoMatch : LastFmAlbumLookup
    data class RetryableFailure(
        val message: String,
        val isRateLimited: Boolean = false,
    ) : LastFmAlbumLookup
    data class PermanentFailure(val message: String) : LastFmAlbumLookup
}

data class LastFmAlbumMetadata(
    val year: Int?,
    val genre: String?,
    val coverUrl: String?,
    val genres: List<String> = genre?.let(::listOf).orEmpty(),
    val sourceMbid: String? = null,
    val confidence: Float = 0f,
) {
    fun hasAnyValue(): Boolean {
        return year != null || genres.isNotEmpty() || !genre.isNullOrBlank() || !coverUrl.isNullOrBlank()
    }
}

fun selectBestLastFmImageUrl(images: List<LastFmImage>): String? {
    return images.firstOrNull { it.size == "mega" && it.url.isNotBlank() }?.url
        ?: images.firstOrNull { it.size == "extralarge" && it.url.isNotBlank() }?.url
        ?: images.lastOrNull { it.url.isNotBlank() }?.url
}
