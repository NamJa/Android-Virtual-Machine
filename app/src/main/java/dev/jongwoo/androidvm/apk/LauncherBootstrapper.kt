package dev.jongwoo.androidvm.apk

import dev.jongwoo.androidvm.storage.InstancePaths
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject

/**
 * Phase D.2 launcher bootstrap.
 *
 * Auto-installs a "minimal" launcher APK on first boot using [PmsInstallCoordinator] and routes
 * the equivalent of `am start -n <pkg>/<activity>` through [GuestActivityManager]. Once an
 * instance has booted the launcher at least once, subsequent boots only re-launch.
 *
 * The launcher fixture itself is provided as a staged file (see [LauncherFixture]); production
 * builds rely on the debug fixture script, tests pass synthesized bytes.
 */
class LauncherBootstrapper(
    private val coordinator: PmsInstallCoordinator,
    private val activityManager: GuestActivityManager,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    data class LauncherBootResult(
        val installed: Boolean,
        val alreadyInstalled: Boolean,
        val started: Boolean,
        val packageName: String,
        val activity: String?,
        val message: String,
    ) {
        val passed: Boolean get() = (installed || alreadyInstalled) && started
    }

    fun bootstrap(
        instancePaths: InstancePaths,
        fixture: LauncherFixture,
    ): LauncherBootResult {
        val pmsList = runCatching { coordinator.syncFromGuest(instancePaths) }.getOrNull()
        val alreadyInstalled = pmsList?.find(fixture.packageName) != null
        val installed: Boolean
        if (alreadyInstalled) {
            installed = false
        } else {
            val staged = fixture.materialize(instancePaths.stagingDir, clock)
            val outcome = coordinator.install(
                instancePaths = instancePaths,
                stagedApk = staged,
                sourceName = fixture.sourceName,
            )
            installed = outcome.passed
            if (!installed) {
                writeMarker(instancePaths, fixture, success = false, message = outcome.pmsResult.message)
                return LauncherBootResult(
                    installed = false,
                    alreadyInstalled = false,
                    started = false,
                    packageName = fixture.packageName,
                    activity = fixture.launcherActivity,
                    message = "launcher_install_failed:${outcome.pmsResult.message}",
                )
            }
        }
        val launch = activityManager.startActivity(
            instanceId = instancePaths.id,
            packageName = fixture.packageName,
            activity = fixture.launcherActivity,
            intentAction = ACTION_MAIN,
            categories = listOf(CATEGORY_HOME, CATEGORY_LAUNCHER),
        )
        val started = launch.status == GuestActivityManager.LaunchStatus.STARTED
        writeMarker(instancePaths, fixture, success = started, message = launch.message)
        return LauncherBootResult(
            installed = installed,
            alreadyInstalled = alreadyInstalled,
            started = started,
            packageName = fixture.packageName,
            activity = launch.startedActivity ?: fixture.launcherActivity,
            message = launch.message,
        )
    }

    private fun writeMarker(
        instancePaths: InstancePaths,
        fixture: LauncherFixture,
        success: Boolean,
        message: String,
    ) {
        val marker = launcherBootMarker(instancePaths)
        runCatching {
            marker.parentFile?.mkdirs()
            marker.writeText(
                JSONObject()
                    .put("packageName", fixture.packageName)
                    .put("activity", fixture.launcherActivity ?: JSONObject.NULL)
                    .put("success", success)
                    .put("at", formatTimestampUtc(clock()))
                    .put("message", message)
                    .toString(2),
            )
        }
    }

    private fun formatTimestampUtc(epochMillis: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(epochMillis))
    }

    companion object {
        const val ACTION_MAIN = "android.intent.action.MAIN"
        const val CATEGORY_HOME = "android.intent.category.HOME"
        const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
        const val DEFAULT_MINIMAL_PACKAGE = "com.android.launcher.minimal"

        fun launcherBootMarker(instancePaths: InstancePaths): File =
            File(instancePaths.runtimeDir, "launcher-boot.json")
    }
}

/**
 * The launcher fixture description. The bytes are produced lazily so unit tests can hand a tiny
 * synthetic zip while production swaps in the AOSP minimal launcher.
 */
data class LauncherFixture(
    val packageName: String,
    val launcherActivity: String?,
    val sourceName: String,
    val provideBytes: () -> ByteArray,
) {
    fun materialize(stagingDir: File, clock: () -> Long): File {
        stagingDir.mkdirs()
        val out = File(stagingDir, "launcher_${clock()}_${packageName}.apk")
        out.writeBytes(provideBytes())
        return out
    }

    companion object {
        fun minimal(
            packageName: String = LauncherBootstrapper.DEFAULT_MINIMAL_PACKAGE,
            activity: String = "$packageName.MinimalLauncherActivity",
            bytes: () -> ByteArray,
        ): LauncherFixture = LauncherFixture(
            packageName = packageName,
            launcherActivity = activity,
            sourceName = "minimal-launcher.apk",
            provideBytes = bytes,
        )
    }
}
