package dev.foldcode.ide

import java.io.ByteArrayOutputStream

/** Values written into the generated AndroidManifest.xml of an exported native APK. */
internal data class NativeApkManifestSpec(
    val packageName: String,
    val appLabel: String,
    val versionCode: Int,
    val versionName: String,
    /** Library name without the `lib` prefix and `.so` suffix, as NativeActivity expects it. */
    val libraryName: String,
    val minSdk: Int = 26,
    val targetSdk: Int = 34,
)

/**
 * Writes the binary XML (AXML) form of a minimal NativeActivity manifest.
 *
 * The manifest references no resources, so the APK needs neither resources.arsc
 * nor any dex code. That lets FoldCode assemble an installable APK on the device
 * without aapt2, d8 or the Android SDK.
 */
internal object ApkNativeManifest {
    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"

    private const val CHUNK_XML = 0x0003
    private const val CHUNK_STRING_POOL = 0x0001
    private const val CHUNK_RESOURCE_MAP = 0x0180
    private const val CHUNK_START_NAMESPACE = 0x0100
    private const val CHUNK_END_NAMESPACE = 0x0101
    private const val CHUNK_START_ELEMENT = 0x0102
    private const val CHUNK_END_ELEMENT = 0x0103

    private const val TYPE_STRING = 0x03
    private const val TYPE_INT_DEC = 0x10
    private const val TYPE_INT_HEX = 0x11
    private const val TYPE_INT_BOOLEAN = 0x12

    // android.R.attr resource ids. The string pool must list these attribute
    // names first, in this order, because the resource map is index based.
    private val RESOURCE_IDS: Map<String, Int> = linkedMapOf(
        "label" to 0x01010001,
        "name" to 0x01010003,
        "hasCode" to 0x0101000c,
        "exported" to 0x01010010,
        "configChanges" to 0x0101001f,
        "value" to 0x01010024,
        "minSdkVersion" to 0x0101020c,
        "versionCode" to 0x0101021b,
        "versionName" to 0x0101021c,
        "targetSdkVersion" to 0x01010270,
        "extractNativeLibs" to 0x010104ea,
    )

    // keyboard | keyboardHidden | orientation | screenLayout | uiMode | screenSize | smallestScreenSize
    private const val CONFIG_CHANGES = 0x0fb0

    private class HexInt(val value: Int)
    private class Attribute(val name: String, val namespaced: Boolean, val value: Any)
    private class Element(
        val tag: String,
        val attributes: List<Attribute>,
        val children: List<Element> = emptyList(),
    )

    private fun android(name: String, value: Any) = Attribute(name, true, value)

    fun build(spec: NativeApkManifestSpec): ByteArray {
        val activity = Element(
            "activity",
            listOf(
                android("name", "android.app.NativeActivity"),
                android("label", spec.appLabel),
                android("configChanges", HexInt(CONFIG_CHANGES)),
                android("exported", true),
            ),
            listOf(
                Element(
                    "meta-data",
                    listOf(android("name", "android.app.lib_name"), android("value", spec.libraryName)),
                ),
                Element(
                    "intent-filter",
                    emptyList(),
                    listOf(
                        Element("action", listOf(android("name", "android.intent.action.MAIN"))),
                        Element("category", listOf(android("name", "android.intent.category.LAUNCHER"))),
                    ),
                ),
            ),
        )
        val application = Element(
            "application",
            listOf(
                android("label", spec.appLabel),
                android("hasCode", false),
                android("extractNativeLibs", true),
            ),
            listOf(activity),
        )
        val root = Element(
            "manifest",
            listOf(
                android("versionCode", spec.versionCode),
                android("versionName", spec.versionName),
                Attribute("package", false, spec.packageName),
            ),
            listOf(
                Element(
                    "uses-sdk",
                    listOf(android("minSdkVersion", spec.minSdk), android("targetSdkVersion", spec.targetSdk)),
                ),
                application,
            ),
        )
        return encode(root)
    }

    private class StringPool {
        val items = ArrayList<String>()
        private val indexes = HashMap<String, Int>()

