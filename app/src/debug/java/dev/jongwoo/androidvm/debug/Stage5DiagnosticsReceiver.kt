package dev.jongwoo.androidvm.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.RomInstaller
import dev.jongwoo.androidvm.vm.VmNativeBridge
import org.json.JSONObject
import kotlin.math.abs

class Stage5DiagnosticsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_STAGE5_DIAGNOSTICS) return

        val config = InstanceStore(context).ensureDefaultConfig()
        val snapshot = RomInstaller(context).snapshot(config.instanceId)
        if (!snapshot.isInstalled) {
            Log.e(TAG, "STAGE5_RESULT passed=false reason=${snapshot.imageState}")
            return
        }

        VmNativeBridge.initHost(
            context.filesDir.absolutePath,
            context.applicationInfo.nativeLibraryDir,
            Build.VERSION.SDK_INT,
        )
        VmNativeBridge.initInstance(config.instanceId, config.toJson())

        val graphicsPassed = runGraphicsDiagnostics(config.instanceId)
        val inputPassed = runInputDiagnostics(config.instanceId)
        val audioPassed = runAudioDiagnostics(config.instanceId)
        val lifecyclePassed = runLifecycleDiagnostics(config.instanceId)
        val passed = graphicsPassed && inputPassed && audioPassed && lifecyclePassed

        Log.i(
            TAG,
            "STAGE5_RESULT passed=$passed graphics=$graphicsPassed input=$inputPassed " +
                "audio=$audioPassed lifecycle=$lifecyclePassed",
        )
    }

    private fun runGraphicsDiagnostics(instanceId: String): Boolean {
        val rotationReset = VmNativeBridge.setFramebufferRotation(instanceId, 0)
        val resizeResult = VmNativeBridge.resizeSurface(instanceId, HOST_WIDTH, HOST_HEIGHT, HOST_DENSITY_DPI)
        val patternResult = VmNativeBridge.writeFramebufferTestPattern(instanceId, 7)
        val fbFd = VmNativeBridge.openGuestPath(instanceId, "/dev/graphics/fb0", true)
        val fbWrite = if (fbFd > 0) {
            VmNativeBridge.writeGuestFile(instanceId, fbFd, "frame=11")
        } else {
            -1
        }
        if (fbFd > 0) VmNativeBridge.closeGuestFile(instanceId, fbFd)
        val stats = JSONObject(VmNativeBridge.getGraphicsStats(instanceId))
        val mappingLeft = stats.optInt("mappingLeft")
        val mappingTop = stats.optInt("mappingTop")
        val mappingWidth = stats.optInt("mappingWidth")
        val mappingHeight = stats.optInt("mappingHeight")

        val passed = rotationReset == 0 &&
            resizeResult == 0 &&
            patternResult == 0 &&
            stats.optInt("framebufferWidth") == GUEST_WIDTH &&
            stats.optInt("framebufferHeight") == GUEST_HEIGHT &&
            stats.optInt("framebufferRotation") == 0 &&
            stats.optInt("orientedWidth") == GUEST_WIDTH &&
            stats.optInt("orientedHeight") == GUEST_HEIGHT &&
            stats.optInt("surfaceWidth") == HOST_WIDTH &&
            stats.optInt("surfaceHeight") == HOST_HEIGHT &&
            mappingLeft == 0 &&
            mappingTop == 0 &&
            mappingWidth == HOST_WIDTH &&
            mappingHeight == HOST_HEIGHT &&
            fbFd > 0 &&
            fbWrite == "frame=11".length &&
            stats.optLong("framebufferFrames") >= 3L &&
            stats.optString("framebufferSource") == "guest_fb0" &&
            stats.optBoolean("dirty")

        Log.i(
            TAG,
            "STAGE5_GRAPHICS_RESULT passed=$passed rotationReset=$rotationReset resize=$resizeResult " +
                "pattern=$patternResult fbFd=$fbFd fbWrite=$fbWrite " +
                "fb=${stats.optInt("framebufferWidth")}x${stats.optInt("framebufferHeight")} " +
                "rotation=${stats.optInt("framebufferRotation")} oriented=${stats.optInt("orientedWidth")}x${stats.optInt("orientedHeight")} " +
                "surface=${stats.optInt("surfaceWidth")}x${stats.optInt("surfaceHeight")} " +
                "mapping=$mappingLeft,$mappingTop ${mappingWidth}x$mappingHeight " +
                "frames=${stats.optLong("framebufferFrames")} source=${stats.optString("framebufferSource")} " +
                "dirty=${stats.optBoolean("dirty")}",
        )
        return passed
    }

    private fun runInputDiagnostics(instanceId: String): Boolean {
        VmNativeBridge.setFramebufferRotation(instanceId, 0)
        VmNativeBridge.resetInputQueue(instanceId)
        val singleDown = VmNativeBridge.sendTouch(
            instanceId,
            MotionEvent.ACTION_DOWN,
            0,
            HOST_WIDTH / 2f,
            HOST_HEIGHT / 2f,
        )
        val singleUp = VmNativeBridge.sendTouch(
            instanceId,
            MotionEvent.ACTION_UP,
            0,
            HOST_WIDTH / 2f,
            HOST_HEIGHT / 2f,
        )
        val touchStats = JSONObject(VmNativeBridge.getInputStats(instanceId))
        val touchPassed = singleDown == 0 &&
            singleUp == 0 &&
            touchStats.optInt("queueSize") == 2 &&
            touchStats.optString("lastType") == "touch" &&
            touchStats.optInt("lastAction") == MotionEvent.ACTION_UP &&
            abs(touchStats.optDouble("lastGuestX") - GUEST_WIDTH / 2.0) <= 1.0 &&
            abs(touchStats.optDouble("lastGuestY") - GUEST_HEIGHT / 2.0) <= 1.0

        VmNativeBridge.resetInputQueue(instanceId)
        val multiPrimary = VmNativeBridge.sendTouch(
            instanceId,
            MotionEvent.ACTION_POINTER_DOWN,
            0,
            HOST_WIDTH * 0.25f,
            HOST_HEIGHT * 0.25f,
        )
        val multiSecondary = VmNativeBridge.sendTouch(
            instanceId,
            MotionEvent.ACTION_POINTER_DOWN,
            1,
            HOST_WIDTH * 0.75f,
            HOST_HEIGHT * 0.75f,
        )
        val multiStats = JSONObject(VmNativeBridge.getInputStats(instanceId))
        val expectedSecondaryX = GUEST_WIDTH * 0.75
        val expectedSecondaryY = GUEST_HEIGHT * 0.75
        val multiPassed = multiPrimary == 0 &&
            multiSecondary == 0 &&
            multiStats.optInt("queueSize") == 2 &&
            multiStats.optInt("lastPointerId") == 1 &&
            abs(multiStats.optDouble("lastGuestX") - expectedSecondaryX) <= 1.0 &&
            abs(multiStats.optDouble("lastGuestY") - expectedSecondaryY) <= 1.0

        val keyDown = VmNativeBridge.sendKey(instanceId, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0)
        val keyUp = VmNativeBridge.sendKey(instanceId, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0)
        val keyStats = JSONObject(VmNativeBridge.getInputStats(instanceId))
        val keyPassed = keyDown == 0 &&
            keyUp == 0 &&
            keyStats.optInt("queueSize") == 4 &&
            keyStats.optString("lastType") == "key" &&
            keyStats.optInt("lastAction") == KeyEvent.ACTION_UP &&
            keyStats.optInt("lastKeyCode") == KeyEvent.KEYCODE_BACK

        val reset = VmNativeBridge.resetInputQueue(instanceId)
        val resetStats = JSONObject(VmNativeBridge.getInputStats(instanceId))
        val resetPassed = reset == 0 && resetStats.optInt("queueSize") == 0 && resetStats.optLong("resets") > 0L
        val passed = touchPassed && multiPassed && keyPassed && resetPassed

        Log.i(
            TAG,
            "STAGE5_INPUT_RESULT passed=$passed touch=$touchPassed multi=$multiPassed key=$keyPassed reset=$resetPassed " +
                "guest=${touchStats.optDouble("lastGuestX")},${touchStats.optDouble("lastGuestY")} " +
                "multiGuest=${multiStats.optDouble("lastGuestX")},${multiStats.optDouble("lastGuestY")} " +
                "queueAfterKey=${keyStats.optInt("queueSize")} resets=${resetStats.optLong("resets")}",
        )
        return passed
    }

    private fun runAudioDiagnostics(instanceId: String): Boolean {
        val generated = VmNativeBridge.generateAudioTestTone(instanceId, AUDIO_SAMPLE_RATE, AUDIO_FRAMES, false)
        val audioFd = VmNativeBridge.openGuestPath(instanceId, "/dev/snd/pcmC0D0p", true)
        val audioPayload = "rate=$AUDIO_SAMPLE_RATE frames=$AUDIO_FRAMES muted=false"
        val audioWrite = if (audioFd > 0) {
            VmNativeBridge.writeGuestFile(instanceId, audioFd, audioPayload)
        } else {
            -1
        }
        if (audioFd > 0) VmNativeBridge.closeGuestFile(instanceId, audioFd)
        val statsAfterPlay = JSONObject(VmNativeBridge.getAudioStats(instanceId))
        val playPassed = generated == AUDIO_FRAMES &&
            audioFd > 0 &&
            audioWrite == audioPayload.length &&
            statsAfterPlay.optInt("sampleRate") == AUDIO_SAMPLE_RATE &&
            statsAfterPlay.optInt("framesGenerated") >= AUDIO_FRAMES * 2 &&
            statsAfterPlay.optInt("channels") == AUDIO_CHANNELS &&
            !statsAfterPlay.optBoolean("muted")

        val mutedFrames = VmNativeBridge.generateAudioTestTone(instanceId, AUDIO_SAMPLE_RATE, AUDIO_FRAMES, true)
        val statsMuted = JSONObject(VmNativeBridge.getAudioStats(instanceId))
        val mutePassed = mutedFrames == AUDIO_FRAMES && statsMuted.optBoolean("muted")

        val unmutedFrames = VmNativeBridge.generateAudioTestTone(instanceId, AUDIO_SAMPLE_RATE, AUDIO_FRAMES, false)
        val statsUnmuted = JSONObject(VmNativeBridge.getAudioStats(instanceId))
        val unmutePassed = unmutedFrames == AUDIO_FRAMES && !statsUnmuted.optBoolean("muted")

        val passed = playPassed && mutePassed && unmutePassed

        Log.i(
            TAG,
            "STAGE5_AUDIO_RESULT passed=$passed play=$playPassed mute=$mutePassed unmute=$unmutePassed " +
                "rate=${statsAfterPlay.optInt("sampleRate")} frames=${statsAfterPlay.optInt("framesGenerated")} " +
                "channels=${statsAfterPlay.optInt("channels")} mutedAfterToggle=${statsMuted.optBoolean("muted")} " +
                "framesAfterUnmute=${statsUnmuted.optInt("framesGenerated")}",
        )
        return passed
    }

    private fun runLifecycleDiagnostics(instanceId: String): Boolean {
        VmNativeBridge.setFramebufferRotation(instanceId, 0)
        val detach = VmNativeBridge.detachSurface(instanceId)
        val statsAfterDetach = JSONObject(VmNativeBridge.getGraphicsStats(instanceId))
        val inputAfterDetach = JSONObject(VmNativeBridge.getInputStats(instanceId))
        val detachPassed = detach == 0 &&
            !statsAfterDetach.optBoolean("surfaceAttached", true) &&
            !statsAfterDetach.optBoolean("renderRunning", true) &&
            statsAfterDetach.optInt("surfaceWidth") == 0 &&
            statsAfterDetach.optInt("surfaceHeight") == 0 &&
            inputAfterDetach.optInt("queueSize") == 0

        val resize = VmNativeBridge.resizeSurface(instanceId, HOST_WIDTH, HOST_HEIGHT, HOST_DENSITY_DPI)
        val statsAfterResize = JSONObject(VmNativeBridge.getGraphicsStats(instanceId))
        val resizePassed = resize == 0 &&
            statsAfterResize.optInt("surfaceWidth") == HOST_WIDTH &&
            statsAfterResize.optInt("surfaceHeight") == HOST_HEIGHT

        val rotateSet = VmNativeBridge.setFramebufferRotation(instanceId, 90)
        val rotateResize = VmNativeBridge.resizeSurface(instanceId, HOST_HEIGHT, HOST_WIDTH, HOST_DENSITY_DPI)
        VmNativeBridge.resetInputQueue(instanceId)
        val rotateTouch = VmNativeBridge.sendTouch(
            instanceId,
            MotionEvent.ACTION_DOWN,
            0,
            HOST_HEIGHT / 2f,
            HOST_WIDTH / 2f,
        )
        val statsAfterRotate = JSONObject(VmNativeBridge.getGraphicsStats(instanceId))
        val inputAfterRotate = JSONObject(VmNativeBridge.getInputStats(instanceId))
        val rotatePassed = rotateResize == 0 &&
            rotateSet == 0 &&
            rotateTouch == 0 &&
            statsAfterRotate.optInt("surfaceWidth") == HOST_HEIGHT &&
            statsAfterRotate.optInt("surfaceHeight") == HOST_WIDTH &&
            statsAfterRotate.optInt("framebufferWidth") == GUEST_WIDTH &&
            statsAfterRotate.optInt("framebufferHeight") == GUEST_HEIGHT &&
            statsAfterRotate.optInt("framebufferRotation") == 90 &&
            statsAfterRotate.optInt("orientedWidth") == GUEST_HEIGHT &&
            statsAfterRotate.optInt("orientedHeight") == GUEST_WIDTH &&
            statsAfterRotate.optInt("mappingWidth") == HOST_HEIGHT &&
            statsAfterRotate.optInt("mappingHeight") == HOST_WIDTH &&
            abs(inputAfterRotate.optDouble("lastGuestX") - GUEST_WIDTH / 2.0) <= 1.0 &&
            abs(inputAfterRotate.optDouble("lastGuestY") - GUEST_HEIGHT / 2.0) <= 1.0

        VmNativeBridge.setFramebufferRotation(instanceId, 0)

        val passed = detachPassed && resizePassed && rotatePassed

        Log.i(
            TAG,
            "STAGE5_LIFECYCLE_RESULT passed=$passed detach=$detachPassed resize=$resizePassed rotate=$rotatePassed " +
                "surfaceAfterDetach=${statsAfterDetach.optInt("surfaceWidth")}x${statsAfterDetach.optInt("surfaceHeight")} " +
                "renderRunningAfterDetach=${statsAfterDetach.optBoolean("renderRunning")} " +
                "surfaceAfterRotate=${statsAfterRotate.optInt("surfaceWidth")}x${statsAfterRotate.optInt("surfaceHeight")} " +
                "rotation=${statsAfterRotate.optInt("framebufferRotation")} " +
                "rotatedGuest=${inputAfterRotate.optDouble("lastGuestX")},${inputAfterRotate.optDouble("lastGuestY")}",
        )
        return passed
    }

    companion object {
        const val ACTION_RUN_STAGE5_DIAGNOSTICS = "dev.jongwoo.androidvm.debug.RUN_STAGE5_DIAGNOSTICS"
        private const val TAG = "AVM.Stage5Diag"
        private const val GUEST_WIDTH = 720
        private const val GUEST_HEIGHT = 1280
        private const val HOST_WIDTH = 1080
        private const val HOST_HEIGHT = 1920
        private const val HOST_DENSITY_DPI = 320
        private const val AUDIO_SAMPLE_RATE = 48_000
        private const val AUDIO_FRAMES = 4_800
        private const val AUDIO_CHANNELS = 2
    }
}
