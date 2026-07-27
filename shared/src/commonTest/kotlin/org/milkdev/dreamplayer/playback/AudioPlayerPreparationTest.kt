package org.milkdev.dreamplayer.playback

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class AudioPlayerPreparationTest {

    @Test
    fun prepare_exposesRestoredPositionWithoutStartingPlayback() {
        try {
            AudioPlayer.prepare(testSnapshot(), startPositionMs = 85_955L)

            val playerState = AudioPlayer.state.value
            val timeSnapshot = AudioPlayer.playbackTimeSource.snapshot()

            assertEquals(TEST_TRACK_ID, playerState.currentTrackId)
            assertEquals(TEST_DURATION_MS, playerState.totalDurationMs)
            assertFalse(playerState.isPlaying)
            assertEquals(85_955L, timeSnapshot.positionMs)
            assertEquals(TEST_DURATION_MS, timeSnapshot.durationMs)
            assertFalse(timeSnapshot.isPlaying)
        } finally {
            AudioPlayer.stop()
        }
    }

    @Test
    fun seek_updatesLogicalPositionBeforePlaybackSessionExists() {
        try {
            AudioPlayer.prepare(testSnapshot(), startPositionMs = 12_000L)

            AudioPlayer.seekTo(97_500L)

            assertEquals(
                97_500L,
                AudioPlayer.playbackTimeSource.snapshot().positionMs,
            )
            assertFalse(AudioPlayer.state.value.isPlaying)
        } finally {
            AudioPlayer.stop()
        }
    }

    @Test
    fun logicalPosition_isClampedToTrackDuration() {
        try {
            AudioPlayer.prepare(testSnapshot(), startPositionMs = TEST_DURATION_MS + 1_000L)
            assertEquals(
                TEST_DURATION_MS,
                AudioPlayer.playbackTimeSource.snapshot().positionMs,
            )

            AudioPlayer.seekTo(-1_000L)
            assertEquals(
                0L,
                AudioPlayer.playbackTimeSource.snapshot().positionMs,
            )
        } finally {
            AudioPlayer.stop()
        }
    }

    private fun testSnapshot(): PlaybackSnapshot {
        return PlaybackSnapshot(
            queue = PlaybackQueueSnapshot(
                queueVersion = 1L,
                trackIds = longArrayOf(TEST_TRACK_ID),
                currentIndex = 0,
                currentTrackId = TEST_TRACK_ID,
            ),
            items = listOf(
                ResolvedPlaybackItem(
                    ref = PlaybackItemRef(
                        trackId = TEST_TRACK_ID,
                        uri = "file:///logical-prepare-test.mp3",
                        availability = TrackAvailability.AVAILABLE,
                        contentVersion = 1L,
                    ),
                    metadata = TrackPlaybackMetadata(
                        title = "Logical prepare test",
                        artistName = "DreamPlayer",
                        albumName = "Tests",
                        durationMs = TEST_DURATION_MS,
                        albumArtUri = null,
                    ),
                )
            ),
        )
    }

    private companion object {
        const val TEST_TRACK_ID = 42L
        const val TEST_DURATION_MS = 180_000L
    }
}
