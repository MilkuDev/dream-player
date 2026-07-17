package org.milkdev.dreamplayer.playback

interface PlaybackTimeSource {
    fun snapshot(): PlaybackTimeSnapshot
}
