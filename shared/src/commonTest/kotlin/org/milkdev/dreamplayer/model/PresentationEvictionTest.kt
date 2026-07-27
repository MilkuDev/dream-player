package org.milkdev.dreamplayer.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PresentationEvictionTest {
    private val token = PresentationEvictionToken(
        entryId = 42L,
        removedAtRevision = 8L,
    )

    @Test
    fun matchingOwnerCanAcknowledgeEntryThatRemainsRemoved() {
        assertTrue(
            canAcknowledgePresentationEviction(
                token = token,
                pendingToken = token,
                currentEntryIds = setOf(0L, 7L),
                activePresentationOwnerEpoch = 3L,
                acknowledgementOwnerEpoch = 3L,
            ),
        )
    }

    @Test
    fun staleRevisionOwnerOrRestoredEntryRejectAcknowledgement() {
        assertFalse(
            canAcknowledgePresentationEviction(
                token = token,
                pendingToken = token.copy(removedAtRevision = 9L),
                currentEntryIds = emptySet(),
                activePresentationOwnerEpoch = 3L,
                acknowledgementOwnerEpoch = 3L,
            ),
        )
        assertFalse(
            canAcknowledgePresentationEviction(
                token = token,
                pendingToken = token,
                currentEntryIds = emptySet(),
                activePresentationOwnerEpoch = 4L,
                acknowledgementOwnerEpoch = 3L,
            ),
        )
        assertFalse(
            canAcknowledgePresentationEviction(
                token = token,
                pendingToken = token,
                currentEntryIds = setOf(token.entryId),
                activePresentationOwnerEpoch = 3L,
                acknowledgementOwnerEpoch = 3L,
            ),
        )
    }
}
