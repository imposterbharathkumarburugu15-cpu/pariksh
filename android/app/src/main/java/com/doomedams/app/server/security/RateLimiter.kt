package com.doomedams.app.server.security

import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory sliding window rate limiter for protecting sensitive endpoints (e.g. login).
 */
class RateLimiter(
    private val maxAttempts: Int = 5,
    private val windowSeconds: Long = 60
) {
    private val attemptsByKey = ConcurrentHashMap<String, MutableList<Instant>>()

    @Synchronized
    fun isAllowed(key: String): Boolean {
        val now = Instant.now()
        val cutoff = now.minusSeconds(windowSeconds)

        val attempts = attemptsByKey.computeIfAbsent(key) { mutableListOf() }
        attempts.removeAll { it.isBefore(cutoff) }

        return if (attempts.size < maxAttempts) {
            attempts.add(now)
            true
        } else {
            false
        }
    }

    @Synchronized
    fun reset(key: String) {
        attemptsByKey.remove(key)
    }
}
