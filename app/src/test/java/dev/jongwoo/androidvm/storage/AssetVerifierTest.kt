package dev.jongwoo.androidvm.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class AssetVerifierTest {
    @Test
    fun sha256_returnsKnownDigest() {
        val digest = AssetVerifier.sha256("abc".byteInputStream())

        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            digest,
        )
    }
}
