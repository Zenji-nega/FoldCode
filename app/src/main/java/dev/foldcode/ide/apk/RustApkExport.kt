package dev.foldcode.ide

import java.io.File

/** Finds the Android library of a Rust project and derives the APK settings from Cargo.toml. */
internal object RustApkExport {
    const val ANDROID_TARGET = "aarch64-linux-android"

    private val PACKAGE_SECTION = Regex("""(?m)^\[package]\s*$""")
    private val NAME = Regex("""(?m)^\s*name\s*=\s*"([^"]+)"""")
    private val VERSION = Regex("""(?m)^\s*version\s*=\s*"([^"]+)"""")

    /**
     * Cargo's output for an Android build: `<cargo target dir>/aarch64-linux-android/<profile>/lib<crate>.so`.
     * FoldCode keeps Cargo's target directory in app-private storage, outside the project.
     */
    fun findLibrary(cargoTargetDirectory: File, crateName: String?, debug: Boolean): File? {
        val expected = crateName?.let { "lib${it.replace('-', '_')}.so" }
        val directory = File(cargoTargetDirectory, "$ANDROID_TARGET/${if (debug) "debug" else "release"}")
        val candidates = directory.listFiles { file ->
            file.isFile && file.name.startsWith("lib") && file.name.endsWith(".so")
        }?.sortedBy { it.name }.orEmpty()
        return candidates.firstOrNull { it.name == expected } ?: candidates.firstOrNull()
    }

    fun crateName(cargoToml: String?): String? = packageSection(cargoToml)?.let {
        NAME.find(it)?.groupValues?.get(1)
    }

    fun specFor(cargoToml: String?, library: File): NativeApkManifestSpec {
        val libraryName = requireNotNull(NativeApkPackager.libraryNameOf(library)) {
            "${library.name} is not named lib<name>.so"
        }
        val section = packageSection(cargoToml)
        val crate = section?.let { NAME.find(it)?.groupValues?.get(1) } ?: libraryName
        val version = section?.let { VERSION.find(it)?.groupValues?.get(1) } ?: "1.0.0"
        return NativeApkManifestSpec(
            packageName = "dev.foldcode.app.${packageIdentifier(crate)}",
            appLabel = crate,
            versionCode = versionCodeOf(version),
            versionName = version,
            libraryName = libraryName,
        )
    }

    fun outputFile(projectDirectory: File, spec: NativeApkManifestSpec): File =
        File(projectDirectory, "dist/${spec.packageName.substringAfterLast('.')}.apk")

    fun missingLibraryMessage(): String =
        "Cargo finished, but no Android library (lib<name>.so) was produced.\n" +
            "Add a [lib] section with crate-type = [\"cdylib\"] to Cargo.toml, " +
            "and export an android_main entry point (for example with the android-activity crate)."

    fun missingTargetMessage(): String =
        "The installed Rust extension cannot build for Android ($ANDROID_TARGET).\n" +
            "Install a Rust extension package that includes the Android target (version 1.0.0+5 or newer)."

    internal fun packageIdentifier(crateName: String): String {
        val cleaned = crateName.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }
            .joinToString("")
            .trim('_')
            .ifEmpty { "app" }
        return if (cleaned.first() in '0'..'9') "app_$cleaned" else cleaned
    }

    internal fun versionCodeOf(version: String): Int {
        val parts = version.substringBefore('-').substringBefore('+').split('.')
        val numbers = parts.take(3).map { it.toIntOrNull() ?: return 1 }
        val (major, minor, patch) = numbers + List(3 - numbers.size) { 0 }
        return (major * 10_000 + minor * 100 + patch).coerceAtLeast(1)
    }

    private fun packageSection(cargoToml: String?): String? {
        if (cargoToml == null) return null
        val start = PACKAGE_SECTION.find(cargoToml) ?: return null
        val body = cargoToml.substring(start.range.last + 1)
        val end = Regex("(?m)^\\[").find(body)?.range?.first ?: body.length
        return body.substring(0, end)
    }
}
