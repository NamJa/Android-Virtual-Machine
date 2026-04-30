package dev.jongwoo.androidvm.storage

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotManagerTest {
    private val tempDirs = mutableListOf<File>()

    @After
    fun tearDown() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    @Test
    fun resolveForRead_prefersOverlayThenBaseAndRespectsWhiteout() {
        val paths = layered("resolve-read")
        File(paths.base, "system/foo.txt").apply { parentFile?.mkdirs(); writeText("base") }
        File(paths.overlay, "system/foo.txt").apply { parentFile?.mkdirs(); writeText("overlay") }
        assertEquals(LayeredPathResolver.Source.OVERLAY,
            LayeredPathResolver.resolveForRead(paths, "system/foo.txt"))
        File(paths.overlay, "system/foo.txt").delete()
        assertEquals(LayeredPathResolver.Source.BASE,
            LayeredPathResolver.resolveForRead(paths, "system/foo.txt"))
        // Whiteout hides the base file.
        LayeredPathResolver.markDeleted(paths, "system/foo.txt")
        assertEquals(LayeredPathResolver.Source.WHITEOUT,
            LayeredPathResolver.resolveForRead(paths, "system/foo.txt"))
        assertEquals(LayeredPathResolver.Source.NOT_FOUND,
            LayeredPathResolver.resolveForRead(paths, "missing.txt"))
    }

    @Test
    fun resolveForWrite_copiesBaseIntoOverlayWithoutMutatingBase() {
        val paths = layered("resolve-write")
        val baseFile = File(paths.base, "system/data.bin").apply {
            parentFile?.mkdirs(); writeText("base-content")
        }
        val target = LayeredPathResolver.resolveForWrite(paths, "system/data.bin")
        assertTrue(target.exists())
        assertEquals("base-content", target.readText())
        // Base must not have been touched.
        assertEquals("base-content", baseFile.readText())
        // Subsequent writes operate on the overlay copy.
        target.writeText("changed")
        assertEquals("changed", File(paths.overlay, "system/data.bin").readText())
        assertEquals("base-content", baseFile.readText())
    }

    @Test
    fun resolveForWrite_clearsExistingWhiteout() {
        val paths = layered("resolve-undelete")
        File(paths.base, "x.txt").writeText("from-base")
        LayeredPathResolver.markDeleted(paths, "x.txt")
        LayeredPathResolver.resolveForWrite(paths, "x.txt").writeText("new")
        assertEquals(LayeredPathResolver.Source.OVERLAY,
            LayeredPathResolver.resolveForRead(paths, "x.txt"))
    }

    @Test
    fun snapshot_createPersistsMetadataAndAllowsRollback() {
        val paths = layered("snap-create")
        File(paths.overlay, "logs/app.log").apply { parentFile?.mkdirs(); writeText("v1") }
        val mgr = SnapshotManager(paths, clock = { 1_700_000_000_000L })
        val snap = mgr.create("first")
        assertEquals("first", snap.id)
        assertEquals(1, snap.fileCount)
        assertTrue(snap.byteCount > 0)
        // Modify overlay then rollback.
        File(paths.overlay, "logs/app.log").writeText("v2-bigger")
        mgr.rollback("first")
        assertEquals("v1", File(paths.overlay, "logs/app.log").readText())
    }

    @Test
    fun snapshot_listAndDeleteReclaimDisk() {
        val paths = layered("snap-list")
        File(paths.overlay, "data.txt").writeText("payload")
        val mgr = SnapshotManager(paths)
        mgr.create("a")
        mgr.create("b", description = "checkpoint")
        val snapshots = mgr.list()
        assertEquals(setOf("a", "b"), snapshots.map { it.id }.toSet())
        assertTrue(snapshots.any { it.description == "checkpoint" })
        assertTrue(mgr.delete("a"))
        assertFalse(File(paths.snapshotsDir, "a").exists())
        assertEquals(listOf("b"), mgr.list().map { it.id })
    }

    @Test
    fun rollback_restoresPriorWhiteoutDeletions() {
        val paths = layered("snap-whiteout")
        File(paths.base, "shared.txt").writeText("baseline")
        File(paths.overlay, "shared.txt").writeText("overlaid")
        val mgr = SnapshotManager(paths)
        mgr.create("checkpoint")
        // After snapshot, mark file deleted.
        LayeredPathResolver.markDeleted(paths, "shared.txt")
        assertEquals(LayeredPathResolver.Source.WHITEOUT,
            LayeredPathResolver.resolveForRead(paths, "shared.txt"))
        mgr.rollback("checkpoint")
        // Whiteout should be gone after rollback (overlay was wiped + restored from snapshot).
        assertEquals(LayeredPathResolver.Source.OVERLAY,
            LayeredPathResolver.resolveForRead(paths, "shared.txt"))
        assertEquals("overlaid", File(paths.overlay, "shared.txt").readText())
    }

    @Test
    fun snapshotManager_layeredPathsForInstance() {
        val instanceRoot = Files.createTempDirectory("layered").toFile().also { tempDirs += it }
        val configDir = File(instanceRoot, "config")
        val rootfsDir = File(instanceRoot, "rootfs")
        val paths = InstancePaths(
            id = "vm1",
            root = instanceRoot,
            configDir = configDir,
            rootfsDir = rootfsDir,
            dataDir = File(rootfsDir, "data"),
            cacheDir = File(rootfsDir, "cache"),
            logsDir = File(instanceRoot, "logs"),
            runtimeDir = File(instanceRoot, "runtime"),
            sharedDir = File(instanceRoot, "shared"),
            stagingDir = File(instanceRoot, "staging"),
            exportDir = File(instanceRoot, "export"),
            configFile = File(configDir, "vm_config.json"),
            imageManifestFile = File(configDir, "image_manifest.json"),
        )
        paths.create()
        val layered = LayeredRootfsPaths.forInstance(paths)
        assertEquals(File(instanceRoot, "rootfs.base"), layered.base)
        assertEquals(File(instanceRoot, "rootfs.overlay"), layered.overlay)
        assertEquals(File(instanceRoot, "snapshots"), layered.snapshotsDir)
        layered.ensure()
        assertTrue(layered.base.isDirectory)
        assertTrue(layered.overlay.isDirectory)
        assertTrue(layered.snapshotsDir.isDirectory)
        assertNotNull(layered.toString())
    }

    private fun layered(prefix: String): LayeredRootfsPaths {
        val root = Files.createTempDirectory(prefix).toFile().also { tempDirs += it }
        val paths = LayeredRootfsPaths(
            base = File(root, "base"),
            overlay = File(root, "overlay"),
            snapshotsDir = File(root, "snapshots"),
        )
        paths.ensure()
        return paths
    }
}
