package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.test.runTest
import org.milkdev.dreamplayer.library.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackPersistenceTest {

    @Test
    fun coordinator_processesPlaybackWritesInEnqueueOrder() = runTest {
        val events = mutableListOf<String>()
        val coordinator = PlaybackPersistenceCoordinator(
            scope = backgroundScope,
            persistState = { state -> events += "state:${state.currentTrackId}" },
            persistPosition = { trackId, positionMs ->
                events += "position:$trackId:$positionMs"
            },
            clearPersistedState = { events += "clear" },
        )

        coordinator.saveState(savedState(currentTrackId = 20L))
        coordinator.savePosition(trackId = 20L, positionMs = 42_000L)
        coordinator.clear()
        coordinator.flush()

        assertEquals(
            listOf("state:20", "position:20:42000", "clear"),
            events,
        )
    }

    @Test
    fun checkpointTracker_savesByDistanceAndResetsForNewTrack() {
        val tracker = PlaybackProgressCheckpointTracker(checkpointDistanceMs = 10_000L)

        assertFalse(tracker.shouldCheckpoint(trackId = 1L, positionMs = 0L, isPlaying = true))
        assertFalse(tracker.shouldCheckpoint(trackId = 1L, positionMs = 9_999L, isPlaying = true))
        assertTrue(tracker.shouldCheckpoint(trackId = 1L, positionMs = 10_000L, isPlaying = true))
        assertFalse(tracker.shouldCheckpoint(trackId = 1L, positionMs = 15_000L, isPlaying = false))
        assertTrue(tracker.shouldCheckpoint(trackId = 1L, positionMs = 20_000L, isPlaying = true))
        assertFalse(tracker.shouldCheckpoint(trackId = 2L, positionMs = 80_000L, isPlaying = true))
    }

    @Test
    fun restorePlan_usesCurrentTrackIdFromShuffledQueue() {
        val plan = planPlaybackRestore(
            originalTrackIds = listOf(1L, 2L, 3L, 4L),
            shuffledTrackIds = listOf(3L, 1L, 4L, 2L),
            shuffleEnabled = true,
            queueIndex = 2,
            savedCurrentTrackId = 4L,
            savedPositionMs = 85_955L,
            availableTrackIds = setOf(1L, 2L, 3L, 4L),
        )

        assertNotNull(plan)
        assertEquals(4L, plan.currentTrackId)
        assertEquals(85_955L, plan.trackPositionMs)
        assertEquals(listOf(3L, 1L, 4L, 2L), plan.shuffledTrackIds)
    }

    @Test
    fun restorePlan_supportsLegacyStateWithoutExplicitCurrentTrackId() {
        val plan = planPlaybackRestore(
            originalTrackIds = listOf(1L, 2L, 3L, 4L),
            shuffledTrackIds = listOf(3L, 1L, 4L, 2L),
            shuffleEnabled = true,
            queueIndex = 2,
            savedCurrentTrackId = null,
            savedPositionMs = 85_955L,
            availableTrackIds = setOf(1L, 2L, 3L, 4L),
        )

        assertNotNull(plan)
        assertEquals(4L, plan.currentTrackId)
        assertEquals(85_955L, plan.trackPositionMs)
    }

    @Test
    fun restorePlan_preservesCurrentTrackWhenEarlierFileIsMissing() {
        val plan = planPlaybackRestore(
            originalTrackIds = listOf(1L, 2L, 3L),
            shuffledTrackIds = null,
            shuffleEnabled = false,
            queueIndex = 2,
            savedCurrentTrackId = 3L,
            savedPositionMs = 35_000L,
            availableTrackIds = setOf(2L, 3L),
        )

        assertNotNull(plan)
        assertEquals(listOf(2L, 3L), plan.originalTrackIds)
        assertEquals(3L, plan.currentTrackId)
        assertEquals(35_000L, plan.trackPositionMs)
        assertNull(plan.shuffledTrackIds)
    }

    @Test
    fun restorePlan_resetsPositionWhenSavedCurrentTrackIsMissing() {
        val plan = planPlaybackRestore(
            originalTrackIds = listOf(1L, 2L, 3L),
            shuffledTrackIds = null,
            shuffleEnabled = false,
            queueIndex = 1,
            savedCurrentTrackId = 2L,
            savedPositionMs = 70_000L,
            availableTrackIds = setOf(1L, 3L),
        )

        assertNotNull(plan)
        assertEquals(3L, plan.currentTrackId)
        assertEquals(0L, plan.trackPositionMs)
    }

    private fun savedState(currentTrackId: Long): SettingsRepository.SavedPlaybackState {
        return SettingsRepository.SavedPlaybackState(
            queueTrackIds = listOf(10L, currentTrackId),
            queueShuffledIds = null,
            queueIndex = 1,
            currentTrackId = currentTrackId,
            trackPositionMs = 0L,
            shuffleEnabled = false,
            repeatMode = PlaybackRepeatMode.Off.name,
        )
    }
}
