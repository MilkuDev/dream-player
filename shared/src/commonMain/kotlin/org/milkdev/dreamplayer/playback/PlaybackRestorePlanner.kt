package org.milkdev.dreamplayer.playback

internal data class PlaybackRestorePlan(
    val originalTrackIds: List<Long>,
    val shuffledTrackIds: List<Long>?,
    val currentTrackId: Long,
    val trackPositionMs: Long,
)

internal fun planPlaybackRestore(
    originalTrackIds: List<Long>,
    shuffledTrackIds: List<Long>?,
    shuffleEnabled: Boolean,
    queueIndex: Int,
    savedCurrentTrackId: Long?,
    savedPositionMs: Long,
    availableTrackIds: Set<Long>,
): PlaybackRestorePlan? {
    val filteredOriginalIds = originalTrackIds
        .distinct()
        .filter { it in availableTrackIds }
    if (filteredOriginalIds.isEmpty()) return null

    val savedActiveIds = if (shuffleEnabled && !shuffledTrackIds.isNullOrEmpty()) {
        shuffledTrackIds
    } else {
        originalTrackIds
    }
    val legacyCurrentTrackId = savedActiveIds.getOrNull(queueIndex)
    val requestedCurrentTrackId = savedCurrentTrackId ?: legacyCurrentTrackId
    val currentTrackId = requestedCurrentTrackId
        ?.takeIf { it in availableTrackIds }
        ?: nearestAvailableTrackId(
            trackIds = savedActiveIds,
            anchorIndex = queueIndex,
            availableTrackIds = availableTrackIds,
        )
        ?: filteredOriginalIds.first()

    val filteredShuffledIds = shuffledTrackIds
        ?.distinct()
        ?.filter { it in availableTrackIds }
        ?.takeIf { shuffled ->
            shuffleEnabled &&
                shuffled.size == filteredOriginalIds.size &&
                shuffled.toSet() == filteredOriginalIds.toSet()
        }

    return PlaybackRestorePlan(
        originalTrackIds = filteredOriginalIds,
        shuffledTrackIds = filteredShuffledIds,
        currentTrackId = currentTrackId,
        trackPositionMs = if (requestedCurrentTrackId == currentTrackId) {
            savedPositionMs.coerceAtLeast(0L)
        } else {
            0L
        },
    )
}

private fun nearestAvailableTrackId(
    trackIds: List<Long>,
    anchorIndex: Int,
    availableTrackIds: Set<Long>,
): Long? {
    if (trackIds.isEmpty()) return null
    val anchor = anchorIndex.coerceIn(0, trackIds.lastIndex)
    for (distance in trackIds.indices) {
        val forwardIndex = anchor + distance
        if (forwardIndex in trackIds.indices) {
            trackIds[forwardIndex].takeIf { it in availableTrackIds }?.let { return it }
        }

        val backwardIndex = anchor - distance
        if (backwardIndex in trackIds.indices && backwardIndex != forwardIndex) {
            trackIds[backwardIndex].takeIf { it in availableTrackIds }?.let { return it }
        }
    }
    return null
}
