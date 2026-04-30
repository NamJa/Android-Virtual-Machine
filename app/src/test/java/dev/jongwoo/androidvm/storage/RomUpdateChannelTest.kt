package dev.jongwoo.androidvm.storage

import java.security.MessageDigest
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RomUpdateChannelTest {
    @Test
    fun manifestPersistsSignatureAndPatchLevel() {
        val original = sampleManifest(patch = 3, signature = "deadbeef", publicKeyId = "key1")
        val parsed = RomImageManifest.fromJson(original.toJson())
        assertEquals(3, parsed.patchLevel)
        assertEquals("deadbeef", parsed.signature)
        assertEquals("key1", parsed.publicKeyId)
    }

    @Test
    fun manifestSignatureFieldsRoundTripNullableBack() {
        val noSig = sampleManifest(patch = 0)
        assertNull(noSig.signature)
        assertNull(noSig.publicKeyId)
        val parsed = RomImageManifest.fromJson(noSig.toJson())
        assertNull(parsed.signature)
        assertNull(parsed.publicKeyId)
    }

    @Test
    fun acceptsLegitimateNewerPatchLevel() {
        val installed = sampleManifest(patch = 1)
        val (candidate, _) = signed(patch = 2)
        val channel = RomUpdateChannel(StubSha256SignatureVerifier(KEY_ID), KEY_ID)
        val verdict = channel.verify(candidate, installed)
        assertTrue(verdict is RomUpdateVerdict.Accepted)
        val accepted = verdict as RomUpdateVerdict.Accepted
        assertEquals(2, accepted.newPatchLevel)
        assertEquals(1, accepted.previousPatchLevel)
    }

    @Test
    fun rejectsForgedSignature() {
        val (candidate, body) = signed(patch = 5)
        // Replace signature with a different valid SHA but for a *different* body.
        val bogus = candidate.copy(
            signature = MessageDigest.getInstance("SHA-256")
                .digest((String(body) + "tampered").toByteArray()).toHex(),
        )
        val channel = RomUpdateChannel(StubSha256SignatureVerifier(KEY_ID), KEY_ID)
        val verdict = channel.verify(bogus, installedManifest = null)
        assertTrue(verdict is RomUpdateVerdict.Rejected)
        assertEquals("signature_mismatch", (verdict as RomUpdateVerdict.Rejected).reason)
    }

    @Test
    fun rejectsUnexpectedPublicKey() {
        val (candidate, _) = signed(patch = 1, publicKeyId = "rotated-key")
        val channel = RomUpdateChannel(StubSha256SignatureVerifier(KEY_ID), KEY_ID)
        val verdict = channel.verify(candidate, installedManifest = null)
        assertTrue(verdict is RomUpdateVerdict.Rejected)
        assertTrue((verdict as RomUpdateVerdict.Rejected).reason.startsWith("unexpected_public_key"))
    }

    @Test
    fun rejectsMissingSignature() {
        val candidate = sampleManifest(patch = 9, signature = null, publicKeyId = KEY_ID)
        val verdict = RomUpdateChannel(StubSha256SignatureVerifier(KEY_ID), KEY_ID)
            .verify(candidate, installedManifest = null)
        assertTrue(verdict is RomUpdateVerdict.Rejected)
        assertEquals("missing_signature", (verdict as RomUpdateVerdict.Rejected).reason)
    }

    @Test
    fun rejectsRollbackOrSamePatchLevel() {
        val installed = sampleManifest(patch = 5)
        val (candidate, _) = signed(patch = 5)
        val channel = RomUpdateChannel(StubSha256SignatureVerifier(KEY_ID), KEY_ID)
        val verdict = channel.verify(candidate, installed)
        assertTrue(verdict is RomUpdateVerdict.Rejected)
        assertTrue((verdict as RomUpdateVerdict.Rejected).reason.startsWith("patch_level_not_newer"))
    }

    @Test
    fun ed25519VerifierAcceptsSignedManifestAndRejectsForgery() {
        val keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val keyId = "ed25519-test"
        val unsigned = sampleManifest(patch = 6, publicKeyId = keyId)
        val body = unsigned.canonicalSigningBody().toByteArray()
        val signature = Signature.getInstance("Ed25519").apply {
            initSign(keyPair.private)
            update(body)
        }.sign().toHex()
        val channel = RomUpdateChannel(Ed25519SignatureVerifier(keyPair.public.encoded), keyId)
        assertTrue(channel.verify(unsigned.copy(signature = signature), sampleManifest(patch = 5)) is RomUpdateVerdict.Accepted)
        assertTrue(channel.verify(unsigned.copy(signature = signature.reversed()), sampleManifest(patch = 5)) is RomUpdateVerdict.Rejected)
    }

    @Test
    fun statusLineContainsAllOfflineMarkers() {
        val line = RomUpdateChannel.statusLine(passed = true, patchLevel = 7)
        listOf(
            "passed=true",
            "signed=true",
            "patch_level=7",
            "consent_gate=on",
            "channel=offline",
            "network_fetch=off",
            "auto_update=off",
            "telemetry=off",
        ).forEach { fragment ->
            assertTrue("missing fragment '$fragment' in $line", line.contains(fragment))
        }
        // Failure line still tags offline channel.
        val failLine = RomUpdateChannel.statusLine(passed = false, patchLevel = 7)
        assertFalse(failLine.contains("passed=true"))
        assertTrue(failLine.contains("channel=offline"))
    }

    private fun signed(
        patch: Int,
        publicKeyId: String = KEY_ID,
    ): Pair<RomImageManifest, ByteArray> {
        val unsigned = sampleManifest(patch = patch)
        val body = unsigned.canonicalSigningBody().toByteArray()
        val signature = MessageDigest.getInstance("SHA-256").digest(body).toHex()
        return unsigned.copy(signature = signature, publicKeyId = publicKeyId) to body
    }

    private fun sampleManifest(
        patch: Int,
        signature: String? = null,
        publicKeyId: String? = null,
    ) = RomImageManifest(
        name = "guest-7.1.2",
        guestVersion = "7.1.2",
        guestArch = "arm64-v8a",
        format = "tar.zst",
        compressedSize = 100L,
        uncompressedSize = 1_000L,
        sha256 = "abc123",
        createdAt = "2026-01-01T00:00:00Z",
        minHostSdk = 26,
        patchLevel = patch,
        signature = signature,
        publicKeyId = publicKeyId,
    )

    private fun ByteArray.toHex(): String {
        val builder = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and 0xFF
            builder.append(HEX[v ushr 4])
            builder.append(HEX[v and 0x0F])
        }
        return builder.toString()
    }

    companion object {
        private const val KEY_ID = "phase-e-default"
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