        fun index(value: String): Int = indexes.getOrPut(value) {
            items.add(value)
            items.size - 1
        }
    }

    private fun encode(root: Element): ByteArray {
        val pool = StringPool()
        val resourceEntries = RESOURCE_IDS.entries.sortedBy { it.value }
        resourceEntries.forEach { pool.index(it.key) }
        val prefixIndex = pool.index("android")
        val uriIndex = pool.index(ANDROID_NS)

        val body = ByteArrayOutputStream()
        var line = 1

        fun namespace(type: Int) {
            body.u16(type); body.u16(16); body.u32(24)
            body.u32(line); body.u32(-1)
            body.u32(prefixIndex); body.u32(uriIndex)
        }

        fun emit(element: Element) {
            val nameIndex = pool.index(element.tag)
            val attributes = element.attributes.sortedBy {
                if (it.namespaced) RESOURCE_IDS.getValue(it.name) else Int.MAX_VALUE
            }
            body.u16(CHUNK_START_ELEMENT); body.u16(16); body.u32(36 + 20 * attributes.size)
            body.u32(line++); body.u32(-1)
            body.u32(-1); body.u32(nameIndex)
            body.u16(20); body.u16(20); body.u16(attributes.size)
            body.u16(0); body.u16(0); body.u16(0)
            for (attribute in attributes) {
                body.u32(if (attribute.namespaced) uriIndex else -1)
                body.u32(pool.index(attribute.name))
                when (val value = attribute.value) {
                    is String -> {
                        val valueIndex = pool.index(value)
                        body.u32(valueIndex)
                        typed(body, TYPE_STRING, valueIndex)
                    }
                    is Int -> {
                        body.u32(-1)
                        typed(body, TYPE_INT_DEC, value)
                    }
                    is Boolean -> {
                        body.u32(-1)
                        typed(body, TYPE_INT_BOOLEAN, if (value) -1 else 0)
                    }
                    is HexInt -> {
                        body.u32(-1)
                        typed(body, TYPE_INT_HEX, value.value)
                    }
                    else -> error("Unsupported manifest attribute value: $value")
                }
            }
            element.children.forEach(::emit)
            body.u16(CHUNK_END_ELEMENT); body.u16(16); body.u32(24)
            body.u32(line++); body.u32(-1)
            body.u32(-1); body.u32(nameIndex)
        }

        namespace(CHUNK_START_NAMESPACE)
        emit(root)
        namespace(CHUNK_END_NAMESPACE)

        val poolChunk = stringPoolChunk(pool.items)
        val resourceMapChunk = ByteArrayOutputStream().apply {
            u16(CHUNK_RESOURCE_MAP); u16(8); u32(8 + 4 * resourceEntries.size)
            resourceEntries.forEach { u32(it.value) }
        }.toByteArray()

        val total = 8 + poolChunk.size + resourceMapChunk.size + body.size()
        return ByteArrayOutputStream(total).apply {
            u16(CHUNK_XML); u16(8); u32(total)
            write(poolChunk)
            write(resourceMapChunk)
            write(body.toByteArray())
        }.toByteArray()
    }

    private fun typed(out: ByteArrayOutputStream, type: Int, data: Int) {
        out.u16(8); out.write(0); out.write(type); out.u32(data)
    }

    private fun stringPoolChunk(strings: List<String>): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { index, value ->
            require(value.length <= 0x7fff) { "Manifest string is too long" }
            offsets[index] = data.size()
            data.u16(value.length)
            data.write(value.toByteArray(Charsets.UTF_16LE))
            data.u16(0)
        }
        while (data.size() % 4 != 0) data.write(0)
        val stringsStart = 28 + 4 * strings.size
        return ByteArrayOutputStream().apply {
            u16(CHUNK_STRING_POOL); u16(28); u32(stringsStart + data.size())
            u32(strings.size); u32(0); u32(0)
            u32(stringsStart); u32(0)
            offsets.forEach { u32(it) }
            write(data.toByteArray())
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.u16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun ByteArrayOutputStream.u32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
