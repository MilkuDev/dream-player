package org.milkdev.dreamplayer.extensions.ai


private val CompactWhitespaceRegex = "\\s+".toRegex()

fun formatAiPlaylistCandidates(
    candidates: List<AiPlaylistCandidate>,
    limit: Int = 200,
): String {
    return candidates
        .take(limit)
        .joinToString(separator = "\n") { candidate ->
            listOf(
                candidate.id.toString(),
                candidate.artist.compactAiField(),
                candidate.title.compactAiField(),
                candidate.album.compactAiField(),
            ).joinToString(separator = "\t")
        }
}

private fun String.compactAiField(): String {
    return replace('\t', ' ')
        .replace('\n', ' ')
        .replace('\r', ' ')
        .trim()
        .replace(CompactWhitespaceRegex, " ")
}
