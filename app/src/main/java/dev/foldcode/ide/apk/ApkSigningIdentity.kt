package dev.foldcode.ide

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * The key and certificate that sign exported APKs.
 *
 * Android has no public API for building a certificate, so the self-signed
 * X.509 structure is encoded here by hand. The identity is stored in a PKCS#12
 * file inside the app's private storage. Reusing it keeps the signature stable,
 * which is what lets a later export update an earlier install in place.
 */
internal class ApkSigningIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    companion object {
        private const val ALIAS = "foldcode-apk"

        // The store lives in app-private storage and protects nothing secret: it
        // is a development identity, equivalent to Android's debug keystore.
        private val STORE_PASSWORD = "foldcode-apk".toCharArray()

        fun loadOrCreate(file: File, commonName: String = "FoldCode Export"): ApkSigningIdentity {
            if (file.isFile) {
                runCatching { load(file) }.getOrNull()?.let { return it }
            }
            val created = create(commonName)
            save(file, created)
            return created
        }

        internal fun create(commonName: String): ApkSigningIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").run {
                initialize(2048)
                generateKeyPair()
            }
            val certificate = SelfSignedCertificate.create(commonName, keyPair.public.encoded, keyPair.private)
            return ApkSigningIdentity(keyPair.private, certificate)
        }

        private fun load(file: File): ApkSigningIdentity {
            val store = KeyStore.getInstance("PKCS12")
            file.inputStream().use { store.load(it, STORE_PASSWORD) }
            val key = store.getKey(ALIAS, STORE_PASSWORD) as PrivateKey
            val certificate = store.getCertificate(ALIAS) as X509Certificate
            return ApkSigningIdentity(key, certificate)
        }

        private fun save(file: File, identity: ApkSigningIdentity) {
            val store = KeyStore.getInstance("PKCS12")
            store.load(null, null)
            store.setKeyEntry(ALIAS, identity.privateKey, STORE_PASSWORD, arrayOf(identity.certificate))
            file.absoluteFile.parentFile?.mkdirs()
            val temporary = File(file.absolutePath + ".tmp")
            temporary.outputStream().use { store.store(it, STORE_PASSWORD) }
            check(temporary.renameTo(file)) { "Could not save the APK signing identity" }
        }
    }
}

/** Minimal DER encoder for a v3 self-signed RSA certificate (SHA256withRSA). */
internal object SelfSignedCertificate {
    // 1.2.840.113549.1.1.11 sha256WithRSAEncryption
    private val OID_SHA256_RSA = byteArrayOf(
        0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b,
    )

    // 2.5.4.3 commonName
    private val OID_COMMON_NAME = byteArrayOf(0x55, 0x04, 0x03)

    fun create(commonName: String, subjectPublicKeyInfo: ByteArray, signingKey: PrivateKey): X509Certificate {
        val algorithm = der(0x30, der(0x06, OID_SHA256_RSA) + der(0x05, ByteArray(0)))
        val name = der(
            0x30,
            der(0x31, der(0x30, der(0x06, OID_COMMON_NAME) + der(0x0c, commonName.toByteArray(Charsets.UTF_8)))),
        )
        val now = ZonedDateTime.now(ZoneOffset.UTC).minusDays(1)
        val validity = der(0x30, time(now) + time(now.plusYears(30)))
        val serial = ByteArray(8).also { SecureRandom().nextBytes(it) }
        // Keep the serial positive and free of a leading zero byte.
        serial[0] = ((serial[0].toInt() and 0x3f) or 0x40).toByte()

        val tbs = der(
            0x30,
            concat(
                der(0xa0, der(0x02, byteArrayOf(0x02))),
                der(0x02, serial),
                algorithm,
                name,
                validity,
                name,
                subjectPublicKeyInfo,
            ),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(signingKey)
            update(tbs)
            sign()
        }
        val certificate = der(0x30, concat(tbs, algorithm, der(0x03, byteArrayOf(0) + signature)))
        return CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
    }

    private fun time(value: ZonedDateTime): ByteArray =
        if (value.year < 2050) {
            der(0x17, value.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).toByteArray(Charsets.US_ASCII))
        } else {
            der(0x18, value.format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'")).toByteArray(Charsets.US_ASCII))
        }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val length = content.size
        when {
            length < 0x80 -> out.write(length)
            length < 0x100 -> { out.write(0x81); out.write(length) }
            length < 0x10000 -> { out.write(0x82); out.write(length ushr 8); out.write(length and 0xff) }
            else -> error("DER element is too large")
        }
        out.write(content)
        return out.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }
}
