package dev.foldcode.ide

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RustApkExportTest {
    private val cargo = """
        [package]
        name = "my-game"
        version = "0.3.7"
        edition = "2021"

        [lib]
        crate-type = ["cdylib"]

        [dependencies]
        name = "not-the-package"
    """.trimIndent()

    @Test
    fun specIsDerivedFromCargoToml() {
        val spec = RustApkExport.specFor(cargo, File("/p/libmy_game.so"))
        assertEquals("dev.foldcode.app.my_game", spec.packageName)
        assertEquals("my-game", spec.appLabel)
        assertEquals("0.3.7", spec.versionName)
        assertEquals(307, spec.versionCode)
        assertEquals("my_game", spec.libraryName)
        assertEquals("my-game", RustApkExport.crateName(cargo))
    }

    @Test
    fun missingOrOddCargoTomlFallsBackToSafeDefaults() {
        val spec = RustApkExport.specFor(null, File("/p/libdemo.so"))
        assertEquals("dev.foldcode.app.demo", spec.packageName)
        assertEquals("1.0.0", spec.versionName)
        assertEquals(10_000, spec.versionCode)

        assertEquals("app_9lives", RustApkExport.packageIdentifier("9Lives"))
        assertEquals("app", RustApkExport.packageIdentifier("---"))
        assertEquals(1, RustApkExport.versionCodeOf("not.a.version"))
        assertEquals(20_000, RustApkExport.versionCodeOf("2"))
        assertEquals(10_203, RustApkExport.versionCodeOf("1.2.3-beta.1+build"))
    }

    @Test
    fun libraryIsFoundInTheCargoTargetDirectory() {
        val targetDirectory = Files.createTempDirectory("foldcode-apk-export").toFile()
        try {
            assertNull(RustApkExport.findLibrary(targetDirectory, null, debug = false))
            val release = File(targetDirectory, "aarch64-linux-android/release").apply { mkdirs() }
            File(release, "libhelper.so").writeBytes(ByteArray(1))
            File(release, "libmy_game.so").writeBytes(ByteArray(1))
            File(release, "deps").mkdirs()
            File(release, "deps/libignored.so").writeBytes(ByteArray(1))

            assertEquals("libmy_game.so", RustApkExport.findLibrary(targetDirectory, "my-game", debug = false)?.name)
            assertEquals("libhelper.so", RustApkExport.findLibrary(targetDirectory, null, debug = false)?.name)
            // Profiles are not mixed: a release library is never used for a debug export.
            assertNull(RustApkExport.findLibrary(targetDirectory, "my-game", debug = true))

            // A glibc build for the host target must not be picked up.
            val host = File(targetDirectory, "release").apply { mkdirs() }
            File(host, "libhost.so").writeBytes(ByteArray(1))
            assertTrue(RustApkExport.findLibrary(targetDirectory, null, debug = false)!!.path.contains("aarch64-linux-android"))
        } finally {
            targetDirectory.deleteRecursively()
        }
    }

    @Test
    fun outputGoesToTheProjectDistFolder() {
        val spec = RustApkExport.specFor(cargo, File("/p/libmy_game.so"))
        assertEquals(File("/proj/dist/my_game.apk"), RustApkExport.outputFile(File("/proj"), spec))
        assertTrue(RustApkExport.missingTargetMessage().contains("aarch64-linux-android"))
        assertTrue(RustApkExport.missingLibraryMessage().contains("cdylib"))
    }
}
