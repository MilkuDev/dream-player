package org.milkdev.dreamplayer.extensions.network

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.milkdev.dreamplayer.currentTimeMillis
import kotlin.time.Duration.Companion.milliseconds

class RequestRateLimiter(
    private val intervalMs: Long,
    private val now: () -> Long = ::currentTimeMillis,
    private val delayMs: suspend (Long) -> Unit = { delay(it.milliseconds) },
) {
    private val mutex = Mutex()
    private var nextRequestAt = 0L

    suspend fun awaitPermit() {
        mutex.withLock {
            val waitMs = nextRequestAt - now()
            if (waitMs > 0L) {
                delayMs(waitMs)
            }
            nextRequestAt = now() + intervalMs.coerceAtLeast(0L)
        }
    }

    suspend fun cooldown(durationMs: Long) {
        mutex.withLock {
            nextRequestAt = maxOf(nextRequestAt, now() + durationMs.coerceAtLeast(0L))
        }
    }
}
