package org.milkdev.dreamplayer.extensions.ai

data class AiPlaylistResolvedSelection(
    val selectedIds: List<Long>,
    val acceptedAiIds: List<Long>,
    val rejectedAiIds: List<Long>,
    val fallbackIds: List<Long>,
)

fun resolveRecommendedAiPlaylistIds(
    candidateIds: List<Long>,
    recommendedIds: List<Long>,
    limit: Int,
): List<Long> {
    return resolveRecommendedAiPlaylistSelection(
        candidateIds = candidateIds,
        recommendedIds = recommendedIds,
        limit = limit,
    ).selectedIds
}

fun resolveRecommendedAiPlaylistSelection(
    candidateIds: List<Long>,
    recommendedIds: List<Long>,
    limit: Int,
): AiPlaylistResolvedSelection {
    val allowedIds = candidateIds.toSet()
    val accepted = mutableListOf<Long>()
    val acceptedSet = mutableSetOf<Long>()
    val rejected = mutableListOf<Long>()

    recommendedIds.forEach { id ->
        when {
            id !in allowedIds -> rejected += id
            id in acceptedSet -> rejected += id
            accepted.size < limit -> {
                accepted += id
                acceptedSet += id
            }
        }
    }

    val fallback = candidateIds
        .filter { it !in acceptedSet }
        .take((limit - accepted.size).coerceAtLeast(0))

    return AiPlaylistResolvedSelection(
        selectedIds = (accepted + fallback)
            .take(limit),
        acceptedAiIds = accepted,
        rejectedAiIds = rejected,
        fallbackIds = fallback,
    )
}
