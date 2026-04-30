package dev.jongwoo.androidvm.apk

/**
 * Phase D.2 ActivityManager surface. Maps `am start -n <pkg>/<activity>` style requests onto a
 * binder transaction with the guest activity service. The guest implementation will route to the
 * activity binder service registered by Phase C system_server.
 */
interface GuestActivityManager {
    enum class LaunchStatus { STARTED, NOT_FOUND, NOT_LAUNCHABLE, DISABLED, UNAVAILABLE }

    data class LaunchResult(
        val status: LaunchStatus,
        val startedActivity: String?,
        val message: String,
    )

    fun startActivity(
        instanceId: String,
        packageName: String,
        activity: String?,
        intentAction: String = LauncherBootstrapper.ACTION_MAIN,
        categories: List<String> = listOf(LauncherBootstrapper.CATEGORY_LAUNCHER),
    ): LaunchResult
}

/**
 * Test-friendly implementation that delegates to a [PhaseDPmsClient]. Production wires this up
 * against the JNI surface through `VmNativeBridge.launchGuestActivity` once that's implemented.
 */
class PmsBackedActivityManager(
    private val pmsClient: PhaseDPmsClient,
) : GuestActivityManager {
    private val recorded = mutableListOf<Pair<String, String>>()

    val launches: List<Pair<String, String>>
        get() = synchronized(recorded) { recorded.toList() }

    override fun startActivity(
        instanceId: String,
        packageName: String,
        activity: String?,
        intentAction: String,
        categories: List<String>,
    ): GuestActivityManager.LaunchResult {
        val launch = pmsClient.launchActivity(instanceId, packageName, activity)
        if (launch.status == PmsLaunchStatus.LAUNCHED && launch.activity != null) {
            synchronized(recorded) { recorded += packageName to launch.activity }
        }
        val mapped = when (launch.status) {
            PmsLaunchStatus.LAUNCHED -> GuestActivityManager.LaunchStatus.STARTED
            PmsLaunchStatus.NOT_FOUND -> GuestActivityManager.LaunchStatus.NOT_FOUND
            PmsLaunchStatus.NOT_LAUNCHABLE -> GuestActivityManager.LaunchStatus.NOT_LAUNCHABLE
            PmsLaunchStatus.DISABLED -> GuestActivityManager.LaunchStatus.DISABLED
            PmsLaunchStatus.UNAVAILABLE -> GuestActivityManager.LaunchStatus.UNAVAILABLE
        }
        return GuestActivityManager.LaunchResult(
            status = mapped,
            startedActivity = launch.activity,
            message = launch.message,
        )
    }
}
