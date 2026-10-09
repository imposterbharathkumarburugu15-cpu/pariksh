package com.doomedams.app.server.security

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

/**
 * Result of TLS KeyStore generation
 */
data class TlsConfiguration(
    val keyStore: KeyStore,
    val keyStorePassword: CharArray,
    val certificate: X509Certificate,
    val sha256Fingerprint: String,
    val certificatePem: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TlsConfiguration
        return sha256Fingerprint == other.sha256Fingerprint
    }

    override fun hashCode(): Int = sha256Fingerprint.hashCode()
}

/**
 * Generates dynamic, device-specific TLS certificates for offline examiner servers.
 * Eliminates hardcoded private keys or permissive trust managers.
 */
object TlsCertificateManager {

    private const val KEY_ALIAS = "doomedams_examiner"
    private const val VALIDITY_DAYS = 30L

    fun generateSelfSignedCertificate(commonName: String = "DOOMEDAMS-Examiner-Node"): TlsConfiguration {
        // 1. Generate RSA 2048 KeyPair
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val keyPair: KeyPair = keyGen.generateKeyPair()

        // 2. Build X.509 Certificate
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 1000L * 60 * 60) // 1 hour ago to account for clock skew
        val notAfter = Date(now + 1000L * 60 * 60 * 24 * VALIDITY_DAYS)
        val serialNumber = BigInteger(64, SecureRandom())
        val subject = X500Name("CN=$commonName, O=DOOMEDAMS, OU=Examination Node")

        val certBuilder: X509v3CertificateBuilder = JcaX509v3CertificateBuilder(
            subject,
            serialNumber,
            notBefore,
            notAfter,
            subject,
            keyPair.public
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        val certHolder = certBuilder.build(signer)
        val certificate: X509Certificate = JcaX509CertificateConverter().getCertificate(certHolder)

        // 3. Calculate SHA-256 Fingerprint
        val digest = MessageDigest.getInstance("SHA-256")
        val encodedCert = certificate.encoded
        val fingerprintBytes = digest.digest(encodedCert)
        val sha256Fingerprint = fingerprintBytes.joinToString(":") { "%02X".format(it) }

        // 4. PEM format
        val pemBase64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(encodedCert)
        val certificatePem = "-----BEGIN CERTIFICATE-----\n$pemBase64\n-----END CERTIFICATE-----"

        // 5. Store in PKCS12 KeyStore
        val keyStorePassword = generateSecurePassword()
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            keyStorePassword,
            arrayOf(certificate)
        )

        return TlsConfiguration(
            keyStore = keyStore,
            keyStorePassword = keyStorePassword,
            certificate = certificate,
            sha256Fingerprint = sha256Fingerprint,
            certificatePem = certificatePem
        )
    }

    private fun generateSecurePassword(): CharArray {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
    }
}
