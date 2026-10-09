package com.doomedams.app.server.security

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Standard, secure password hashing using PBKDF2WithHmacSHA256.
 *
 * SPECIFICATION:
 * - Hash algorithm: PBKDF2WithHmacSHA256
 * - Salt: 16 cryptographically secure random bytes
 * - Iterations: 100,000
 * - Key length: 256 bits (32 bytes)
 * - Format: pbkdf2_sha256$<iterations>$<saltHex>$<hashHex>
 * - Verification: Constant-time byte array comparison (MessageDigest.isEqual)
 */
object PasswordHasher {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val ITERATIONS = 100000
    private const val KEY_LENGTH = 256
    private const val SALT_LENGTH = 16

    private val secureRandom = SecureRandom()

    fun hashPassword(password: String): Pair<String, String> {
        val saltBytes = ByteArray(SALT_LENGTH)
        secureRandom.nextBytes(saltBytes)
        val saltHex = bytesToHex(saltBytes)

        val hashBytes = pbkdf2(password.toCharArray(), saltBytes, ITERATIONS, KEY_LENGTH)
        val hashHex = bytesToHex(hashBytes)

        val encodedHash = "pbkdf2_sha256\$$ITERATIONS\$$saltHex\$$hashHex"
        return Pair(encodedHash, saltHex)
    }

    fun verifyPassword(password: String, storedHash: String): Boolean {
        val parts = storedHash.split("$")
        if (parts.size != 4) return false
        val algorithmTag = parts[0]
        if (algorithmTag != "pbkdf2_sha256") return false

        val iterations = parts[1].toIntOrNull() ?: return false
        val saltBytes = hexToBytes(parts[2]) ?: return false
        val expectedHashBytes = hexToBytes(parts[3]) ?: return false

        val actualHashBytes = pbkdf2(password.toCharArray(), saltBytes, iterations, KEY_LENGTH)
        return MessageDigest.isEqual(actualHashBytes, expectedHashBytes)
    }

    private fun pbkdf2(password: CharArray, salt: ByteArray, iterations: Int, keyLength: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, keyLength)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray? {
        if (hex.length % 2 != 0) return null
        return try {
            ByteArray(hex.length / 2) { i ->
                hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (e: Exception) {
            null
        }
    }
}
