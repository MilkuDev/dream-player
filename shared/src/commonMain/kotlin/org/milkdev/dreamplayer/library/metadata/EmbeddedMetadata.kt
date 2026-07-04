@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING", "SpellCheckingInspection")

package org.milkdev.dreamplayer.library.metadata

import org.milkdev.dreamplayer.library.RawTrackData

data class EmbeddedMetadata(
    val recordingMbid: String? = null,
    val releaseMbid: String? = null,
    val releaseGroupMbid: String? = null,
    val artistMbids: List<String> = emptyList(),
    val albumArtistMbids: List<String> = emptyList(),
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val tagFingerprint: String? = null,
)

expect object EmbeddedMetadataReader {
    suspend fun read(rawTrack: RawTrackData): EmbeddedMetadata?
}

fun normalizeEmbeddedGenre(value: String): String {
    return value.trim()
        .lowercase()
        .replace(Regex("\\s+"), " ")
}

fun splitEmbeddedGenres(value: String?): List<String> {
    return value.orEmpty()
        .split(';', ',', '/')
        .asSequence()
        .map(::normalizeEmbeddedGenre)
        .filter { it.isNotBlank() }
        .distinct()
        .take(MAX_EMBEDDED_GENRES)
        .toList()
}

fun parseEmbeddedYear(value: String?): Int? {
    return value?.let { yearRegex.find(it)?.value?.toIntOrNull() }
}

fun splitMusicBrainzIds(value: String?): List<String> {
    return value.orEmpty()
        .split(';', ',', '/', '\\')
        .asSequence()
        .map { it.trim() }
        .filter { musicBrainzIdRegex.matches(it) }
        .distinct()
        .toList()
}

fun firstMusicBrainzId(value: String?): String? {
    return splitMusicBrainzIds(value).firstOrNull()
}

private const val MAX_EMBEDDED_GENRES = 8
private val yearRegex = Regex("\\d{4}")
private val musicBrainzIdRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

