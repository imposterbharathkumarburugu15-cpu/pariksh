package com.doomedams.app.server.auth

import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * Authenticated Server-Side Session Record
 */
data class UserSession(
    val sessionId: String,
    val token: String,
    val userId: String,
    val displayName: String,
    val role: String,
    val instituteScope: String,
    val examinationScope: String? = null,
    val createdAt: Instant,
    val expiresAt: Instant
) {
    fun isExpired(): Boolean = Instant.now().isAfter(expiresAt)
}

/**
 * Thread-safe In-Memory Session Manager.
 * All session validation and role lookup occurs strictly on the trusted examiner server.
 */
class SessionManager(private val sessionValiditySeconds: Long = 14400) { // 4 hours default

    private val sessionsByToken = ConcurrentHashMap<String, UserSession>()
    private val sessionsByUserId = ConcurrentHashMap<String, MutableSet<String>>()
    private val secureRandom = SecureRandom()

    fun createSession(
        userId: String,
        displayName: String,
        role: String,
        instituteScope: String,
        examinationScope: String? = null
    ): UserSession {
        val token = generateToken()
        val sessionId = "ses_${System.currentTimeMillis()}_${generateToken().take(8)}"
        val now = Instant.now()
        val expiresAt = now.plusSeconds(sessionValiditySeconds)

        val session = UserSession(
            sessionId = sessionId,
            token = token,
            userId = userId,
            displayName = displayName,
            role = role,
            instituteScope = instituteScope,
            examinationScope = examinationScope,
            createdAt = now,
            expiresAt = expiresAt
        )

        sessionsByToken[token] = session
        sessionsByUserId.computeIfAbsent(userId) { ConcurrentHashMap.newKeySet() }.add(token)
        return session
    }

    fun getSession(token: String): UserSession? {
        val session = sessionsByToken[token] ?: return null
        if (session.isExpired()) {
            invalidateSession(token)
            return null
        }
        return session
    }

    fun invalidateSession(token: String) {
        val session = sessionsByToken.remove(token)
        if (session != null) {
            sessionsByUserId[session.userId]?.remove(token)
        }
    }

    fun invalidateAllSessionsForUser(userId: String) {
        val tokens = sessionsByUserId.remove(userId) ?: return
        for (token in tokens) {
            sessionsByToken.remove(token)
        }
    }

    fun getActiveSessionCount(): Int {
        cleanExpiredSessions()
        return sessionsByToken.size
    }

    private fun cleanExpiredSessions() {
        val now = Instant.now()
        val iterator = sessionsByToken.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now.isAfter(entry.value.expiresAt)) {
                sessionsByUserId[entry.value.userId]?.remove(entry.key)
                iterator.remove()
            }
        }
    }

    private fun generateToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}
