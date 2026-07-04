@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.playback

import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.app.applicationContext
import org.milkdev.org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.org.milkdev.dreamplayer.playback.AudioPlayerState
import org.milkdev.org.milkdev.dreamplayer.playback.EmptyPlaybackQueueSnapshot
import org.milkdev.org.milkdev.dreamplayer.playback.PlaybackRepeatMode
import org.milkdev.org.milkdev.dreamplayer.playback.PlaybackSnapshot
import org.milkdev.org.milkdev.dreamplayer.playback.ResolvedPlaybackItem
import org.milkdev.org.milkdev.dreamplayer.playback.TrackAvailability

actual object AudioPlayer {
    private const val TAG = "AudioPlayer"
    private val lock = Any()
    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(AudioPlayerState())
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mediaItemCache = mutableMapOf<MediaItemCacheKey, MediaItem>()

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playbackSnapshot: PlaybackSnapshot? = null

    private var controllerIndexToQueueIndex: List<Int> = emptyList()
    private var repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off

    actual val state: kotlinx.coroutines.flow.StateFlow<AudioPlayerState> = _state.asStateFlow()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val mediaController = player as? MediaController
            if (mediaController != null) {
                syncCurrentTrackFromController(mediaController)
            }
            syncStateFromController(mediaController)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            withController { mediaController ->
                syncCurrentTrackFromController(mediaController)
                mediaController.ensureUpcomingItem()
                syncStateFromController(mediaController)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                withController { mediaController ->
                    syncCurrentTrackFromController(mediaController)
                    syncStateFromController(mediaController)
                }
            }
        }
    }

    actual fun play(snapshot: PlaybackSnapshot) {
        AppDebugLog.log("audio_play_snapshot tracks=${snapshot.queue.trackIds.size} version=${snapshot.queue.queueVersion}")
        setSnapshot(snapshot)
        withController { mediaController ->
            replaceControllerQueue(
                mediaController = mediaController,
                snapshot = snapshot,
                startPositionMs = 0L,
                playWhenReady = true,
                reason = "play_snapshot",
            )
        }
    }

    actual fun updateQueue(snapshot: PlaybackSnapshot) {
        AppDebugLog.log("audio_update_snapshot tracks=${snapshot.queue.trackIds.size} version=${snapshot.queue.queueVersion}")
        withController { mediaController ->
            val currentPlaybackPositionMs = mediaController.currentPosition.coerceAtLeast(0L)
            val shouldPlay = mediaController.playWhenReady
            val previousTrackId = synchronized(lock) {
                playbackSnapshot?.queue?.currentTrackId
            } ?: mediaController.currentMediaItem?.mediaId?.substringAfter("_")?.toLongOrNull()

            setSnapshot(snapshot)
            if (
                snapshot.queue.currentTrackId == previousTrackId &&
                mediaController.tryApplyQueueUpdate(snapshot)
            ) {
                return@withController
            }

            replaceControllerQueue(
                mediaController = mediaController,
                snapshot = snapshot,
                startPositionMs = if (snapshot.queue.currentTrackId == previousTrackId) {
                    currentPlaybackPositionMs
                } else {
                    0L
                },
                playWhenReady = shouldPlay,
                reason = "update_snapshot",
            )
        }
    }

    actual fun moveQueueItem(fromIndex: Int, toIndex: Int, snapshot: PlaybackSnapshot) {
        AppDebugLog.log(
            "audio_move_snapshot from=$fromIndex to=$toIndex tracks=${snapshot.queue.trackIds.size} " +
                    "version=${snapshot.queue.queueVersion}"
        )
        withController { mediaController ->
            setSnapshot(snapshot)
            if (mediaController.tryApplyQueueUpdate(snapshot)) {
                return@withController
            }

            replaceControllerQueue(
                mediaController = mediaController,
                snapshot = snapshot,
                startPositionMs = mediaController.currentPosition.coerceAtLeast(0L),
                playWhenReady = mediaController.playWhenReady,
                reason = "move_snapshot_fallback",
            )
        }
    }

    actual fun pause() {
        AppDebugLog.log("audio_pause")
        withController { mediaController ->
            mediaController.pause()
            syncStateFromController(mediaController)
        }
    }

    actual fun resume() {
        AppDebugLog.log("audio_resume")
        withController { mediaController ->
            if (mediaController.mediaItemCount == 0) {
                playbackSnapshot?.let { snapshot ->
                    replaceControllerQueue(
                        mediaController = mediaController,
                        snapshot = snapshot,
                        startPositionMs = 0L,
                        playWhenReady = true,
                        reason = "resume_empty_controller",
                    )
                }
                return@withController
            }

            mediaController.prepare()
            mediaController.play()
            syncStateFromController(mediaController)
        }
    }

    actual fun stop() {
        AppDebugLog.log("audio_stop")
        withController { mediaController ->
            synchronized(lock) {
                playbackSnapshot = null
                controllerIndexToQueueIndex = emptyList()
            }
            mediaController.stop()
            mediaController.clearMediaItems()
            syncStateFromController(mediaController)
        }
    }

    actual fun seekTo(positionMs: Long) {
        withController { mediaController ->
            if (mediaController.mediaItemCount == 0) return@withController

            mediaController.seekTo(positionMs.coerceAtLeast(0L))
            syncStateFromController(mediaController)
        }
    }

    actual fun skipToPrevious() {
        seekControllerByQueueOffset(offset = -1, reason = "skip_previous")
    }

    actual fun skipToNext() {
        seekControllerByQueueOffset(offset = 1, reason = "skip_next")
    }

    actual fun skipToQueueIndex(index: Int) {
        AppDebugLog.log("audio_skip_to_queue_index index=$index")
        withController { mediaController ->
            val mediaIndex = (0 until mediaController.mediaItemCount).firstOrNull { idx ->
                mediaController.getMediaItemAt(idx).mediaId.substringBefore("_") == index.toString()
            } ?: return@withController

            mediaController.seekTo(mediaIndex, 0L)
            mediaController.play()
            syncCurrentTrackFromController(mediaController)
            syncStateFromController(mediaController)
        }
    }

    actual fun setRepeatMode(mode: PlaybackRepeatMode) {
        AppDebugLog.log("audio_repeat_mode mode=$mode")
        synchronized(lock) {
            repeatMode = mode
        }
        withController { mediaController ->
            mediaController.repeatMode = mode.toMedia3RepeatMode()
            syncStateFromController(mediaController)
        }
    }

    actual fun getCurrentPosition(): Long {
        return synchronized(lock) {
            controller?.currentPosition?.coerceAtLeast(0L) ?: 0L
        }
    }

    private fun seekControllerByQueueOffset(offset: Int, reason: String) {
        AppDebugLog.log("audio_$reason")
        withController { mediaController ->
            if (mediaController.mediaItemCount == 0) return@withController
            val snapshot = synchronized(lock) { playbackSnapshot } ?: return@withController

            val currentMediaItem = mediaController.currentMediaItem ?: return@withController
            val currentQueueIndex = currentMediaItem.mediaId.substringBefore("_").toIntOrNull()
                ?: snapshot.queue.currentIndex

            val targetQueueIndex = when {
                offset < 0 && currentQueueIndex <= 0 -> snapshot.queue.trackIds.lastIndex
                offset < 0 -> currentQueueIndex - 1
                currentQueueIndex >= snapshot.queue.trackIds.lastIndex -> 0
                else -> currentQueueIndex + 1
            }

            val targetMediaIndex = (0 until mediaController.mediaItemCount).firstOrNull { idx ->
                mediaController.getMediaItemAt(idx).mediaId.substringBefore("_") == targetQueueIndex.toString()
            } ?: return@withController

            mediaController.seekTo(targetMediaIndex, 0L)
            mediaController.play()
            syncCurrentTrackFromController(mediaController)
            mediaController.ensureUpcomingItem()
            syncStateFromController(mediaController)
        }
    }

    private fun setSnapshot(snapshot: PlaybackSnapshot) {
        synchronized(lock) {
            playbackSnapshot = snapshot.copy(
                queue = snapshot.queue.copy(trackIds = snapshot.queue.trackIds.copyOf()),
                items = snapshot.items.toList(),
            )
        }
    }

    private fun withController(action: (MediaController) -> Unit) {
        val readyController = synchronized(lock) {
            controller?.also { return@synchronized it }

            pendingActions.addLast(action)
            ensureControllerLocked()
            null
        }

        readyController?.let(action)
    }

    private fun ensureControllerLocked() {
        if (controller != null || controllerFuture != null) return

        val sessionToken = SessionToken(
            applicationContext,
            ComponentName(applicationContext, PlaybackService::class.java)
        )
        val future = MediaController.Builder(applicationContext, sessionToken).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                val resolvedController = runCatching { future.get() }
                    .onFailure { error ->
                        Log.e(TAG, "Unable to connect MediaController", error)
                        AppDebugLog.log("audio_controller_connect_error message=${error.message.orEmpty()}")
                    }
                    .getOrNull()

                if (resolvedController == null) {
                    synchronized(lock) {
                        if (controllerFuture === future) {
                            controllerFuture = null
                        }
                    }
                    return@addListener
                }

                var queuedActions: List<(MediaController) -> Unit> = emptyList()
                var releaseResolvedController = false

                synchronized(lock) {
                    if (controller != null && controller !== resolvedController) {
                        releaseResolvedController = true
                    } else {
                        controller = resolvedController
                        queuedActions = pendingActions.toList()
                        pendingActions.clear()
                    }

                    if (controllerFuture === future) {
                        controllerFuture = null
                    }
                }

                if (releaseResolvedController) {
                    resolvedController.release()
                    return@addListener
                }

                resolvedController.addListener(playerListener)
                resolvedController.repeatMode = synchronized(lock) {
                    repeatMode.toMedia3RepeatMode()
                }
                syncStateFromController(resolvedController)
                queuedActions.forEach { queuedAction ->
                    queuedAction(resolvedController)
                }
            },
            ContextCompat.getMainExecutor(applicationContext)
        )
    }

    private fun replaceControllerQueue(
        mediaController: MediaController,
        snapshot: PlaybackSnapshot,
        startPositionMs: Long,
        playWhenReady: Boolean,
        reason: String,
    ) {
        val availableIndexedItems = snapshot.items.mapIndexed { index, item -> index to item }
            .filter { (_, item) -> item.ref.availability == TrackAvailability.AVAILABLE && item.ref.uri.isNotBlank() }

        val currentIndex = snapshot.queue.currentIndex
        val currentTrackId = snapshot.queue.currentTrackId

        val startIndex = availableIndexedItems.indexOfFirst { (idx, _) -> idx == currentIndex }
            .takeIf { it >= 0 }
            ?: availableIndexedItems.indexOfFirst { (_, item) -> item.trackId == currentTrackId }

        if (availableIndexedItems.isEmpty() || startIndex < 0) {
            AppDebugLog.log(
                "audio_play_snapshot_skip reason=$reason currentTrackId=$currentTrackId " +
                        "availableItems=${availableIndexedItems.size}"
            )
            syncStateFromController(mediaController)
            return
        }

        AppDebugLog.log(
            "audio_play_snapshot_item reason=$reason trackId=$currentTrackId " +
                    "index=${snapshot.queue.currentIndex} queueSize=${snapshot.queue.trackIds.size}"
        )

        mediaController.repeatMode = synchronized(lock) {
            repeatMode.toMedia3RepeatMode()
        }

        playerScope.launch {
            val mediaItems = withContext(Dispatchers.Default) {
                availableIndexedItems.map { (queueIndex, item) -> item.toCachedMediaItem(queueIndex) }
            }
            mediaController.setMediaItems(
                mediaItems,
                startIndex,
                startPositionMs.coerceAtLeast(0L),
            )
            mediaController.prepare()
            if (playWhenReady) {
                mediaController.play()
            } else {
                mediaController.pause()
            }
            syncCurrentTrackFromController(mediaController)
            syncStateFromController(mediaController)
        }
    }

    private fun ResolvedPlaybackItem.toCachedMediaItem(queueIndex: Int): MediaItem {
        val key = MediaItemCacheKey(trackId = trackId, contentVersion = ref.contentVersion, queueIndex = queueIndex)
        return synchronized(lock) {
            mediaItemCache.getOrPut(key) { toMediaItem(queueIndex) }
        }
    }

    private fun syncCurrentTrackFromController(mediaController: MediaController) {
        val currentMediaItem = mediaController.currentMediaItem ?: return
        val playingTrackId = currentMediaItem.mediaId.substringAfter("_").toLongOrNull() ?: return

        synchronized(lock) {
            val snapshot = playbackSnapshot ?: return@synchronized
            val currentControllerIndex = mediaController.currentMediaItemIndex

            if (currentControllerIndex in snapshot.queue.trackIds.indices) {
                val trackIdAtMediaIndex = snapshot.queue.trackIds[currentControllerIndex]

                if (trackIdAtMediaIndex == playingTrackId) {
                    playbackSnapshot = snapshot.copy(
                        queue = snapshot.queue.copy(
                            currentIndex = currentControllerIndex,
                            currentTrackId = playingTrackId
                        )
                    )
                }
            }
        }
    }

    private fun MediaController.tryApplyQueueMove(fromIndex: Int, toIndex: Int, snapshot: PlaybackSnapshot): Boolean {
        if (toIndex !in 0 until mediaItemCount) return false

        val resolvedFromIndex = synchronized(lock) {
            controllerIndexToQueueIndex.indexOf(fromIndex)
        }.takeIf { it >= 0 } ?: return false

        val resolvedToIndex = synchronized(lock) {
            controllerIndexToQueueIndex.indexOf(toIndex)
        }.takeIf { it >= 0 } ?: return false

        if (resolvedFromIndex != resolvedToIndex) {
            moveMediaItem(resolvedFromIndex, resolvedToIndex)
        }

        val availableIndexedItems = snapshot.items.mapIndexed { index, item -> index to item }
            .filter { (_, item) -> item.ref.availability == TrackAvailability.AVAILABLE && item.ref.uri.isNotBlank() }

        synchronized(lock) {
            controllerIndexToQueueIndex = availableIndexedItems.map { it.first }
        }
        return true
    }

    private fun MediaController.tryApplyQueueUpdate(snapshot: PlaybackSnapshot): Boolean {
        val mediaController = this
        val currentMediaItem = currentMediaItem ?: return false

        val currentTrackId = currentMediaItem.mediaId.substringAfter("_").toLongOrNull() ?: return false
        if (currentTrackId != snapshot.queue.currentTrackId) return false

        val availableIndexedItems = snapshot.items.mapIndexed { index, item -> index to item }
            .filter { (_, item) -> item.ref.availability == TrackAvailability.AVAILABLE && item.ref.uri.isNotBlank() }

        if (availableIndexedItems.size != mediaItemCount) return false

        val targetTrackIds = availableIndexedItems.map { it.second.trackId }
        val currentTrackIds = currentTrackIds()
        if (!currentTrackIds.hasSameTrackIdsAs(targetTrackIds)) return false

        val targetCurrentIndex = availableIndexedItems.indexOfFirst { (queueIndex, item) ->
            queueIndex == snapshot.queue.currentIndex && item.trackId == currentTrackId
        }.takeIf { it >= 0 } ?: availableIndexedItems.indexOfFirst { it.second.trackId == currentTrackId }

        if (targetCurrentIndex !in availableIndexedItems.indices) return false

        val currentControllerIndex = currentMediaItemIndex
        if (currentControllerIndex !in 0 until mediaItemCount) return false

        if (currentControllerIndex != targetCurrentIndex) {
            moveMediaItem(currentControllerIndex, targetCurrentIndex)
        }

        // 2. Синхронно маппим элементы без ухода во внешние потоки
        val targetMediaItems = availableIndexedItems.map { (queueIndex, item) ->
            item.toCachedMediaItem(queueIndex)
        }

        if (targetCurrentIndex > 0) {
            replaceMediaItems(0, targetCurrentIndex, targetMediaItems.subList(0, targetCurrentIndex))
        }
        if (targetCurrentIndex < targetMediaItems.lastIndex) {
            replaceMediaItems(
                targetCurrentIndex + 1,
                mediaController.mediaItemCount,
                targetMediaItems.subList(targetCurrentIndex + 1, targetMediaItems.size)
            )
        }

        ensureUpcomingItem(availableIndexedItems.map { it.second }, currentTrackId)

        AppDebugLog.log("audio_update_snapshot_in_place trackId=$currentTrackId queueSize=${snapshot.queue.trackIds.size}")
        return true
    }

    private fun MediaController.ensureUpcomingItem() {
        val snapshot = synchronized(lock) { playbackSnapshot } ?: return
        val currentTrackId = currentMediaItem?.mediaId?.substringAfter("_")?.toLongOrNull()
            ?: snapshot.queue.currentTrackId
            ?: return

        val availableItems = snapshot.items
            .filter { item -> item.ref.availability == TrackAvailability.AVAILABLE && item.ref.uri.isNotBlank() }
        ensureUpcomingItem(availableItems, currentTrackId)
    }

    private fun MediaController.ensureUpcomingItem(
        targetItems: List<ResolvedPlaybackItem>,
        currentTrackId: Long,
    ) {
        if (targetItems.size <= 1 || mediaItemCount <= 1) return

        val targetCurrentIndex = targetItems.indexOfFirst { item -> item.trackId == currentTrackId }
        if (targetCurrentIndex !in targetItems.indices) return

        val playbackRepeatMode = synchronized(lock) { this@AudioPlayer.repeatMode }
        val nextTargetItem = when {
            targetCurrentIndex < targetItems.lastIndex -> targetItems[targetCurrentIndex + 1]
            playbackRepeatMode == PlaybackRepeatMode.Queue -> targetItems.first()
            else -> null
        } ?: return

        val currentControllerIndex = currentMediaItemIndex
        if (currentControllerIndex !in 0 until mediaItemCount) return

        val nextControllerIndex = when {
            currentControllerIndex < mediaItemCount - 1 -> currentControllerIndex + 1
            playbackRepeatMode == PlaybackRepeatMode.Queue -> 0
            else -> return
        }
        if (getMediaItemAt(nextControllerIndex).mediaId.substringAfter("_").toLongOrNull() == nextTargetItem.trackId) return

        val nextMediaIndex = (0 until mediaItemCount).firstOrNull { idx ->
            getMediaItemAt(idx).mediaId.substringAfter("_").toLongOrNull() == nextTargetItem.trackId
        } ?: return
        if (nextMediaIndex == currentControllerIndex) return

        moveMediaItem(nextMediaIndex, nextControllerIndex)
    }

    private fun MediaController.findMediaItemIndex(trackId: Long): Int? {
        return (0 until mediaItemCount).firstOrNull { index ->
            getMediaItemAt(index).mediaId.substringAfter("_").toLongOrNull() == trackId
        }
    }

    private fun syncStateFromController(mediaController: MediaController? = synchronized(lock) { controller }) {
        val snapshot = synchronized(lock) { playbackSnapshot }
        val queue = snapshot?.queue ?: EmptyPlaybackQueueSnapshot
        val currentTrackId = queue.currentTrackId
        val metadataDuration = snapshot?.items
            ?.firstOrNull { it.trackId == currentTrackId }
            ?.metadata
            ?.durationMs
            ?: 0L
        val mediaDuration = mediaController?.duration ?: C.TIME_UNSET
        val resolvedDurationMs = when {
            isKnownDuration(mediaDuration) -> mediaDuration
            metadataDuration > 0L -> metadataDuration
            else -> 0L
        }

        _state.value = AudioPlayerState(
            currentTrackId = currentTrackId,
            isPlaying = mediaController?.isPlaying == true,
            totalDurationMs = resolvedDurationMs,
            queue = queue.copy(trackIds = queue.trackIds.copyOf()),
        )
    }
}

