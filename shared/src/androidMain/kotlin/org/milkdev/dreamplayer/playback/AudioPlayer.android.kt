@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.playback

import android.annotation.SuppressLint
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import android.util.LruCache
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.milkdev.dreamplayer.app.applicationContext
import org.milkdev.dreamplayer.diagnostics.AppDebugLog
import org.milkdev.dreamplayer.diagnostics.PlaybackTrace
import org.milkdev.dreamplayer.diagnostics.TraceCategory

actual object AudioPlayer {
    private const val TAG = "AudioPlayer"
    private val lock = Any()
    private val pendingActions = ArrayDeque<(MediaController) -> Unit>()
    private val _state = MutableStateFlow(AudioPlayerState())
    private val playerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val mediaItemCache = LruCache<MediaItemCacheKey, MediaItem>(367)

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playbackSnapshot: PlaybackSnapshot? = null

    private var controllerIndexToQueueIndex: List<Int> = emptyList()
    private var repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off
    private var nextSessionId: Int = 1
    private var currentSessionId: Int = 0

    actual val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    actual val playbackTimeSource: PlaybackTimeSource = object : PlaybackTimeSource {
        override fun snapshot(): PlaybackTimeSnapshot {
            val c = synchronized(lock) { controller }
            val s = _state.value
            val rawPosition = c?.currentPosition
            val positionMs = rawPosition?.coerceAtLeast(0L) ?: 0L
            val durationMs = s.totalDurationMs
            val bufferedPositionMs = c?.bufferedPosition?.coerceAtLeast(0L) ?: s.totalDurationMs
            val playbackSpeed = c?.playbackParameters?.speed ?: 1f
            val isPlaying = s.isPlaying
            val playbackState = c?.playbackState
            val playWhenReady = c?.playWhenReady

            return PlaybackTimeSnapshot(
                positionMs = positionMs,
                durationMs = durationMs,
                bufferedPositionMs = bufferedPositionMs,
                playbackSpeed = playbackSpeed,
                isPlaying = isPlaying,
            )

        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val mediaController = player as? MediaController
            if (mediaController != null) {
                syncCurrentTrackFromController(mediaController)
            }
            syncStateFromController(mediaController)

            val hash = mediaController?.let { System.identityHashCode(it) } ?: 0

            if (events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED)) {
                PlaybackTrace.event(
                    TraceCategory.Playback,
                    "PLAYBACK_STATE_CHANGED",
                    "playbackState=${player.playbackState} controllerHash=$hash"
                )
            }
            if (events.contains(Player.EVENT_PLAY_WHEN_READY_CHANGED)) {
                PlaybackTrace.event(
                    TraceCategory.Playback,
                    "PLAY_WHEN_READY_CHANGED",
                    "playWhenReady=${player.playWhenReady} controllerHash=$hash"
                )
            }
            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) {
                PlaybackTrace.event(
                    TraceCategory.Playback,
                    "IS_PLAYING_CHANGED",
                    "isPlaying=${player.isPlaying} controllerHash=$hash"
                )
            }
            if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) {
                PlaybackTrace.event(
                    TraceCategory.Playback,
                    "MEDIA_ITEM_TRANSITION",
                    "mediaItemIndex=${player.currentMediaItemIndex} controllerHash=$hash"
                )
            }
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                PlaybackTrace.event(
                    TraceCategory.Playback,
                    "TIMELINE_CHANGED",
                    "periodCount=${player.currentTimeline.periodCount} controllerHash=$hash"
                )
            }
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            val hash = synchronized(lock) { controller }
                ?.let { System.identityHashCode(it) }
                ?: 0
            val reasonLabel = when (reason) {
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> "AUTO_TRANSITION"
                Player.DISCONTINUITY_REASON_SEEK -> "SEEK"
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> "SEEK_ADJUSTMENT"
                Player.DISCONTINUITY_REASON_SKIP -> "SKIP"
                Player.DISCONTINUITY_REASON_REMOVE -> "REMOVE"
                Player.DISCONTINUITY_REASON_INTERNAL -> "INTERNAL"
                Player.DISCONTINUITY_REASON_SILENCE_SKIP -> "SILENCE_SKIP"
                else -> "UNKNOWN"
            }
            PlaybackTrace.event(
                TraceCategory.Playback,
                "EVENT_POSITION_DISCONTINUITY",
                "reason=$reasonLabel old=${oldPosition.positionMs} new=${newPosition.positionMs} controllerHash=$hash"
            )
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

    actual fun play(snapshot: PlaybackSnapshot, startPositionMs: Long) {
        currentSessionId = nextSessionId++
        val sessionId = currentSessionId
        setSnapshot(snapshot)
        withController { mediaController ->
            val hash = System.identityHashCode(mediaController)
            PlaybackTrace.event(
                TraceCategory.Playback,
                "PLAY",
                "sessionId=$sessionId startPositionMs=$startPositionMs controllerHash=$hash"
            )
            replaceControllerQueue(
                mediaController = mediaController,
                snapshot = snapshot,
                startPositionMs = startPositionMs,
                playWhenReady = true,
                reason = "play_snapshot",
            )
        }
    }

    private var updateJob: Job? = null

    actual fun updateQueue(snapshot: PlaybackSnapshot) {
        PlaybackTrace.event(
            TraceCategory.Playback,
            "UPDATE_QUEUE",
            "queueVersion=${snapshot.queue.queueVersion} itemCount=${snapshot.items.size}"
        )

        updateJob?.cancel()

        updateJob = playerScope.launch {
            val (availableIndexedItems, targetMediaItems) = withContext(Dispatchers.Default) {
                val available = snapshot.items.mapIndexed { index, item -> index to item }
                    .filter { (_, item) -> item.ref.availability == TrackAvailability.AVAILABLE && item.ref.uri.isNotBlank() }

                val mediaItems = available.map { (queueIndex, item) ->
                    item.toCachedMediaItem(queueIndex)
                }

                available to mediaItems
            }

            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext

                withController { mediaController ->
                    val currentPlaybackPositionMs = mediaController.currentPosition.coerceAtLeast(0L)
                    val shouldPlay = mediaController.playWhenReady
                    val previousTrackId = synchronized(lock) {
                        playbackSnapshot?.queue?.currentTrackId
                    } ?: mediaController.currentMediaItem?.mediaId?.substringAfter("_")?.toLongOrNull()

                    setSnapshot(snapshot)

                    if (
                        snapshot.queue.currentTrackId == previousTrackId &&
                        mediaController.tryApplyQueueUpdate(snapshot, availableIndexedItems, targetMediaItems)
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
        }
    }

    actual fun moveQueueItem(fromIndex: Int, toIndex: Int, snapshot: PlaybackSnapshot) {
        PlaybackTrace.event(
            TraceCategory.Playback,
            "MOVE_QUEUE",
            "fromIndex=$fromIndex toIndex=$toIndex"
        )

        updateJob?.cancel()

        updateJob = playerScope.launch {
            val (availableIndexedItems, targetMediaItems) = withContext(Dispatchers.Default) {
                val available = snapshot.items.mapIndexed { index, item -> index to item }
                    .filter { (_, item) -> item.ref.availability == TrackAvailability.AVAILABLE && item.ref.uri.isNotBlank() }

                val mediaItems = available.map { (queueIndex, item) ->
                    item.toCachedMediaItem(queueIndex)
                }

                available to mediaItems
            }

            withContext(Dispatchers.Main) {
                if (!isActive) return@withContext

                withController { mediaController ->
                    setSnapshot(snapshot)

                    if (mediaController.tryApplyQueueUpdate(snapshot, availableIndexedItems, targetMediaItems)) {
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
        }
    }

    actual fun pause() {
        withController { mediaController ->
            val hash = System.identityHashCode(mediaController)
            PlaybackTrace.event(
                TraceCategory.Playback,
                "PAUSE",
                "controllerHash=$hash"
            )
            mediaController.pause()
            syncStateFromController(mediaController)
        }
    }

    actual fun resume() {
        withController { mediaController ->
            val hash = System.identityHashCode(mediaController)
            PlaybackTrace.event(
                TraceCategory.Playback,
                "RESUME",
                "controllerHash=$hash"
            )
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
        withController { mediaController ->
            val hash = System.identityHashCode(mediaController)
            PlaybackTrace.event(
                TraceCategory.Playback,
                "STOP",
                "controllerHash=$hash"
            )
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

            val hash = System.identityHashCode(mediaController)
            PlaybackTrace.event(
                TraceCategory.Playback,
                "BEFORE_SEEK",
                "positionMs=$positionMs controllerHash=$hash"
            )
            mediaController.seekTo(positionMs.coerceAtLeast(0L))
            PlaybackTrace.event(
                TraceCategory.Playback,
                "AFTER_SEEK",
                "positionMs=$positionMs controllerHash=$hash"
            )
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
    val mainExecutor = java.util.concurrent.Executor { command ->
        android.os.Handler(android.os.Looper.getMainLooper()).post(command)
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

                var connectedController: MediaController? = null
                synchronized(lock) {
                    if (controller != null && controller !== resolvedController) {
                        releaseResolvedController = true
                    } else {
                        controller = resolvedController
                        connectedController = resolvedController
                        queuedActions = pendingActions.toList()
                        pendingActions.clear()
                    }

                    if (controllerFuture === future) {
                        controllerFuture = null
                    }
                }

                if (connectedController != null) {
                    val connectHash = System.identityHashCode(connectedController)
                    PlaybackTrace.event(
                        TraceCategory.Playback,
                        "CONTROLLER_CONNECT",
                        "controllerHash=$connectHash"
                    )
                }

                if (releaseResolvedController) {
                    val releasedHash = System.identityHashCode(resolvedController)
                    PlaybackTrace.event(
                        TraceCategory.Playback,
                        "CONTROLLER_RELEASE",
                        "controllerHash=$releasedHash"
                    )
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
            mainExecutor
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

        mediaController.repeatMode = synchronized(lock) {
            repeatMode.toMedia3RepeatMode()
        }

        playerScope.launch {
            val mediaItems = withContext(Dispatchers.Default) {
                availableIndexedItems.map { (queueIndex, item) -> item.toCachedMediaItem(queueIndex) }
            }
            val hash = System.identityHashCode(mediaController)
            PlaybackTrace.event(
                TraceCategory.Playback,
                "SET_MEDIA_ITEMS",
                "reason=$reason startIndex=$startIndex startPosition=${startPositionMs.coerceAtLeast(0L)} itemCount=${mediaItems.size} controllerHash=$hash"
            )
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
            var item = mediaItemCache.get(key)
            if (item == null) {
                item = toMediaItem(queueIndex)
                mediaItemCache.put(key, item)
            }
            item
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

    private fun MediaController.tryApplyQueueUpdate(
        snapshot: PlaybackSnapshot,
        availableIndexedItems: List<Pair<Int, ResolvedPlaybackItem>>,
        targetMediaItems: List<MediaItem>
    ): Boolean {
        val mediaController = this
        val currentMediaItem = currentMediaItem ?: return false

        val currentTrackId = currentMediaItem.mediaId.substringAfter("_").toLongOrNull() ?: return false
        if (currentTrackId != snapshot.queue.currentTrackId) return false

        if (availableIndexedItems.size != mediaItemCount) return false

        val targetCurrentIndex = availableIndexedItems.indexOfFirst { (queueIndex, item) ->
            queueIndex == snapshot.queue.currentIndex && item.trackId == currentTrackId
        }.takeIf { it >= 0 } ?: availableIndexedItems.indexOfFirst { it.second.trackId == currentTrackId }

        if (targetCurrentIndex !in availableIndexedItems.indices) return false

        val currentControllerIndex = currentMediaItemIndex
        if (currentControllerIndex !in 0 until mediaItemCount) return false

        if (currentControllerIndex != targetCurrentIndex) {
            moveMediaItem(currentControllerIndex, targetCurrentIndex)
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

        AppDebugLog.log("audio_update_snapshot_in_place trackId=$currentTrackId queueSize=${snapshot.queue.trackIds.size}")
        return true
    }

    private fun MediaController.ensureUpcomingItem() {
        val snapshot = synchronized(lock) { playbackSnapshot } ?: return
        val currentTrackId = currentMediaItem?.mediaId?.substringAfter("_")?.toLongOrNull()
            ?: snapshot.queue.currentTrackId
            ?: return

        val availableIndexedItems = snapshot.items.mapIndexed { index, item -> index to item }
            .filter { (_, item) -> item.ref.availability == TrackAvailability.AVAILABLE && item.ref.uri.isNotBlank() }

        ensureUpcomingItem(availableIndexedItems, currentTrackId)
    }

    private fun MediaController.ensureUpcomingItem(
        targetItems: List<Pair<Int, ResolvedPlaybackItem>>,
        currentTrackId: Long,
    ) {
        if (targetItems.size <= 1 || mediaItemCount <= 1) return

        val targetCurrentIndex = targetItems.indexOfFirst { (_, item) -> item.trackId == currentTrackId }
        if (targetCurrentIndex !in targetItems.indices) return

        val playbackRepeatMode = synchronized(lock) { this@AudioPlayer.repeatMode }
        val nextTargetIndex = when {
            targetCurrentIndex < targetItems.lastIndex -> targetCurrentIndex + 1
            playbackRepeatMode == PlaybackRepeatMode.Queue -> 0
            else -> -1
        }
        if (nextTargetIndex == -1) return

        val nextTargetItem = targetItems[nextTargetIndex].second

        val currentControllerIndex = currentMediaItemIndex
        if (currentControllerIndex !in 0 until mediaItemCount) return

        val nextControllerIndex = when {
            currentControllerIndex < mediaItemCount - 1 -> currentControllerIndex + 1
            playbackRepeatMode == PlaybackRepeatMode.Queue -> 0
            else -> return
        }

        val nextControllerItem = getMediaItemAt(nextControllerIndex) // Это единственный разрешенный вызов!
        if (nextControllerItem.mediaId.substringAfter("_").toLongOrNull() == nextTargetItem.trackId) return

        if (nextTargetIndex == currentControllerIndex) return

        moveMediaItem(nextTargetIndex, nextControllerIndex)
    }

    private fun syncStateFromController(mediaController: MediaController? = synchronized(lock) { controller }) {
        synchronized(lock) {
            val snapshot = playbackSnapshot
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
                isPlaying = mediaController?.let { c ->
                    c.playWhenReady && c.playbackState != Player.STATE_ENDED
                } == true,
                totalDurationMs = resolvedDurationMs,
                queue = queue.copy(trackIds = queue.trackIds.copyOf()),
            )
        }
    }
}

private data class MediaItemCacheKey(
    val trackId: Long,
    val contentVersion: Long,
    val queueIndex: Int
)
private const val EXTRA_TRACK_DURATION_MS = "dreamplayer.track.DURATION_MS"

@SuppressLint("UseKtx")
private fun ResolvedPlaybackItem.toMediaItem(queueIndex: Int): MediaItem {
    val metadataExtras = Bundle().apply {
        putLong(EXTRA_TRACK_DURATION_MS, metadata.durationMs)
    }

    return MediaItem.Builder()
        .setMediaId("${queueIndex}_${trackId}")
        .setUri(android.net.Uri.parse(ref.uri))
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(metadata.title)
                .setArtist(metadata.artistName)
                .setAlbumTitle(metadata.albumName)
                .setArtworkUri(metadata.albumArtUri?.let { android.net.Uri.parse(it) })
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