package dev.jongwoo.androidvm.apk

import java.io.File
import java.util.zip.ZipFile

/** Resolves the fully-qualified launcher activity class name from an APK. */
class LauncherActivityResolver {

    fun resolve(apkFile: File): String? {
        if (!apkFile.exists()) return null
        return runCatching {
            ZipFile(apkFile).use { zip ->
                val entry = zip.getEntry(MANIFEST_ENTRY) ?: return@use null
                val bytes = zip.getInputStream(entry).use { it.readBytes() }
                resolveFromManifestBytes(bytes)
            }
        }.getOrNull()
    }

    fun resolveFromManifestBytes(bytes: ByteArray): String? {
        val parser = BinaryAxmlParser(bytes)
        val visitor = ManifestVisitor()
        parser.accept(visitor)
        return visitor.launcherActivity()
    }

    private class ManifestVisitor : BinaryAxmlParser.Visitor {
        private val stack = ArrayDeque<String>()
        private var manifestPackage: String? = null
        private var currentActivity: String? = null
        private var hasMain = false
        private var hasLauncher = false
        private var resolved: String? = null

        override fun onStartElement(name: String, attributes: List<BinaryAxmlParser.Attribute>) {
            stack.addLast(name)
            when (name) {
                "manifest" -> {
                    manifestPackage = attributes.firstOrNull { it.name == "package" }?.stringValue
                }
                "activity", "activity-alias" -> {
                    currentActivity = attributes.firstOrNull { it.name == "name" }?.stringValue
                    hasMain = false
                    hasLauncher = false
                }
                "intent-filter" -> {
                    if (currentActivity != null) {
                        hasMain = false
                        hasLauncher = false
                    }
                }
                "action" -> {
                    val actionName = attributes.firstOrNull { it.name == "name" }?.stringValue
                    if (actionName == ACTION_MAIN && currentActivity != null) hasMain = true
                }
                "category" -> {
                    val categoryName = attributes.firstOrNull { it.name == "name" }?.stringValue
                    if (categoryName == CATEGORY_LAUNCHER && currentActivity != null) hasLauncher = true
                }
            }
        }

        override fun onEndElement(name: String) {
            when (name) {
                "intent-filter" -> {
                    if (resolved == null && currentActivity != null && hasMain && hasLauncher) {
                        resolved = qualify(manifestPackage, currentActivity)
                    }
                }
                "activity", "activity-alias" -> {
                    currentActivity = null
                }
            }
            if (stack.isNotEmpty() && stack.last() == name) stack.removeLast()
        }

        fun launcherActivity(): String? = resolved

        private fun qualify(pkg: String?, activity: String?): String? {
            if (activity.isNullOrBlank()) return null
            return when {
                pkg.isNullOrBlank() -> activity
                activity.startsWith(".") -> "$pkg$activity"
                !activity.contains('.') -> "$pkg.$activity"
                else -> activity
            }
        }
    }

    companion object {
        private const val MANIFEST_ENTRY = "AndroidManifest.xml"
        private const val ACTION_MAIN = "android.intent.action.MAIN"
        private const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    }
}
