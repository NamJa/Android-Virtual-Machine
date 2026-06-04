package dev.jongwoo.androidvm.storage

import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RomSignaturePolicyTest {
    private val keyId = "avm-key-1"
    private val keyPair: KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey: ByteArray = keyPair.public.encoded

    private fun manifest(patchLevel: Int = 0): RomImageManifest = RomImageManifest(
        name = "guest",
        guestVersion = "7.1.2",
        guestArch = "arm64",
        format = "tar.zst",
        compressedSize = 100,
        uncompressedSize = 200,
        sha256 = "abc",
        createdAt = "2026-01-01T00:00:00Z",
        minHostSdk = 26,
        patchLevel = patchLevel,
    )

    private fun sign(m: RomImageManifest): RomImageManifest {
        val body = m.canonicalSigningBody().toByteArray(Charsets.UTF_8)
        val s = Signature.getInstance("Ed25519")
        s.initSign(keyPair.private)
        s.update(body)
        val hex = s.sign().joinToString("") { "%02x".format(it) }
        return m.copy(signature = hex, publicKeyId = keyId)
    }

    @Test
    fun bundledDevAcceptsUnsigned() {
        val verdict = RomSignaturePolicy.bundledDev().gate(manifest(), installedManifest = null)
        assertTrue(verdict.message, verdict.ok)
    }

    @Test
    fun importRejectsUnsigned() {
        val verdict = RomSignaturePolicy.ed25519Import(publicKey, keyId).gate(manifest(), null)
        assertFalse(verdict.ok)
        assertEquals("unsigned_image_rejected", verdict.message)
    }

    @Test
    fun importAcceptsValidEd25519Signature() {
        val signed = sign(manifest(patchLevel = 1))
        val verdict = RomSignaturePolicy.ed25519Import(publicKey, keyId).gate(signed, installedManifest = null)
        assertTrue(verdict.message, verdict.ok)
    }

    @Test
    fun importRejectsTamperedSignature() {
        val signed = sign(manifest(patchLevel = 1))
        // Flip the manifest body after signing: sha256 changes -> signature no longer matches.
        val tampered = signed.copy(sha256 = "deadbeef")
        val verdict = RomSignaturePolicy.ed25519Import(publicKey, keyId).gate(tampered, null)
        assertFalse(verdict.ok)
        assertEquals("signature_mismatch", verdict.message)
    }

    @Test
    fun signedImageWithoutTrustAnchorRejected() {
        val signed = sign(manifest(patchLevel = 1))
        val verdict = RomSignaturePolicy.bundledDev().gate(signed, null)
        assertFalse(verdict.ok)
        assertEquals("signed_image_without_trust_anchor", verdict.message)
    }

    @Test
    fun wrongKeyIdRejected() {
        val signed = sign(manifest(patchLevel = 1)).copy(publicKeyId = "other-key")
        val verdict = RomSignaturePolicy.ed25519Import(publicKey, keyId).gate(signed, null)
        assertFalse(verdict.ok)
        assertTrue(verdict.message.startsWith("unexpected_public_key"))
    }

    @Test
    fun nonMonotonicPatchLevelRejected() {
        val signed = sign(manifest(patchLevel = 1))
        val installed = manifest(patchLevel = 1) // same level -> not newer
        val verdict = RomSignaturePolicy.ed25519Import(publicKey, keyId).gate(signed, installed)
        assertFalse(verdict.ok)
        assertTrue(verdict.message.startsWith("patch_level_not_newer"))
    }
}
