package dev.foldcode.ide

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.zip.ZipInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeApkPackagerTest {
    private val spec = NativeApkManifestSpec(
        packageName = "dev.example.hello",
        appLabel = "Hello Rust",
        versionCode = 3,
        versionName = "1.2.3",
        libraryName = "hello",
    )

    // A minimal 64-bit little-endian AArch64 ELF header followed by filler bytes.
    private fun fakeAndroidLibrary(extra: ByteArray = ByteArray(2000) { 7 }): ByteArray {
        val header = ByteArray(64)
        header[0] = 0x7f; header[1] = 'E'.code.toByte(); header[2] = 'L'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = 2; header[5] = 1; header[6] = 1
        header[18] = 183.toByte(); header[19] = 0
        return header + extra
    }

    private val identity by lazy { ApkSigningIdentity.create("FoldCode Test") }

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    @Test
    fun manifestIsAWellFormedBinaryXmlDocument() {
        val manifest = ApkNativeManifest.build(spec)
        val buffer = le(manifest)
        assertEquals(0x0003, buffer.getShort(0).toInt())
        assertEquals(manifest.size, buffer.getInt(4))

        val types = ArrayList<Int>()
        var position = 8
        while (position < manifest.size) {
            val size = buffer.getInt(position + 4)
            assertTrue("Chunk at $position has size $size", size >= 8 && position + size <= manifest.size)
            types += buffer.getShort(position).toInt() and 0xffff
            position += size
        }
        assertEquals(manifest.size, position)

        assertEquals(0x0001, types[0])
        assertEquals(0x0180, types[1])
        assertEquals(types.count { it == 0x0102 }, types.count { it == 0x0103 })
        assertEquals(2, types.count { it == 0x0100 } + types.count { it == 0x0101 })
        // manifest, uses-sdk, application, activity, meta-data, intent-filter, action, category
        assertEquals(8, types.count { it == 0x0102 })

        for (expected in listOf("android.app.NativeActivity", "android.app.lib_name", "dev.example.hello", "hello", "1.2.3")) {
            assertTrue("Missing $expected", contains(manifest, expected.toByteArray(Charsets.UTF_16LE)))
        }
    }

    @Test
    fun selfSignedCertificateIsValidAndSelfVerifying() {
        val certificate = identity.certificate
        certificate.checkValidity()
        certificate.verify(certificate.publicKey)
        assertTrue(certificate.subjectX500Principal.name.contains("FoldCode Test"))
        assertEquals("SHA256withRSA", certificate.sigAlgName)
        val reparsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate.encoded)) as X509Certificate
        assertEquals(certificate, reparsed)
    }

    @Test
    fun signingIdentityIsStableAcrossLoads() {
        val file = File.createTempFile("foldcode-identity", ".p12").also { it.delete() }
        try {
            val first = ApkSigningIdentity.loadOrCreate(file)
            val second = ApkSigningIdentity.loadOrCreate(file)
            assertArrayEquals(first.certificate.encoded, second.certificate.encoded)
        } finally {
            file.delete()
        }
    }

    @Test
    fun apkContainsManifestAndLibraryAndKeepsZipStructure() {
        val library = fakeAndroidLibrary()
        val apk = NativeApkPackager.build(spec, library, identity)

        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(apk)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("AndroidManifest.xml", "lib/arm64-v8a/libhello.so"), entries.keys)
        assertArrayEquals(library, entries.getValue("lib/arm64-v8a/libhello.so"))
        assertArrayEquals(ApkNativeManifest.build(spec), entries.getValue("AndroidManifest.xml"))
    }

    @Test
    fun v2SignatureMatchesAnIndependentVerification() {
        val apk = NativeApkPackager.build(spec, fakeAndroidLibrary(), identity)
        val buffer = le(apk)

        val eocd = apk.size - 22
        assertEquals(0x06054b50, buffer.getInt(eocd))
        val centralDirectoryOffset = buffer.getInt(eocd + 16)

        // Locate the signing block that sits directly before the central directory.
        assertArrayEquals(
            "APK Sig Block 42".toByteArray(Charsets.US_ASCII),
            apk.copyOfRange(centralDirectoryOffset - 16, centralDirectoryOffset),
        )
        val blockSize = buffer.getLong(centralDirectoryOffset - 24)
        val blockStart = (centralDirectoryOffset - blockSize - 8).toInt()
        assertEquals(blockSize, buffer.getLong(blockStart))

        val pairLength = buffer.getLong(blockStart + 8).toInt()
        assertEquals(0x7109871a, buffer.getInt(blockStart + 16))
        val v2Value = apk.copyOfRange(blockStart + 20, blockStart + 16 + pairLength)

        val signers = lengthPrefixedItems(lengthPrefixedItems(v2Value).single())
        val signer = lengthPrefixedItems(signers.single())
        val signedData = signer[0]
        // signer[1] is the length-prefixed list of signatures; take its single entry.
        val signatureEntry = lengthPrefixedItems(signer[1]).single()
        val publicKey = signer[2]

        assertArrayEquals(identity.certificate.publicKey.encoded, publicKey)

        // signedData = digests, certificates, additional attributes.
        val signedParts = lengthPrefixedItems(signedData)
        val digestEntry = lengthPrefixedItems(signedParts[0]).single()
        assertEquals(0x0103, le(digestEntry).getInt(0))
        val signedDigest = lengthPrefixedItems(digestEntry.copyOfRange(4, digestEntry.size)).single()
        assertArrayEquals(identity.certificate.encoded, lengthPrefixedItems(signedParts[1]).single())

        // Rebuild the unsigned APK and compute the digest without using the signer's own code.
        val unsigned = apk.copyOfRange(0, blockStart) +
            apk.copyOfRange(centralDirectoryOffset, eocd) +
            apk.copyOfRange(eocd, apk.size).also { le(it).putInt(16, blockStart) }
        val unsignedCentralDirectory = blockStart
        val unsignedEocd = unsigned.size - 22
        val sections = listOf(
            unsigned.copyOfRange(0, unsignedCentralDirectory),
            unsigned.copyOfRange(unsignedCentralDirectory, unsignedEocd),
            unsigned.copyOfRange(unsignedEocd, unsigned.size),
        )
        sections.forEach { assertTrue("Test needs sections below one chunk", it.size in 1..(1024 * 1024)) }
        val chunkDigests = sections.map { section ->
            MessageDigest.getInstance("SHA-256").run {
                update(0xa5.toByte())
                update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(section.size).array())
                update(section)
                digest()
            }
        }
        val expectedDigest = MessageDigest.getInstance("SHA-256").run {
            update(0x5a.toByte())
            update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(chunkDigests.size).array())
            chunkDigests.forEach { update(it) }
            digest()
        }
        assertArrayEquals(expectedDigest, signedDigest)

        // The signature must verify over signedData with the certificate's key.
        assertEquals(0x0103, le(signatureEntry).getInt(0))
        val signatureBytes = lengthPrefixedItems(signatureEntry.copyOfRange(4, signatureEntry.size)).single()
        val verified = Signature.getInstance("SHA256withRSA").run {
            initVerify(identity.certificate.publicKey)
            update(signedData)
            verify(signatureBytes)
        }
        assertTrue("v2 signature did not verify", verified)
    }

    @Test
    fun libraryValidationExplainsCommonMistakes() {
        assertNull(NativeApkPackager.validateLibrary(fakeAndroidLibrary()))
        assertNotNull(NativeApkPackager.validateLibrary(ByteArray(100)))

        val x86 = fakeAndroidLibrary().also { it[18] = 62 }
        assertTrue(NativeApkPackager.validateLibrary(x86)!!.contains("ARM64"))

        val glibc = fakeAndroidLibrary("....libc.so.6....".toByteArray())
        assertTrue(NativeApkPackager.validateLibrary(glibc)!!.contains("aarch64-linux-android"))
    }

    @Test
    fun invalidInputsAreRejectedBeforeAnythingIsBuilt() {
        try {
            NativeApkPackager.build(spec.copy(packageName = "no_dots"), fakeAndroidLibrary(), identity)
            fail("Expected an invalid package name to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("package"))
        }
        try {
            NativeApkPackager.build(spec, ByteArray(10), identity)
            fail("Expected a non-ELF library to be rejected")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("ELF"))
        }
        assertEquals("hello", NativeApkPackager.libraryNameOf(File("/x/libhello.so")))
        assertNull(NativeApkPackager.libraryNameOf(File("/x/hello.so")))
    }

    /** When set, also writes a sample APK so CI can check it with Google's own apksigner and aapt2. */
    @Test
    fun writesSampleApkForExternalVerification() {
        val target = System.getenv("FOLDCODE_APK_TEST_OUT")?.takeIf { it.isNotBlank() } ?: return
        val directory = File(target).also { it.mkdirs() }
        val library = File(directory, "libhello.so").apply { writeBytes(fakeAndroidLibrary()) }
        val output = NativeApkPackager.export(spec, library, identity, File(directory, "sample.apk"))
        assertTrue(output.length() > 0)
    }

    private fun lengthPrefixedItems(data: ByteArray): List<ByteArray> {
        val buffer = le(data)
        val items = ArrayList<ByteArray>()
        var position = 0
        while (position < data.size) {
            val length = buffer.getInt(position)
            position += 4
            items += data.copyOfRange(position, position + length)
            position += length
        }
        assertEquals(data.size, position)
        return items
    }

    private fun contains(data: ByteArray, pattern: ByteArray): Boolean {
        outer@ for (start in 0..data.size - pattern.size) {
            for (offset in pattern.indices) {
                if (data[start + offset] != pattern[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
