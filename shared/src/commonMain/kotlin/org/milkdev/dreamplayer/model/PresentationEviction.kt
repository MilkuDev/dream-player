package org.milkdev.dreamplayer.model

data class PresentationEvictionToken(
    val entryId: Long,
    val removedAtRevision: Long,
)

internal fun canAcknowledgePresentationEviction(
    token: PresentationEvictionToken,
    pendingToken: PresentationEvictionToken?,
    currentEntryIds: Set<Long>,
    activePresentationOwnerEpoch: Long?,
    acknowledgementOwnerEpoch: Long,
): Boolean {
    return pendingToken == token &&
        activePresentationOwnerEpoch == acknowledgementOwnerEpoch &&
        token.entryId !in currentEntryIds
}