private fun MediaController.currentTrackIds(): List<Long> {
    return (0 until mediaItemCount).mapNotNull { index ->
        getMediaItemAt(index).mediaId.substringAfter("_").toLongOrNull()
    }
}

private fun List<Long>.hasSameTrackIdsAs(other: List<Long>): Boolean {
    if (size != other.size) return false

    val counts = groupingBy { it }.eachCount().toMutableMap()
    other.forEach { id ->
        val count = counts[id] ?: return false
        if (count == 1) {
            counts.remove(id)
        } else {
            counts[id] = count - 1
        }
    }
    return counts.isEmpty()
}

private data class MediaItemCacheKey(
    val trackId: Long,
    val contentVersion: Long,
    val queueIndex: Int
)

private const val FULL_TIMELINE_REPLACE_LIMIT = 500
private const val EXTRA_TRACK_DURATION_MS = "dreamplayer.track.DURATION_MS"

private fun ResolvedPlaybackItem.toMediaItem(queueIndex: Int): MediaItem {
    val metadataExtras = Bundle().apply {
        putLong(EXTRA_TRACK_DURATION_MS, metadata.durationMs)
    }

    return MediaItem.Builder()
        .setMediaId("${queueIndex}_${trackId}")
        .setUri(ref.uri.toUri())
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(metadata.title)
                .setArtist(metadata.artistName)
                .setAlbumTitle(metadata.albumName)
                .setArtworkUri(metadata.albumArtUri?.toUri())
                .setExtras(metadataExtras)
                .build()
        )
        .build()
}

private fun PlaybackRepeatMode.toMedia3RepeatMode(): Int {
    return when (this) {
        PlaybackRepeatMode.Off -> Player.REPEAT_MODE_OFF
        PlaybackRepeatMode.Queue -> Player.REPEAT_MODE_ALL
        PlaybackRepeatMode.One -> Player.REPEAT_MODE_ONE
    }
}

private fun isKnownDuration(durationMs: Long): Boolean {
    return durationMs != C.TIME_UNSET && durationMs >= 0L
}