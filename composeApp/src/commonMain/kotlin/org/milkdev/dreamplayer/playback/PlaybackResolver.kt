package org.milkdev.dreamplayer.playback

import org.milkdev.dreamplayer.library.MusicLibrarySource

object PlaybackResolver {
    suspend fun resolve(queue: PlaybackQueueSnapshot): PlaybackSnapshot {
        val requestedQueue = queue.copy(trackIds = queue.trackIds.copyOf())
        return PlaybackSnapshot(
            queue = requestedQueue,
            items = MusicLibrarySource.resolvePlayableItems(requestedQueue.trackIds),
        )
    }
}
