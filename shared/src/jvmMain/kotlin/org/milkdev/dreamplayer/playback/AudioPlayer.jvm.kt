@file:Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")

package org.milkdev.dreamplayer.playback

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.milkdev.dreamplayer.audio.openFlacAudioInputStream
import org.milkdev.dreamplayer.audio.openMpegAudioInputStream
import org.milkdev.dreamplayer.diagnostics.PlaybackTrace
import org.milkdev.dreamplayer.diagnostics.TraceCategory
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.util.concurrent.locks.ReentrantLock
import java.util.logging.Level
import java.util.logging.Logger
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.withLock
import kotlin.math.max

actual object AudioPlayer {
    private val logger = Logger.getLogger(AudioPlayer::class.java.name)
    private val lock = Any()
    private val _state = MutableStateFlow(AudioPlayerState())
    private var playbackSession: PlaybackSession? = null
    private var playbackSnapshot: PlaybackSnapshot? = null
    private var queue: List<ResolvedPlaybackItem> = emptyList()
    private var currentIndex: Int = -1
    private var repeatMode: PlaybackRepeatMode = PlaybackRepeatMode.Off
    private var nextSessionId: Int = 1
    private var currentSessionId: Int = 0

    actual val state: StateFlow<AudioPlayerState> = _state.asStateFlow()

    actual val playbackTimeSource: PlaybackTimeSource = object : PlaybackTimeSource {
        override fun snapshot(): PlaybackTimeSnapshot {
            return synchronized(lock) {
                val s = _state.value
                val positionMs = playbackSession?.getCurrentPosition()
                    ?: if (s.currentTrackId != null && !s.isPlaying) s.totalDurationMs else 0L
                PlaybackTimeSnapshot(
                    positionMs = positionMs,
                    durationMs = s.totalDurationMs,
                    bufferedPositionMs = s.totalDurationMs,
                    playbackSpeed = 1f,
                    isPlaying = s.isPlaying,
                )
            }
        }
    }

    actual fun play(snapshot: PlaybackSnapshot, startPositionMs: Long) {
        if (snapshot.items.isEmpty()) {
            stop()
            return
        }

        currentSessionId = nextSessionId++
        val sessionId = currentSessionId
        PlaybackTrace.event(
            TraceCategory.Playback,
            "PLAY",
            "sessionId=$sessionId startPositionMs=$startPositionMs"
        )

        synchronized(lock) {
            setSnapshotLocked(snapshot)
            playCurrentTrackLocked(startPositionMs)
        }
    }

    actual fun updateQueue(snapshot: PlaybackSnapshot) {
        PlaybackTrace.event(
            TraceCategory.Playback,
            "UPDATE_QUEUE",
            "queueVersion=${snapshot.queue.queueVersion} itemCount=${snapshot.items.size}"
        )

        synchronized(lock) {
            if (snapshot.items.isEmpty()) {
                stopLocked()
                return
            }

            val previousTrackId = _state.value.currentTrackId
            setSnapshotLocked(snapshot, preferredTrackId = previousTrackId)
            val currentItem = queue.getOrNull(currentIndex)
            _state.value = _state.value.copy(
                currentTrackId = currentItem?.trackId,
                totalDurationMs = currentItem?.metadata?.durationMs ?: 0L,
                queue = playbackSnapshot?.queue ?: EmptyPlaybackQueueSnapshot,
            )
        }
    }

    actual fun moveQueueItem(fromIndex: Int, toIndex: Int, snapshot: PlaybackSnapshot) {
        PlaybackTrace.event(
            TraceCategory.Playback,
            "MOVE_QUEUE",
            "fromIndex=$fromIndex toIndex=$toIndex"
        )
        updateQueue(snapshot)
    }

    actual fun pause() {
        PlaybackTrace.event(
            TraceCategory.Playback,
            "PAUSE",
            ""
        )
        synchronized(lock) {
            playbackSession?.pause()
            _state.value = _state.value.copy(isPlaying = false)
        }
    }

    actual fun resume() {
        PlaybackTrace.event(
            TraceCategory.Playback,
            "RESUME",
            ""
        )
        synchronized(lock) {
            val session = playbackSession
            if (session != null) {
                session.resume()
                _state.value = _state.value.copy(isPlaying = true)
                return
            }

            if (currentIndex in queue.indices) {
                playCurrentTrackLocked()
            }
        }
    }

    actual fun stop() {
        PlaybackTrace.event(
            TraceCategory.Playback,
            "STOP",
            ""
        )
        synchronized(lock) {
            stopLocked()
        }
    }

    actual fun seekTo(positionMs: Long) {
        PlaybackTrace.event(
            TraceCategory.Playback,
            "BEFORE_SEEK",
            "positionMs=$positionMs"
        )
        synchronized(lock) {
            playbackSession?.seekTo(positionMs)
        }
        PlaybackTrace.event(
            TraceCategory.Playback,
            "AFTER_SEEK",
            "positionMs=$positionMs"
        )
    }

    actual fun setRepeatMode(mode: PlaybackRepeatMode) {
        synchronized(lock) {
            repeatMode = mode
        }
    }

    private fun playCurrentTrackLocked(startPositionMs: Long = 0L) {
        val item = queue.getOrNull(currentIndex) ?: return
        PlaybackTrace.event(
            TraceCategory.Playback,
            "SET_MEDIA_ITEMS",
            "reason=play_track startIndex=$currentIndex startPosition=$startPositionMs itemCount=1"
        )
        if (item.ref.availability != TrackAvailability.AVAILABLE || item.ref.uri.isBlank()) return

        val mediaFile = runCatching { item.ref.uri.toMediaFile() }
            .onFailure { error ->
                logger.log(Level.WARNING, "Unable to resolve media source for ${item.metadata.title}", error)
            }
            .getOrNull() ?: return

        val nextSession = PlaybackSession(
            mediaFile = mediaFile,
            durationMs = item.metadata.durationMs,
            title = item.metadata.title,
            logger = logger,
            onPlaybackCompleted = ::handlePlaybackCompleted,
            onPlaybackError = ::handlePlaybackError,
        )
        playbackSession?.close()
        playbackSession = nextSession
        if (startPositionMs > 0L) {
            nextSession.seekTo(startPositionMs)
        }
        val queueSnapshot = playbackSnapshot?.queue
            ?.copy(
                currentIndex = currentIndex,
                currentTrackId = item.trackId,
                trackIds = playbackSnapshot?.queue?.trackIds?.copyOf() ?: LongArray(0),
            )
            ?: EmptyPlaybackQueueSnapshot
        playbackSnapshot = playbackSnapshot?.copy(queue = queueSnapshot)
        _state.value = AudioPlayerState(
            currentTrackId = item.trackId,
            isPlaying = true,
            totalDurationMs = item.metadata.durationMs,
            queue = queueSnapshot,
        )
        nextSession.start()
    }

    private fun handlePlaybackCompleted(completedSession: PlaybackSession) {
        synchronized(lock) {
            if (playbackSession !== completedSession) return@synchronized
            if (queue.isEmpty()) {
                playbackSession = null
                _state.value = _state.value.copy(isPlaying = false)
                return@synchronized
            }

            when (repeatMode) {
                PlaybackRepeatMode.One -> {
                    if (currentIndex !in queue.indices) {
                        currentIndex = 0
                    }
                    playCurrentTrackLocked()
                }
                PlaybackRepeatMode.Queue -> {
                    currentIndex = if (currentIndex in 0 until queue.lastIndex) {
                        currentIndex + 1
                    } else {
                        0
                    }
                    playCurrentTrackLocked()
                }
                PlaybackRepeatMode.Off -> {
                    if (currentIndex in 0 until queue.lastIndex) {
                        currentIndex += 1
                        playCurrentTrackLocked()
                    } else {
                        playbackSession = null
                        _state.value = _state.value.copy(isPlaying = false)
                    }
                }
            }
        }
    }

    private fun handlePlaybackError(failedSession: PlaybackSession, error: Exception) {
        synchronized(lock) {
            if (playbackSession !== failedSession) return@synchronized

            playbackSession = null
            _state.value = _state.value.copy(isPlaying = false)

            logger.log(Level.SEVERE, "Playback session failed, player stopped: ${error.message}", error)
        }
    }

    private fun stopLocked() {
        playbackSession?.close()
        playbackSession = null
        playbackSnapshot = null
        queue = emptyList()
        currentIndex = -1
        _state.value = AudioPlayerState()
    }

    private fun setSnapshotLocked(
        snapshot: PlaybackSnapshot,
        preferredTrackId: Long? = snapshot.queue.currentTrackId,
    ) {
        playbackSnapshot = snapshot.copy(
            queue = snapshot.queue.copy(trackIds = snapshot.queue.trackIds.copyOf()),
            items = snapshot.items.toList(),
        )
        queue = snapshot.items
        currentIndex = preferredTrackId
            ?.let { trackId -> queue.indexOfFirst { it.trackId == trackId } }
            ?.takeIf { it >= 0 }
            ?: snapshot.queue.currentIndex.takeIf { it in queue.indices }
                ?: if (queue.isEmpty()) -1 else 0
    }

    private fun String.toMediaFile(): File {
        val uri = runCatching { URI(this) }.getOrNull()
        val mediaFile = if (uri != null && uri.scheme != null && !hasWindowsDriveScheme(uri.scheme)) {
            require(uri.scheme.equals("file", ignoreCase = true)) {
                "Only local file media is supported on JVM: $this"
            }
            File(uri)
        } else {
            File(this)
        }

        require(mediaFile.isFile) {
            "Media file does not exist: $this"
        }
        return mediaFile
    }

    private fun String.hasWindowsDriveScheme(scheme: String): Boolean {
        return scheme.length == 1 && length > 1 && this[1] == ':'
    }

}

