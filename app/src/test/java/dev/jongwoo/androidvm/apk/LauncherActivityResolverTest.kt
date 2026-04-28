package dev.jongwoo.androidvm.apk

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherActivityResolverTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanUp() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun resolve_findsActivityWithMainAndLauncherIntent() {
        val resolver = LauncherActivityResolver()
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", "com.example.alpha"))
            .start("application")
            .start("activity", AxmlBuilder.Attr.string("name", ".MainActivity"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .element("category", AxmlBuilder.Attr.string("name", "android.intent.category.LAUNCHER"))
            .end("intent-filter")
            .end("activity")
            .end("application")
            .end("manifest")
            .build()

        val launcher = resolver.resolveFromManifestBytes(manifest)

        assertEquals("com.example.alpha.MainActivity", launcher)
    }

    @Test
    fun resolve_returnsNullWhenNoActivityHasLauncherCategory() {
        val resolver = LauncherActivityResolver()
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", "com.example.beta"))
            .start("application")
            .start("activity", AxmlBuilder.Attr.string("name", ".SettingsActivity"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .end("intent-filter")
            .end("activity")
            .end("application")
            .end("manifest")
            .build()

        val launcher = resolver.resolveFromManifestBytes(manifest)

        assertNull(launcher)
    }

    @Test
    fun resolve_picksFirstLaunchableActivity() {
        val resolver = LauncherActivityResolver()
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", "com.example.gamma"))
            .start("application")
            .start("activity", AxmlBuilder.Attr.string("name", "com.example.gamma.FirstActivity"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .element("category", AxmlBuilder.Attr.string("name", "android.intent.category.LAUNCHER"))
            .end("intent-filter")
            .end("activity")
            .start("activity", AxmlBuilder.Attr.string("name", ".SecondActivity"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .element("category", AxmlBuilder.Attr.string("name", "android.intent.category.LAUNCHER"))
            .end("intent-filter")
            .end("activity")
            .end("application")
            .end("manifest")
            .build()

        val launcher = resolver.resolveFromManifestBytes(manifest)

        assertEquals("com.example.gamma.FirstActivity", launcher)
    }

    @Test
    fun resolve_qualifiesShortActivityNameWithPackage() {
        val resolver = LauncherActivityResolver()
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", "com.example.delta"))
            .start("application")
            .start("activity", AxmlBuilder.Attr.string("name", "MainActivity"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .element("category", AxmlBuilder.Attr.string("name", "android.intent.category.LAUNCHER"))
            .end("intent-filter")
            .end("activity")
            .end("application")
            .end("manifest")
            .build()

        val launcher = resolver.resolveFromManifestBytes(manifest)

        assertEquals("com.example.delta.MainActivity", launcher)
    }

    @Test
    fun resolve_readsAxmlFromZipApk() {
        val resolver = LauncherActivityResolver()
        val manifest = AxmlBuilder()
            .start("manifest", AxmlBuilder.Attr.string("package", "com.example.epsilon"))
            .start("application")
            .start("activity", AxmlBuilder.Attr.string("name", ".Launcher"))
            .start("intent-filter")
            .element("action", AxmlBuilder.Attr.string("name", "android.intent.action.MAIN"))
            .element("category", AxmlBuilder.Attr.string("name", "android.intent.category.LAUNCHER"))
            .end("intent-filter")
            .end("activity")
            .end("application")
            .end("manifest")
            .build()
        val apk = tempApk("epsilon.apk", manifest)

        val launcher = resolver.resolve(apk)

        assertEquals("com.example.epsilon.Launcher", launcher)
    }

    private fun tempApk(name: String, manifestBytes: ByteArray): File {
        val dir = Files.createTempDirectory("launcher-resolver").toFile().also { tempDirs += it }
        val file = File(dir, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml"))
            zip.write(manifestBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            zip.write(ByteArray(8))
            zip.closeEntry()
        }
        // Touch ByteArrayOutputStream import once to avoid unused warning surfacing
        ByteArrayOutputStream().close()
        return file
    }
}
