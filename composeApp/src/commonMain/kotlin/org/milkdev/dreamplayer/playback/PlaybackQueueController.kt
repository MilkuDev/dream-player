package org.milkdev.dreamplayer.playback

import kotlin.random.Random

class PlaybackQueueController {
    private var originalIds: LongArray = LongArray(0)
    private var shuffledIds: LongArray? = null
    private var currentIndex: Int = -1
    private var queueVersion: Long = 0L

    val isShuffleEnabled: Boolean
        get() = shuffledIds != null

    val currentTrackId: Long?
        get() = activeIds().getOrNull(currentIndex)

    fun snapshot(): PlaybackQueueSnapshot {
        val activeIds = activeIds()
        return PlaybackQueueSnapshot(
            queueVersion = queueVersion,
            trackIds = activeIds.copyOf(),
            currentIndex = currentIndex.takeIf { it in activeIds.indices } ?: -1,
            currentTrackId = activeIds.getOrNull(currentIndex),
        )
    }

    fun originalTrackIdsSnapshot(): LongArray = originalIds.copyOf()

    fun setQueue(trackIds: LongArray, startIndex: Int = 0): PlaybackQueueSnapshot {
        originalIds = trackIds.copyOf()
        shuffledIds = null
        currentIndex = if (originalIds.isEmpty()) -1 else startIndex.coerceIn(0, originalIds.lastIndex)
        bumpVersion()
        return snapshot()
    }

    fun clear(): PlaybackQueueSnapshot {
        originalIds = LongArray(0)
        shuffledIds = null
        currentIndex = -1
        bumpVersion()
        return snapshot()
    }

    fun skipToIndex(index: Int): PlaybackQueueSnapshot? {
        val activeIds = activeIds()
        if (index !in activeIds.indices) return null

        currentIndex = index
        return snapshot()
    }

    fun skipToTrack(trackId: Long): PlaybackQueueSnapshot? {
        val index = activeIds().indexOf(trackId)
        if (index < 0) return null

        currentIndex = index
        return snapshot()
    }

    fun skipToPrevious(): PlaybackQueueSnapshot? {
        val activeIds = activeIds()
        if (activeIds.isEmpty()) return null

        currentIndex = if (currentIndex <= 0) activeIds.lastIndex else currentIndex - 1
        return snapshot()
    }

    fun skipToNext(): PlaybackQueueSnapshot? {
        val activeIds = activeIds()
        if (activeIds.isEmpty()) return null

        currentIndex = if (currentIndex >= activeIds.lastIndex || currentIndex < 0) 0 else currentIndex + 1
        return snapshot()
    }

    fun move(fromIndex: Int, toIndex: Int): PlaybackQueueSnapshot? {
        val activeIds = activeIds()
        if (fromIndex == toIndex) return null
        if (fromIndex !in activeIds.indices || toIndex !in activeIds.indices) return null

        val currentId = activeIds.getOrNull(currentIndex)
        val movedActiveIds = activeIds.moved(fromIndex, toIndex)
        if (isShuffleEnabled) {
            shuffledIds = movedActiveIds
            originalIds = originalIds.reorderedLike(movedActiveIds)
        } else {
            originalIds = movedActiveIds
        }
        currentIndex = currentId?.let { movedActiveIds.indexOf(it) }?.takeIf { it >= 0 } ?: -1
        bumpVersion()
        return snapshot()
    }

    fun replaceActiveOrder(
        expectedQueueVersion: Long,
        orderedTrackIds: LongArray,
        currentTrackId: Long?,
        shuffleEnabled: Boolean,
        updateOriginalOrder: Boolean = !shuffleEnabled,
    ): PlaybackQueueSnapshot? {
        if (queueVersion != expectedQueueVersion) return null
        if (currentTrackId != null && this.currentTrackId != currentTrackId) return null
        if (!activeIds().hasSameElementsAs(orderedTrackIds)) return null

        if (shuffleEnabled) {
            shuffledIds = orderedTrackIds.copyOf()
            if (updateOriginalOrder) {
                originalIds = originalIds.reorderedLike(orderedTrackIds)
            }
        } else {
            shuffledIds = null
            originalIds = orderedTrackIds.copyOf()
        }

        currentIndex = currentTrackId
            ?.let { orderedTrackIds.indexOf(it) }
            ?.takeIf { it >= 0 }
            ?: if (orderedTrackIds.isEmpty()) -1 else currentIndex.coerceIn(0, orderedTrackIds.lastIndex)
        bumpVersion()
        return snapshot()
    }

    fun shuffle(random: Random = Random.Default): PlaybackQueueSnapshot? {
        val activeIds = activeIds()
        val currentId = activeIds.getOrNull(currentIndex) ?: return null
        if (activeIds.size <= 1) return snapshot()

        val remaining = activeIds.filter { it != currentId }.shuffled(random)
        shuffledIds = LongArray(activeIds.size).also { shuffled ->
            shuffled[0] = currentId
            remaining.forEachIndexed { index, id -> shuffled[index + 1] = id }
        }
        currentIndex = 0
        bumpVersion()
        return snapshot()
    }

    fun unshuffle(): PlaybackQueueSnapshot {
        val currentId = currentTrackId
        shuffledIds = null
        currentIndex = currentId?.let { originalIds.indexOf(it) }?.takeIf { it >= 0 }
            ?: if (originalIds.isEmpty()) -1 else currentIndex.coerceIn(0, originalIds.lastIndex)
        bumpVersion()
        return snapshot()
    }

    private fun activeIds(): LongArray = shuffledIds ?: originalIds

    private fun bumpVersion() {
        queueVersion += 1
    }
}

private fun LongArray.indexOf(value: Long): Int {
    for (index in indices) {
        if (this[index] == value) return index
    }
    return -1
}

private fun LongArray.moved(fromIndex: Int, toIndex: Int): LongArray {
    val result = toMutableList()
    val moved = result.removeAt(fromIndex)
    result.add(toIndex, moved)
    return result.toLongArray()
}

private fun LongArray.reorderedLike(activeOrder: LongArray): LongArray {
    val originalSet = toSet()
    if (activeOrder.any { it !in originalSet }) return this
    return activeOrder.copyOf()
}

internal fun LongArray.shuffledWithCurrentFirst(
    currentTrackId: Long,
    random: Random = Random.Default,
): LongArray {
    if (isEmpty()) return LongArray(0)
    val remaining = filter { it != currentTrackId }.shuffled(random)
    return LongArray(size).also { shuffled ->
        shuffled[0] = currentTrackId
        remaining.forEachIndexed { index, id -> shuffled[index + 1] = id }
    }
}

internal fun LongArray.movedCopy(fromIndex: Int, toIndex: Int): LongArray? {
    if (fromIndex == toIndex) return null
    if (fromIndex !in indices || toIndex !in indices) return null
    return moved(fromIndex, toIndex)
}

private fun LongArray.hasSameElementsAs(other: LongArray): Boolean {
    if (size != other.size) return false
    val counts = mutableMapOf<Long, Int>()
    forEach { id -> counts[id] = (counts[id] ?: 0) + 1 }
    other.forEach { id ->
        val count = counts[id] ?: return false
        if (count == 1) counts.remove(id) else counts[id] = count - 1
    }
    return counts.isEmpty()
}
