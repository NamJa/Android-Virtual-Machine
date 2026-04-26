package dev.jongwoo.androidvm.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RomPipelineSnapshotTest {
    private val healthy = RootfsHealthResult(
        rootfsPath = "/rootfs",
        missingRequiredEntries = emptyList(),
        unwritableEntries = emptyList(),
        markerMissing = false,
    )
    private val damaged = healthy.copy(missingRequiredEntries = listOf("system/build.prop"))
    private val manifest = manifest(sha256 = "old")
    private val candidate = candidate(manifest)

    @Test
    fun resolveRomImageState_reportsNotInstalledWhenNoMarkerExists() {
        val state = resolveRomImageState(null, listOf(candidate), damaged)

        assertEquals(RomImageState.NOT_INSTALLED, state)
    }

    @Test
    fun resolveRomImageState_reportsInstalledWhenManifestAndHealthMatch() {
        val state = resolveRomImageState(manifest, listOf(candidate), healthy)

        assertEquals(RomImageState.INSTALLED, state)
    }

    @Test
    fun resolveRomImageState_reportsDamagedWhenRootfsHealthFails() {
        val state = resolveRomImageState(manifest, listOf(candidate), damaged)

        assertEquals(RomImageState.DAMAGED, state)
    }

    @Test
    fun resolveRomImageState_reportsAssetMissingWhenInstalledManifestIsNoLongerBundled() {
        val state = resolveRomImageState(manifest, emptyList(), healthy)

        assertEquals(RomImageState.ASSET_MISSING, state)
    }

    @Test
    fun resolveRomImageState_reportsVersionMismatchWhenBundledManifestChanges() {
        val changedCandidate = candidate(manifest(sha256 = "new"))

        val state = resolveRomImageState(manifest, listOf(changedCandidate), healthy)

        assertEquals(RomImageState.VERSION_MISMATCH, state)
    }

    @Test
    fun snapshotMarksRepairCandidateForInstalledImage() {
        val snapshot = RomPipelineSnapshot(
            instanceId = "vm1",
            candidates = listOf(candidate),
            installedManifest = manifest,
            health = damaged,
            imageState = RomImageState.DAMAGED,
        )

        assertFalse(snapshot.isInstalled)
        assertTrue(snapshot.needsRepair)
        assertSame(candidate, snapshot.repairCandidate)
    }

    @Test
    fun snapshotUsesFirstCandidateWhenNoImageIsInstalled() {
        val snapshot = RomPipelineSnapshot(
            instanceId = "vm1",
            candidates = listOf(candidate),
            installedManifest = null,
            health = damaged,
            imageState = RomImageState.NOT_INSTALLED,
        )

        assertFalse(snapshot.needsRepair)
        assertSame(candidate, snapshot.repairCandidate)
    }

    @Test
    fun snapshotReturnsNoRepairCandidateWhenNothingIsBundled() {
        val snapshot = RomPipelineSnapshot(
            instanceId = "vm1",
            candidates = emptyList(),
            installedManifest = null,
            health = damaged,
            imageState = RomImageState.NOT_INSTALLED,
        )

        assertNull(snapshot.repairCandidate)
    }

    private fun manifest(sha256: String): RomImageManifest = RomImageManifest(
        name = "androidfs_7.1.2_arm64_debug",
        guestVersion = "7.1.2",
        guestArch = "arm64",
        format = "zip",
        compressedSize = 1,
        uncompressedSize = 1,
        sha256 = sha256,
        createdAt = "2024-01-01T00:00:00Z",
        minHostSdk = 26,
    )

    private fun candidate(manifest: RomImageManifest): RomImageCandidate = RomImageCandidate(
        manifest = manifest,
        manifestAssetPath = "guest/${manifest.name}.manifest.json",
        archiveAssetPath = "guest/${manifest.archiveFileName}",
        checksumAssetPath = "guest/${manifest.checksumFileName}",
        archiveExists = true,
        checksumExists = true,
    )
}
