package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.library.MusicLibrarySource

object PlaybackResolver {
    suspend fun resolve(queue: PlaybackQueueSnapshot): PlaybackSnapshot =
        withContext(Dispatchers.Default) {
            val requestedQueue = queue.copy(trackIds = queue.trackIds.copyOf())
            PlaybackSnapshot(
                queue = requestedQueue,
                items = MusicLibrarySource.resolvePlayableItems(requestedQueue.trackIds),
            )
        }
}
