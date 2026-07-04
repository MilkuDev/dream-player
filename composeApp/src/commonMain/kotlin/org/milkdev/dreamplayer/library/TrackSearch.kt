package org.milkdev.dreamplayer.library

import kotlin.math.max
import kotlin.math.min

enum class TrackSearchField {
    TITLE,
    ARTIST,
    ALBUM,
}

data class TrackSearchConfig(
    val fields: Set<TrackSearchField>,
    val maxTypos: Int = 2,
)

data class TrackSearchMode(
    val config: TrackSearchConfig,
    val category: SearchCategory = SearchCategory.TRACKS
) {
    companion object {
        val Title = TrackSearchMode(TrackSearchConfig(setOf(TrackSearchField.TITLE)),
            SearchCategory.TRACKS)
        val TitleAndArtist = TrackSearchMode(TrackSearchConfig(setOf(TrackSearchField.TITLE,
            TrackSearchField.ARTIST)), SearchCategory.TRACKS)
        val Artist = TrackSearchMode(TrackSearchConfig(setOf(TrackSearchField.ARTIST)),
            SearchCategory.ARTISTS)
        val Album = TrackSearchMode(TrackSearchConfig(setOf(TrackSearchField.ALBUM)),
            SearchCategory.ALBUMS)
    }
}

enum class SearchCategory {
    TRACKS, ARTISTS, ALBUMS
}

data class LibrarySearchState(
    val isActive: Boolean = false,
    val query: String = "",
    val mode: TrackSearchMode = TrackSearchMode.Title,
)

fun filterTracksByQuery(
    tracks: List<LibraryTrack>,
    query: String,
    config: TrackSearchConfig,
): List<LibraryTrack> {
    val normalizedQuery = query.normalizeForSearch()
    if (normalizedQuery.isBlank()) {
        return tracks
    }

    val queryTokens = normalizedQuery.searchTokens()

    return tracks
        .mapIndexedNotNull { index, track ->
            val score = track.searchScore(normalizedQuery, queryTokens, config) ?: return@mapIndexedNotNull null
            SearchCandidate(track = track, score = score, originalIndex = index)
        }
        .sortedWith(
            compareByDescending<SearchCandidate> { it.score }
                .thenBy { it.originalIndex }
        )
        .map { it.track }
}

private data class SearchCandidate(
    val track: LibraryTrack,
    val score: Double,
    val originalIndex: Int,
)

private fun LibraryTrack.searchScore(
    normalizedQuery: String,
    queryTokens: List<String>,
    config: TrackSearchConfig,
): Double? {
    val candidates = buildList {
        if (TrackSearchField.TITLE in config.fields) {
            add(normalizedField(title) to 1.0)
        }
        if (TrackSearchField.ARTIST in config.fields) {
            add(normalizedField(artistName) to 0.96)
        }
        if (TrackSearchField.ALBUM in config.fields) {
            add(normalizedField(albumName) to 0.92)
        }
        if (config.fields.size > 1) {
            add(
                listOfNotNull(
                    title.takeIf { TrackSearchField.TITLE in config.fields },
                    artistName.takeIf { TrackSearchField.ARTIST in config.fields },
                    albumName.takeIf { TrackSearchField.ALBUM in config.fields },
                ).joinToString(separator = " ").normalizeForSearch() to 0.985
            )
        }
    }

    return candidates
        .mapNotNull { (text, fieldWeight) ->
            scoreText(
                query = normalizedQuery,
                queryTokens = queryTokens,
                target = text,
                maxTypos = config.maxTypos,
            )?.times(fieldWeight)
        }
        .maxOrNull()
}

private fun normalizedField(value: String): String = value.normalizeForSearch()

private fun scoreText(
    query: String,
    queryTokens: List<String>,
    target: String,
    maxTypos: Int,
): Double? {
    if (target.isBlank()) {
        return null
    }

    if (target == query) {
        return 10_000.0
    }

    if (target.startsWith(query)) {
        return 9_200.0 - (target.length - query.length).coerceAtLeast(0) * 0.35
    }

    val targetTokens = target.searchTokens()

    val tokenPrefixIndex = targetTokens.indexOfFirst { it.startsWith(query) }
    if (tokenPrefixIndex >= 0) {
        return 8_500.0 - tokenPrefixIndex * 8.0
    }

    if (queryTokens.size > 1 && queryTokens.all { token -> target.contains(token) }) {
        val densityPenalty = queryTokens.sumOf { token -> target.indexOf(token)
            .coerceAtLeast(0) } * 0.12
        return 7_900.0 - densityPenalty
    }

    val containsIndex = target.indexOf(query)
    if (containsIndex >= 0) {
        return 7_200.0 - containsIndex * 0.7
    }

    val fuzzyTokenScore = targetTokens
        .mapIndexedNotNull { index, token ->
            fuzzyScore(query = query, target = token, maxTypos = maxTypos)
                ?.let { 5_800.0 + it - index * 4.0 }
        }
        .maxOrNull()

    val fuzzyFullScore = fuzzyScore(
        query = query,
        target = target,
        maxTypos = maxTypos,
    )?.let { 5_300.0 + it }

    return listOfNotNull(fuzzyTokenScore, fuzzyFullScore)
        .maxOrNull()
}

private fun fuzzyScore(
    query: String,
    target: String,
    maxTypos: Int,
): Double? {
    if (query.length < 3 || target.isBlank()) {
        return null
    }

    val allowedDistance = min(
        maxTypos,
        when (max(query.length, target.length)) {
            in 0..4 -> 1
            in 5..8 -> 2
            else -> 3
        }
    )

    val distance = levenshteinDistance(query, target, allowedDistance) ?: return null

    val maxLength = max(query.length, target.length).coerceAtLeast(1)
    val similarity = 1.0 - distance.toDouble() / maxLength.toDouble()
    if (similarity < 0.58) {
        return null
    }

    return similarity * 100.0 - distance * 12.0
}

private fun String.normalizeForSearch(): String = buildString(length) {
    for (character in this@normalizeForSearch.lowercase()) {
        append(
            when {
                character.isLetterOrDigit() -> character
                character.isWhitespace() -> ' '
                else -> ' '
            }
        )
    }
}.trim().replace(WHITESPACE_REGEX, " ")

private fun String.searchTokens(): List<String> = split(' ')
    .map { it.trim() }
    .filter { it.isNotEmpty() }

private fun levenshteinDistance(
    source: String,
    target: String,
    maxDistance: Int,
): Int? {
    if (kotlin.math.abs(source.length - target.length) > maxDistance) {
        return null
    }

    var previous = IntArray(target.length + 1) { it }
    var current = IntArray(target.length + 1)

    for (sourceIndex in 1..source.length) {
        current[0] = sourceIndex
        var minInRow = current[0]

        for (targetIndex in 1..target.length) {
            val cost = if (source[sourceIndex - 1] == target[targetIndex - 1]) 0 else 1
            current[targetIndex] = min(
                min(
                    current[targetIndex - 1] + 1,
                    previous[targetIndex] + 1,
                ),
                previous[targetIndex - 1] + cost,
            )
            minInRow = min(minInRow, current[targetIndex])
        }

        if (minInRow > maxDistance) {
            return null
        }

        val swap = previous
        previous = current
        current = swap
    }

    return previous[target.length].takeIf { it <= maxDistance }
}

private val WHITESPACE_REGEX = "\\s+".toRegex()
