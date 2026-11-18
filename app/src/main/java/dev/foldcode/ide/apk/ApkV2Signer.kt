package dev.foldcode.ide

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.X509Certificate

/**
 * APK Signature Scheme v2 using only JDK classes.
 *
 * Android 11+ refuses to install apps that target API 30 or newer unless they
 * carry a v2 (or newer) signature, so the exported APK is signed with v2 only.
 * Layout reference: source.android.com/docs/security/features/apksigning/v2
 */
internal object ApkV2Signer {
    private const val EOCD_SIZE = 22
    private const val EOCD_SIGNATURE = 0x06054b50
    private const val SIGNING_BLOCK_ID_V2 = 0x7109871a
    private const val SIGNATURE_RSA_PKCS1_SHA256 = 0x0103
    private const val CHUNK_SIZE = 1024 * 1024
    private val BLOCK_MAGIC = "APK Sig Block 42".toByteArray(Charsets.US_ASCII)

    /** [unsignedApk] must be a plain zip without an archive comment and without a signing block. */
    fun sign(unsignedApk: ByteArray, privateKey: PrivateKey, certificate: X509Certificate): ByteArray {
        val eocdOffset = unsignedApk.size - EOCD_SIZE
        require(eocdOffset >= 0 && readLe32(unsignedApk, eocdOffset) == EOCD_SIGNATURE) {
            "The APK must end with a zip end-of-central-directory record without a comment"
        }
        require(readLe16(unsignedApk, eocdOffset + 20) == 0) { "Zip comments are not supported" }
        val centralDirectorySize = readLe32(unsignedApk, eocdOffset + 12)
        val centralDirectoryOffset = readLe32(unsignedApk, eocdOffset + 16)
        require(centralDirectoryOffset + centralDirectorySize == eocdOffset) {
            "Unexpected data between the central directory and its end record"
        }

        val contentDigest = computeContentDigest(unsignedApk, centralDirectoryOffset, eocdOffset)
        val signedData = concat(
            lengthPrefixed(lengthPrefixed(le32(SIGNATURE_RSA_PKCS1_SHA256) + lengthPrefixed(contentDigest))),
            lengthPrefixed(lengthPrefixed(certificate.encoded)),
            lengthPrefixed(ByteArray(0)),
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(signedData)
            sign()
        }
        val signer = concat(
            lengthPrefixed(signedData),
            lengthPrefixed(lengthPrefixed(le32(SIGNATURE_RSA_PKCS1_SHA256) + lengthPrefixed(signature))),
            lengthPrefixed(certificate.publicKey.encoded),
        )
        val v2Value = lengthPrefixed(lengthPrefixed(signer))
        val signingBlock = buildSigningBlock(v2Value)

        val signedEocd = unsignedApk.copyOfRange(eocdOffset, unsignedApk.size)
        writeLe32(signedEocd, 16, centralDirectoryOffset + signingBlock.size)

        return ByteArrayOutputStream(unsignedApk.size + signingBlock.size).apply {
            write(unsignedApk, 0, centralDirectoryOffset)
            write(signingBlock)
            write(unsignedApk, centralDirectoryOffset, centralDirectorySize)
            write(signedEocd)
        }.toByteArray()
    }

    private fun buildSigningBlock(v2Value: ByteArray): ByteArray {
        val pair = concat(le64((4 + v2Value.size).toLong()), le32(SIGNING_BLOCK_ID_V2), v2Value)
        // The size field counts the id-value pairs, the repeated size field and the magic.
        val blockSize = (pair.size + 8 + BLOCK_MAGIC.size).toLong()
        return concat(le64(blockSize), pair, le64(blockSize), BLOCK_MAGIC)
    }

    /**
     * Digest over the three zip sections. [centralDirectoryOffset] in the end
     * record is, by definition, the offset where the signing block starts, which
     * in an unsigned APK is the same number.
     */
    internal fun computeContentDigest(apk: ByteArray, centralDirectoryOffset: Int, eocdOffset: Int): ByteArray {
        val chunkDigests = ByteArrayOutputStream()
        var chunkCount = 0

        fun addSection(start: Int, end: Int) {
            var position = start
            while (position < end) {
                val length = minOf(CHUNK_SIZE, end - position)
                val digest = MessageDigest.getInstance("SHA-256")
                digest.update(0xa5.toByte())
                digest.update(le32(length))
                digest.update(apk, position, length)
                chunkDigests.write(digest.digest())
                chunkCount++
                position += length
            }
        }

        addSection(0, centralDirectoryOffset)
        addSection(centralDirectoryOffset, eocdOffset)
        addSection(eocdOffset, apk.size)

        val top = MessageDigest.getInstance("SHA-256")
        top.update(0x5a.toByte())
        top.update(le32(chunkCount))
        top.update(chunkDigests.toByteArray())
        return top.digest()
    }

    private fun lengthPrefixed(data: ByteArray): ByteArray = le32(data.size) + data

    private fun concat(vararg parts: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(parts.sumOf { it.size })
        parts.forEach { out.write(it) }
        return out.toByteArray()
    }

    private fun le32(value: Int) = ByteArray(4) { (value ushr (8 * it)).toByte() }

    private fun le64(value: Long) = ByteArray(8) { (value ushr (8 * it)).toByte() }

    private fun readLe16(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xff) or ((data[offset + 1].toInt() and 0xff) shl 8)

    private fun readLe32(data: ByteArray, offset: Int): Int =
        readLe16(data, offset) or (readLe16(data, offset + 2) shl 16)

    private fun writeLe32(data: ByteArray, offset: Int, value: Int) {
        for (i in 0 until 4) data[offset + i] = (value ushr (8 * i)).toByte()
    }
}
