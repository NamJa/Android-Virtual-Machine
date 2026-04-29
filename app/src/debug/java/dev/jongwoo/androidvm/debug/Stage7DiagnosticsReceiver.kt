package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import dev.jongwoo.androidvm.bridge.Stage7Diagnostics
import java.io.File

class Stage7DiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_STAGE7_DIAGNOSTICS) return

        val workspace = File(context.filesDir, "avm/diagnostics/stage7").also { it.mkdirs() }
        val manifestText = readPackageManifestText(context)

        val diagnostics = Stage7Diagnostics(
            workspaceRoot = workspace,
            manifestText = manifestText,
            regressionProbe = { Stage7RegressionRunner.run(context, TAG) },
            emit = { line -> Log.i(TAG, line) },
        )
        diagnostics.run()
    }

    private fun readPackageManifestText(context: Context): String {
        // We can't read the AndroidManifest.xml directly at runtime, but we can encode the host
        // permissions the package manager sees as a synthetic manifest fragment. Stage 07's
        // forbidden-permission guard runs against this fragment, which is sufficient because
        // a forbidden permission would have to be declared in the manifest to appear here.
        return runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            )
            val permissions = info.requestedPermissions?.joinToString("\n") ?: ""
            "<manifest>$permissions</manifest>"
        }.getOrDefault("<manifest></manifest>")
    }

    companion object {
        private const val TAG = "AVM.Stage7Diag"
        private const val ACTION_RUN_STAGE7_DIAGNOSTICS =
            "dev.jongwoo.androidvm.debug.RUN_STAGE7_DIAGNOSTICS"
    }
}