private class PlaybackSession(
    private val mediaFile: File,
    private val durationMs: Long,
    private val title: String,
    private val logger: Logger,
    private val onPlaybackCompleted: (PlaybackSession) -> Unit,
    private val onPlaybackError: (PlaybackSession, Exception) -> Unit,
) : Runnable {
    private val controlLock = ReentrantLock()
    private val playbackChanged = controlLock.newCondition()
    private val thread = Thread(this, "DreamPlayer-AudioPlayer")

    @Volatile
    private var closed = false

    @Volatile
    private var paused = false

    @Volatile
    private var seekRequestMs: Long? = null

    @Volatile
    private var outputLine: SourceDataLine? = null

    @Volatile
    private var playbackCursor: PlaybackCursor? = null

    @Volatile
    private var lastKnownPositionMs: Long = 0L

    @Volatile
    private var seekingPositionMs: Long? = null

    fun start() {
        thread.isDaemon = true
        thread.priority = PLAYBACK_THREAD_PRIORITY
        thread.start()
    }

    fun pause() {
        controlLock.withLock {
            paused = true
            playbackChanged.signalAll()
        }
        stopOutputLine(dropQueuedAudio = false)
    }

    fun resume() {
        controlLock.withLock {
            paused = false
            playbackChanged.signalAll()
        }
    }

    fun seekTo(positionMs: Long) {
        val sanitizedPositionMs = if (durationMs > 0L) {
            positionMs.coerceIn(0L, durationMs)
        } else {
            positionMs.coerceAtLeast(0L)
        }
        controlLock.withLock {
            seekRequestMs = sanitizedPositionMs
            seekingPositionMs = sanitizedPositionMs
            lastKnownPositionMs = sanitizedPositionMs
            playbackChanged.signalAll()
        }
        stopOutputLine(dropQueuedAudio = true)
    }

    fun close() {
        controlLock.withLock {
            closed = true
            paused = false
            playbackChanged.signalAll()
        }

        closeLine()
    }

    override fun run() {
        var startPositionMs = 0L

        try {
            while (!closed) {
                pollSeekRequest()?.let { requestedSeekMs ->
                    startPositionMs = requestedSeekMs
                    flushLine()
                }

                val decodedAudio = openDecodedAudioStream(startPositionMs) ?: continue
                val requestedSeekMs = decodedAudio.use { playDecodedAudio(it) }
                if (requestedSeekMs == null) {
                    if (!closed) {
                        onPlaybackCompleted(this)
                    }
                    break
                } else {
                    startPositionMs = requestedSeekMs
                    flushLine()
                }
            }
        } catch (error: Exception) {
            if (!closed) {
                logger.log(Level.WARNING, "Unable to play $title", error)
                onPlaybackError(this, error)
            }
            closed = true
        } finally {
            closeLine()
        }
    }

    private fun playDecodedAudio(decodedAudio: DecodedAudio): Long? {
        if (closed) return null

        val format = decodedAudio.format
        val line = getOrOpenOutputLine(format)
        val cursor = PlaybackCursor(
            line = line,
            format = format,
            basePositionMs = decodedAudio.startPositionMs,
            lineStartFramePosition = line.longFramePosition
        )
        controlLock.withLock {
            playbackCursor = cursor
            if (seekRequestMs == null) {
                seekingPositionMs = null
                lastKnownPositionMs = decodedAudio.startPositionMs
            }
        }

        val buffer = ByteArray(audioBufferSize(format))
        var endedBySeek = false

        try {
            while (!closed) {
                pollSeekRequest()?.let {
                    endedBySeek = true
                    return it
                }
                if (!waitUntilPlayable(line)) return null
                pollSeekRequest()?.let {
                    endedBySeek = true
                    return it
                }

                val bytesRead = decodedAudio.stream.read(buffer)
                if (bytesRead < 0) {
                    val requestedSeekMs = drainBufferedAudio(line)
                    if (requestedSeekMs != null) {
                        endedBySeek = true
                        return requestedSeekMs
                    }
                    updateLastKnownPosition(cursor)
                    return null
                }

                var offset = 0
                while (offset < bytesRead && !closed) {
                    pollSeekRequest()?.let {
                        endedBySeek = true
                        return it
                    }
                    if (!waitUntilPlayable(line)) return null
                    pollSeekRequest()?.let {
                        endedBySeek = true
                        return it
                    }

                    val bytesToWrite = writableChunkSize(line, format, bytesRead - offset)
                    if (bytesToWrite == 0) {
                        awaitOutputProgress()
                        continue
                    }

                    val bytesWritten = line.write(buffer, offset, bytesToWrite)
                    if (bytesWritten == 0) {
                        awaitOutputProgress()
                        continue
                    }
                    offset += bytesWritten
                }
            }
        } finally {
            if (playbackCursor === cursor) {
                if (!endedBySeek) {
                    updateLastKnownPosition(cursor)
                }
                playbackCursor = null
            }
        }

        return null
    }

    private fun openDecodedAudioStream(positionMs: Long): DecodedAudio? {
        val sourceStream = openSourceAudioStream()
        try {
            val sourceFormat = sourceStream.format
            val decodedFormat = sourceFormat.toDecodedPcmFormat()
            val decodedStream = sourceStream.convertTo(decodedFormat)
            val outputFormat = decodedFormat.toPlaybackPcmFormat()
            val outputStream = decodedStream.convertTo(outputFormat)

            try {
                if (!discardDecodedAudioUntilPosition(outputStream, outputFormat, positionMs)) {
                    closeAudioStreams(outputStream, sourceStream)
                    return null
                }

                return DecodedAudio(
                    stream = outputStream,
                    sourceStream = sourceStream,
                    format = outputFormat,
                    startPositionMs = positionMs.coerceAtLeast(0L)
                )
            } catch (error: Exception) {
                closeAudioStreams(outputStream, sourceStream)
                throw error
            }
        } catch (error: Exception) {
            runCatching { sourceStream.close() }
            throw error
        }
    }

    private fun openSourceAudioStream(): AudioInputStream {
        if (mediaFile.extension.equals("flac", ignoreCase = true)) {
            return mediaFile.openFlacAudioInputStream()
        }

        if (!mediaFile.extension.equals("mp3", ignoreCase = true)) {
            return AudioSystem.getAudioInputStream(mediaFile)
        }

        val inputStream = BufferedInputStream(mediaFile.inputStream(), MP3_INPUT_BUFFER_BYTES)
        return try {
            inputStream.skipLeadingId3v2Tags(mediaFile.length())
            inputStream.openMpegAudioInputStream()
        } catch (error: Exception) {
            runCatching { inputStream.close() }
            throw error
        }
    }

    private fun BufferedInputStream.skipLeadingId3v2Tags(fileSize: Long) {
        var remainingFileBytes = fileSize

        while (true) {
            mark(ID3V2_HEADER_SIZE)
            val header = ByteArray(ID3V2_HEADER_SIZE)
            if (!readFullyOrEof(header) || !header.hasId3v2Signature()) {
                reset()
                return
            }
            remainingFileBytes -= ID3V2_HEADER_SIZE

            val payloadSize = header.id3v2PayloadSize()
                ?: throw IOException("Invalid ID3v2 tag size in ${mediaFile.name}")
            val footerSize = if (
                header[ID3V2_MAJOR_VERSION_OFFSET].unsigned() == ID3V2_FOOTER_VERSION &&
                header[ID3V2_FLAGS_OFFSET].unsigned() and ID3V2_FOOTER_FLAG != 0
            ) {
                ID3V2_HEADER_SIZE
            } else {
                0
            }
            val remainingTagBytes = payloadSize + footerSize
            if (remainingTagBytes > remainingFileBytes) {
                throw IOException("ID3v2 tag exceeds file size in ${mediaFile.name}")
            }

            skipFully(remainingTagBytes)
            remainingFileBytes -= remainingTagBytes
        }
    }

    private fun InputStream.readFullyOrEof(bytes: ByteArray): Boolean {
        var offset = 0
        while (offset < bytes.size) {
            val bytesRead = read(bytes, offset, bytes.size - offset)
            if (bytesRead < 0) return false
            if (bytesRead > 0) {
                offset += bytesRead
            } else {
                val nextByte = read()
                if (nextByte < 0) return false
                bytes[offset++] = nextByte.toByte()
            }
        }
        return true
    }

    private fun InputStream.skipFully(byteCount: Long) {
        var remainingBytes = byteCount
        while (remainingBytes > 0L) {
            val skippedBytes = skip(remainingBytes)
            if (skippedBytes > 0L) {
                remainingBytes -= skippedBytes
            } else if (read() < 0) {
                throw EOFException("Reached EOF while skipping ID3v2 tag")
            } else {
                remainingBytes--
            }
        }
    }

    private fun ByteArray.hasId3v2Signature(): Boolean {
        return size >= ID3V2_HEADER_SIZE &&
            this[0] == 'I'.code.toByte() &&
            this[1] == 'D'.code.toByte() &&
            this[2] == '3'.code.toByte()
    }

    private fun ByteArray.id3v2PayloadSize(): Long? {
        var size = 0L
        for (index in ID3V2_SIZE_OFFSET until ID3V2_SIZE_OFFSET + ID3V2_SIZE_BYTES) {
            val value = this[index].unsigned()
            if (value and ID3V2_INVALID_SYNCHSAFE_BIT != 0) return null
            size = (size shl ID3V2_BITS_PER_BYTE) or value.toLong()
        }
        return size
    }

    private fun Byte.unsigned(): Int = toInt() and UNSIGNED_BYTE_MASK

    fun getCurrentPosition(): Long {
        val requestedPositionMs = seekingPositionMs
        if (requestedPositionMs != null) {
            return requestedPositionMs
        }

        val pendingSeekMs = seekRequestMs
        if (pendingSeekMs != null) {
            return pendingSeekMs
        }

        val cursor = playbackCursor ?: return lastKnownPositionMs
        val currentPositionMs = runCatching {
            val playedFrames = cursor.line.longFramePosition - cursor.lineStartFramePosition
            cursor.basePositionMs + framesToMillis(playedFrames, cursor.format)
        }.getOrDefault(lastKnownPositionMs)

        lastKnownPositionMs = currentPositionMs.coerceAtLeast(cursor.basePositionMs)
        return lastKnownPositionMs
    }

    private fun getOrOpenOutputLine(format: AudioFormat): SourceDataLine {
        val currentLine = outputLine
        if (currentLine != null && currentLine.isOpen && currentLine.format.isSamePcmOutput(format)) {
            return currentLine
        }

        closeLine()
        val nextLine = openOutputLine(format)
        return controlLock.withLock {
            if (closed) {
                runCatching { nextLine.close() }
                error("Playback session is closed")
            }

            outputLine = nextLine
            nextLine
        }
    }

    private fun openOutputLine(format: AudioFormat): SourceDataLine {
        val line = AudioSystem.getSourceDataLine(format)
        return try {
            line.open(format, lineBufferSize(format))
            line
        } catch (error: Exception) {
            runCatching { line.close() }
            throw error
        }
    }

    private fun waitUntilPlayable(line: SourceDataLine): Boolean {
        controlLock.withLock {
            while (paused && !closed && seekRequestMs == null) {
                if (line.isRunning) {
                    line.stop()
                }
                playbackChanged.await()
            }

            if (!closed && seekRequestMs == null && line.isOpen && !line.isRunning) {
                line.start()
            }

            return !closed && line.isOpen
        }
    }

    private fun awaitOutputProgress() {
        controlLock.withLock {
            if (!closed && !paused && seekRequestMs == null) {
                playbackChanged.awaitNanos(OUTPUT_POLL_INTERVAL_NANOS)
            }
        }
    }

    private fun drainBufferedAudio(line: SourceDataLine): Long? {
        while (!closed) {
            pollSeekRequest()?.let { return it }
            if (!waitUntilPlayable(line)) return null
            pollSeekRequest()?.let { return it }
            if (line.available() >= line.bufferSize) return null
            awaitOutputProgress()
        }

        return null
    }

    private fun writableChunkSize(line: SourceDataLine, format: AudioFormat, remainingBytes: Int): Int {
        val frameSize = max(1, format.frameSize)
        return minOf(
            remainingBytes,
            max(WRITE_CHUNK_BYTES, frameSize),
            line.available().coerceAtLeast(0)
        ).alignToFrameSize(frameSize)
    }

    private fun pollSeekRequest(): Long? {
        controlLock.withLock {
            return pollSeekRequestLocked()
        }
    }

    private fun pollSeekRequestLocked(): Long? {
        val requestedPositionMs = seekRequestMs
        seekRequestMs = null
        if (requestedPositionMs != null) {
            lastKnownPositionMs = requestedPositionMs
        }
        return requestedPositionMs
    }

    private fun flushLine() {
        stopOutputLine(dropQueuedAudio = true)
    }

    private fun closeLine() {
        val line = controlLock.withLock {
            outputLine.also { outputLine = null }
        }

        line?.apply {
            runCatching {
                stop()
                flush()
                close()
            }
        }
    }

    private fun stopOutputLine(dropQueuedAudio: Boolean) {
        val line = outputLine ?: return
        runCatching {
            if (line.isOpen) {
                line.stop()
                if (dropQueuedAudio) {
                    line.flush()
                }
            }
        }
    }

    private fun discardDecodedAudioUntilPosition(
        stream: AudioInputStream,
        format: AudioFormat,
        positionMs: Long
    ): Boolean {
        var remainingBytes = millisToAudioBytes(positionMs, format)
        val frameSize = max(1, format.frameSize)
        val scratchBuffer = ByteArray(
            max(SEEK_BUFFER_BYTES, frameSize).alignToFrameSize(frameSize)
        )

        // MP3SPI overrides skip() with compressed-byte semantics. Reading decoded
        // PCM frames is the portable way to reach the requested playback position.
        while (remainingBytes > 0L && !closed && seekRequestMs == null) {
            val bytesToRead = minOf(scratchBuffer.size.toLong(), remainingBytes).toInt()
            val bytesRead = stream.read(scratchBuffer, 0, bytesToRead)
            if (bytesRead < 0) return true
            remainingBytes -= bytesRead
        }

        return !closed && seekRequestMs == null
    }

    private fun AudioInputStream.convertTo(targetFormat: AudioFormat): AudioInputStream {
        return if (format.isSamePcmOutput(targetFormat)) {
            this
        } else {
            AudioSystem.getAudioInputStream(targetFormat, this)
        }
    }

    private fun AudioFormat.toDecodedPcmFormat(): AudioFormat {
        val channelCount = max(1, channels)
        val effectiveSampleRate = if (sampleRate > 0f) sampleRate else DEFAULT_SAMPLE_RATE
        val effectiveSampleSizeBits = sampleSizeInBits.takeIf { it > 0 } ?: DEFAULT_SAMPLE_SIZE_BITS
        val bytesPerSample = (effectiveSampleSizeBits + BITS_PER_BYTE - 1) / BITS_PER_BYTE

        return AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            effectiveSampleRate,
            effectiveSampleSizeBits,
            channelCount,
            channelCount * bytesPerSample,
            effectiveSampleRate,
            false
        )
    }

    private fun AudioFormat.toPlaybackPcmFormat(): AudioFormat {
        val channelCount = max(1, channels)
        val effectiveSampleRate = if (sampleRate > 0f) sampleRate else DEFAULT_SAMPLE_RATE

        return AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            effectiveSampleRate,
            PLAYBACK_SAMPLE_SIZE_BITS,
            channelCount,
            channelCount * PLAYBACK_BYTES_PER_SAMPLE,
            effectiveSampleRate,
            false
        )
    }

    private fun AudioFormat.isSamePcmOutput(outputFormat: AudioFormat): Boolean {
        return encoding == outputFormat.encoding &&
                sampleRate == outputFormat.sampleRate &&
                sampleSizeInBits == outputFormat.sampleSizeInBits &&
                channels == outputFormat.channels &&
                frameSize == outputFormat.frameSize &&
                frameRate == outputFormat.frameRate &&
                isBigEndian == outputFormat.isBigEndian
    }

    private fun lineBufferSize(format: AudioFormat): Int {
        val frameSize = max(1, format.frameSize)
        val bytesPerSecond = max(frameSize, (format.frameRate * frameSize).toInt())
        val targetBytes = (bytesPerSecond * LINE_BUFFER_MILLIS / MILLIS_PER_SECOND)
            .coerceAtLeast(frameSize * MIN_BUFFER_FRAMES)

        return targetBytes.alignToFrameSize(frameSize)
    }

    private fun audioBufferSize(format: AudioFormat): Int {
        val frameSize = max(1, format.frameSize)
        return lineBufferSize(format)
            .coerceAtMost(MAX_READ_BUFFER_BYTES)
            .coerceAtLeast(frameSize * MIN_BUFFER_FRAMES)
            .alignToFrameSize(frameSize)
    }

    private fun millisToAudioBytes(positionMs: Long, format: AudioFormat): Long {
        val frameSize = max(1, format.frameSize)
        val frameRate = format.frameRate.takeIf { it > 0f } ?: DEFAULT_SAMPLE_RATE
        val frames = (positionMs.coerceAtLeast(0L) * frameRate / MILLIS_PER_SECOND).toLong()
        return frames * frameSize
    }

    private fun framesToMillis(framePosition: Long, format: AudioFormat): Long {
        val frameRate = format.frameRate.takeIf { it > 0f } ?: DEFAULT_SAMPLE_RATE
        return (framePosition.coerceAtLeast(0L) * MILLIS_PER_SECOND / frameRate).toLong()
    }

    private fun updateLastKnownPosition(cursor: PlaybackCursor) {
        val currentPositionMs = runCatching {
            val playedFrames = cursor.line.longFramePosition - cursor.lineStartFramePosition
            cursor.basePositionMs + framesToMillis(playedFrames, cursor.format)
        }.getOrDefault(lastKnownPositionMs)

        lastKnownPositionMs = currentPositionMs.coerceAtLeast(0L)
    }

    private fun Int.alignToFrameSize(frameSize: Int): Int {
        return this - (this % frameSize)
    }

    private data class DecodedAudio(
        val stream: AudioInputStream,
        val sourceStream: AudioInputStream,
        val format: AudioFormat,
        val startPositionMs: Long
    ) : AutoCloseable {
        override fun close() {
            closeAudioStreams(stream, sourceStream)
        }
    }

    private data class PlaybackCursor(
        val line: SourceDataLine,
        val format: AudioFormat,
        val basePositionMs: Long,
        val lineStartFramePosition: Long
    )

    private companion object {
        const val DEFAULT_SAMPLE_RATE = 44100f
        const val DEFAULT_SAMPLE_SIZE_BITS = 16
        const val PLAYBACK_SAMPLE_SIZE_BITS = 16
        const val PLAYBACK_BYTES_PER_SAMPLE = 2
        const val BITS_PER_BYTE = 8
        const val MILLIS_PER_SECOND = 1000
        const val LINE_BUFFER_MILLIS = 250
        const val MIN_BUFFER_FRAMES = 64
        const val MAX_READ_BUFFER_BYTES = 8192
        const val SEEK_BUFFER_BYTES = 8192
        const val MP3_INPUT_BUFFER_BYTES = 8192
        const val WRITE_CHUNK_BYTES = 2048
        const val OUTPUT_POLL_INTERVAL_NANOS = 5_000_000L
        const val ID3V2_HEADER_SIZE = 10
        const val ID3V2_MAJOR_VERSION_OFFSET = 3
        const val ID3V2_FLAGS_OFFSET = 5
        const val ID3V2_SIZE_OFFSET = 6
        const val ID3V2_SIZE_BYTES = 4
        const val ID3V2_BITS_PER_BYTE = 7
        const val ID3V2_INVALID_SYNCHSAFE_BIT = 0x80
        const val ID3V2_FOOTER_VERSION = 4
        const val ID3V2_FOOTER_FLAG = 0x10
        const val UNSIGNED_BYTE_MASK = 0xFF
        val PLAYBACK_THREAD_PRIORITY = (Thread.NORM_PRIORITY + 1).coerceAtMost(Thread.MAX_PRIORITY)
    }
}

private fun closeAudioStreams(decodedStream: AudioInputStream, sourceStream: AudioInputStream) {
    runCatching { decodedStream.close() }
    if (sourceStream !== decodedStream) {
        runCatching { sourceStream.close() }
    }
}
