package dev.jongwoo.androidvm.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class RomImageManifestTest {
    @Test
    fun fromJson_parsesManifestAndDerivesAssetNames() {
        val manifest = RomImageManifest.fromJson(
            """
            {
              "name": "androidfs_7.1.2_arm64",
              "guestVersion": "7.1.2",
              "guestArch": "arm64",
              "format": "tar.zst",
              "compressedSize": 123,
              "uncompressedSize": 456,
              "sha256": "abc123",
              "createdAt": "2024-01-01T00:00:00Z",
              "minHostSdk": 26
            }
            """.trimIndent(),
        )

        assertEquals("androidfs_7.1.2_arm64", manifest.name)
        assertEquals("7.1.2", manifest.guestVersion)
        assertEquals("arm64", manifest.guestArch)
        assertEquals("tar.zst", manifest.format)
        assertEquals(123L, manifest.compressedSize)
        assertEquals(456L, manifest.uncompressedSize)
        assertEquals("abc123", manifest.sha256)
        assertEquals("androidfs_7.1.2_arm64.tar.zst", manifest.archiveFileName)
        assertEquals("androidfs_7.1.2_arm64.sha256", manifest.checksumFileName)
    }

    @Test
    fun toJson_roundTripsThroughParser() {
        val original = RomImageManifest(
            name = "androidfs_7.1.2_arm64_debug",
            guestVersion = "7.1.2",
            guestArch = "arm64",
            format = "zip",
            compressedSize = 1587,
            uncompressedSize = 327,
            sha256 = "2641d5226fe923f4a0266b0911da5188cedc86c480044daefe0197fa1eca5641",
            createdAt = "2024-01-01T00:00:00Z",
            minHostSdk = 26,
        )

        val parsed = RomImageManifest.fromJson(original.toJson())

        assertEquals(original, parsed)
    }
}
