package dev.foldcode.ide

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds an installable, v2-signed APK around a prebuilt NativeActivity library.
 *
 * The APK contains only AndroidManifest.xml and `lib/arm64-v8a/lib<name>.so`.
 * The library must be built for Android (`aarch64-linux-android`), not for
 * desktop Linux; [validateLibrary] rejects the common mistakes with a clear message.
 */
internal object NativeApkPackager {
    private const val ABI_DIRECTORY = "lib/arm64-v8a"
    private const val ELF_MACHINE_AARCH64 = 183

    // Fixed timestamp so exporting the same inputs twice produces the same zip layout.
    private const val ENTRY_TIME_MILLIS = 1_577_836_800_000L

    private val PACKAGE_NAME = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")
    private val LIBRARY_FILE = Regex("^lib(.+)\\.so$")

    /** Returns the NativeActivity library name for `libfoo.so`, or null for other file names. */
    fun libraryNameOf(file: File): String? = LIBRARY_FILE.matchEntire(file.name)?.groupValues?.get(1)

    /** Returns a human-readable problem with [library], or null when it can go into an APK. */
    fun validateLibrary(library: ByteArray): String? {
        if (library.size < 20 ||
            library[0] != 0x7f.toByte() ||
            library[1] != 'E'.code.toByte() ||
            library[2] != 'L'.code.toByte() ||
            library[3] != 'F'.code.toByte()
        ) {
            return "The file is not a native library (ELF)."
        }
        if (library[4] != 2.toByte()) {
            return "The library is not 64-bit. Build it for aarch64-linux-android."
        }
        val machine = (library[18].toInt() and 0xff) or ((library[19].toInt() and 0xff) shl 8)
        if (machine != ELF_MACHINE_AARCH64) {
            return "The library is not an ARM64 build. Build it for aarch64-linux-android."
        }
        if (containsAscii(library, "libc.so.6")) {
            return "The library was built for desktop Linux (glibc) and cannot run in an APK. " +
                "Build it for aarch64-linux-android instead."
        }
        return null
    }

    fun build(spec: NativeApkManifestSpec, library: ByteArray, identity: ApkSigningIdentity): ByteArray {
        require(PACKAGE_NAME.matches(spec.packageName)) {
            "Invalid package name: ${spec.packageName}"
        }
        require(spec.libraryName.isNotBlank() && spec.libraryName.none { it == '/' || it == '\\' }) {
            "Invalid library name: ${spec.libraryName}"
        }
        validateLibrary(library)?.let { throw IllegalArgumentException(it) }

        val manifest = ApkNativeManifest.build(spec)
        val unsigned = ByteArrayOutputStream().also { bytes ->
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(entry("AndroidManifest.xml"))
                zip.write(manifest)
                zip.closeEntry()
                zip.putNextEntry(entry("$ABI_DIRECTORY/lib${spec.libraryName}.so"))
                zip.write(library)
                zip.closeEntry()
            }
        }.toByteArray()
        return ApkV2Signer.sign(unsigned, identity.privateKey, identity.certificate)
    }

    /** Reads [libraryFile], builds the APK and writes it to [output] atomically. */
    fun export(
        spec: NativeApkManifestSpec,
        libraryFile: File,
        identity: ApkSigningIdentity,
        output: File,
    ): File {
        require(libraryNameOf(libraryFile) == spec.libraryName) {
            "Library file ${libraryFile.name} does not match library name ${spec.libraryName}"
        }
        val apk = build(spec, libraryFile.readBytes(), identity)
        output.absoluteFile.parentFile?.mkdirs()
        val temporary = File(output.absolutePath + ".tmp")
        temporary.writeBytes(apk)
        check(temporary.renameTo(output) || (output.delete() && temporary.renameTo(output))) {
            "Could not write ${output.name}"
        }
        return output
    }

    private fun entry(name: String) = ZipEntry(name).apply { time = ENTRY_TIME_MILLIS }

    private fun containsAscii(data: ByteArray, needle: String): Boolean {
        val pattern = needle.toByteArray(Charsets.US_ASCII)
        outer@ for (start in 0..data.size - pattern.size) {
            for (offset in pattern.indices) {
                if (data[start + offset] != pattern[offset]) continue@outer
            }
            return true
        }
        return false
    }
}
