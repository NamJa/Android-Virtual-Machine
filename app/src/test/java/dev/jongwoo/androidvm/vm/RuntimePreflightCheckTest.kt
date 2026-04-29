package dev.jongwoo.androidvm.vm

import dev.jongwoo.androidvm.storage.InstanceStore
import dev.jongwoo.androidvm.storage.PathLayout
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimePreflightCheckTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun resolveConfigUsesRequestedInstanceIdInsteadOfDefault() {
        val layout = PathLayout.forRoot(tempDir("preflight-avm"))
        val store = InstanceStore(layout)

        val config = RuntimePreflightCheck.resolveConfig(store, "vm-phaseA")

        assertEquals("vm-phaseA", config.instanceId)
        assertTrue(config.paths.rootfsPath.contains("/instances/vm-phaseA/rootfs"))
        assertFalse(config.paths.rootfsPath.contains("/instances/${VmConfig.DEFAULT_INSTANCE_ID}/"))
    }

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
}
