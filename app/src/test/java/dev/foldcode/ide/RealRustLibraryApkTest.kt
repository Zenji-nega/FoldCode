package dev.foldcode.ide

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CI-only: packages a real `aarch64-linux-android` cdylib that the workflow built
 * with Cargo, using the same code path as the Export APK button. Without the
 * environment variables it does nothing, so local test runs are unaffected.
 */
class RealRustLibraryApkTest {
    @Test
    fun packagesARealCargoBuiltLibraryWhenProvided() {
        val libraryPath = System.getenv("FOLDCODE_REAL_LIB")?.takeIf { it.isNotBlank() } ?: return
        val cargoToml = System.getenv("FOLDCODE_REAL_CARGO_TOML")?.takeIf { it.isNotBlank() }
            ?.let { File(it).readText() }
        val outputPath = requireNotNull(System.getenv("FOLDCODE_REAL_APK_OUT")?.takeIf { it.isNotBlank() }) {
            "FOLDCODE_REAL_APK_OUT is required when FOLDCODE_REAL_LIB is set"
        }
        val library = File(libraryPath)
        val spec = RustApkExport.specFor(cargoToml, library)
        val identity = ApkSigningIdentity.create("FoldCode CI")
        val output = NativeApkPackager.export(spec, library, identity, File(outputPath))
        assertTrue(output.length() > library.length())
    }
}
