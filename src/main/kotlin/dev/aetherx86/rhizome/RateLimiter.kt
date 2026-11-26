package dev.omakasekim.rhizome

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A cooperative token-bucket bandwidth limiter shared across peer coroutines.
 *
 * Reservations are accounted under a short lock while any required pacing [delay] runs outside
 * the lock, so multiple peers share a single aggregate byte rate without blocking each other.
 */
class RateLimiter(
    val bytesPerSecond: Long,
    val burstBytes: Long = maxOf(bytesPerSecond, MAX_BLOCK_SIZE.toLong()),
    private val clockNanos: () -> Long = System::nanoTime,
) {
    init {
        require(bytesPerSecond > 0) { "rate must be positive, got $bytesPerSecond" }
        require(burstBytes >= 1) { "burst must be positive, got $burstBytes" }
    }

    private val mutex = Mutex()
    private var tokens: Double = burstBytes.toDouble()
    private var lastRefillNanos: Long = clockNanos()

    /** Reserves [bytes] from the bucket and suspends until the rate budget covers them. */
    suspend fun acquire(bytes: Int) {
        if (bytes <= 0) return
        val waitMillis = mutex.withLock {
            val now = clockNanos()
            val elapsed = (now - lastRefillNanos).coerceAtLeast(0L)
            lastRefillNanos = now
            tokens = minOf(burstBytes.toDouble(), tokens + elapsed * bytesPerSecond.toDouble() / 1_000_000_000.0)
            tokens -= bytes
            if (tokens < 0.0) {
                ((-tokens) * 1000.0 / bytesPerSecond.toDouble()).toLong().coerceAtLeast(1L)
            } else {
                0L
            }
        }
        if (waitMillis > 0L) delay(waitMillis)
    }
}
