package dev.jongwoo.androidvm.storage

import java.security.MessageDigest
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Phase E.9 ROM signed update channel. Pure-JVM contract that drives offline-only signed import:
 * the host computes a canonical hash of the manifest body, the user-provided signature is
 * verified against the embedded public key for the channel, and the patch level must increase.
 *
 * The channel does NOT call out to a network update server, does NOT poll, does NOT collect
 * telemetry — by design (see Phase E.9 비목표).
 */

/** Off-device verifier: anything that can answer "is this signature legit for this body?" */
fun interface SignatureVerifier {
    fun verify(body: ByteArray, signatureHex: String, publicKeyId: String): Boolean
}

/**
 * Production hook: a stub verifier that returns false unless the signature matches the
 * deterministic SHA-256 fingerprint of the canonical body XOR'd with the public-key id. Used as
 * a placeholder until the real Ed25519 wiring lands.
 */
class StubSha256SignatureVerifier(private val expectedKeyId: String) : SignatureVerifier {
    override fun verify(body: ByteArray, signatureHex: String, publicKeyId: String): Boolean {
        if (publicKeyId != expectedKeyId) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(body)
        val expected = digest.toHex()
        return signatureHex.equals(expected, ignoreCase = true)
    }

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
        private val HEX = "0123456789abcdef".toCharArray()
    }
}

class Ed25519SignatureVerifier(private val encodedPublicKey: ByteArray) : SignatureVerifier {
    override fun verify(body: ByteArray, signatureHex: String, publicKeyId: String): Boolean {
        return runCatching {
            val key = KeyFactory.getInstance("Ed25519")
                .generatePublic(X509EncodedKeySpec(encodedPublicKey))
            val signature = Signature.getInstance("Ed25519")
            signature.initVerify(key)
            signature.update(body)
            signature.verify(signatureHex.hexToBytes())
        }.getOrDefault(false)
    }

    private fun String.hexToBytes(): ByteArray {
        if (length % 2 != 0) return ByteArray(0)
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            val hi = Character.digit(this[i * 2], 16)
            val lo = Character.digit(this[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return ByteArray(0)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}

/** Verdict produced when the user picks a candidate signed manifest via SAF. */
sealed class RomUpdateVerdict {
    abstract val message: String
    abstract val ok: Boolean

    data class Accepted(
        val manifest: RomImageManifest,
        val newPatchLevel: Int,
        val previousPatchLevel: Int,
    ) : RomUpdateVerdict() {
        override val ok: Boolean = true
        override val message: String = "ok"
    }

    data class Rejected(val reason: String) : RomUpdateVerdict() {
        override val ok: Boolean = false
        override val message: String = reason
    }
}

class RomUpdateChannel(
    private val verifier: SignatureVerifier,
    private val expectedPublicKeyId: String,
) {
    fun verify(
        candidateManifest: RomImageManifest,
        installedManifest: RomImageManifest?,
    ): RomUpdateVerdict {
        val signature = candidateManifest.signature
            ?: return RomUpdateVerdict.Rejected("missing_signature")
        val publicKeyId = candidateManifest.publicKeyId
            ?: return RomUpdateVerdict.Rejected("missing_public_key_id")
        if (publicKeyId != expectedPublicKeyId) {
            return RomUpdateVerdict.Rejected("unexpected_public_key:$publicKeyId")
        }
        val body = candidateManifest.canonicalSigningBody().toByteArray(Charsets.UTF_8)
        val signatureOk = verifier.verify(body, signature, publicKeyId)
        if (!signatureOk) {
            return RomUpdateVerdict.Rejected("signature_mismatch")
        }
        val previous = installedManifest?.patchLevel ?: -1
        if (candidateManifest.patchLevel <= previous) {
            return RomUpdateVerdict.Rejected("patch_level_not_newer:${candidateManifest.patchLevel}<=${previous}")
        }
        return RomUpdateVerdict.Accepted(
            manifest = candidateManifest,
            newPatchLevel = candidateManifest.patchLevel,
            previousPatchLevel = previous,
        )
    }

    companion object {
        /** Phase E.9 status line for the diagnostics receiver. */
        fun statusLine(passed: Boolean, patchLevel: Int): String =
            "STAGE_PHASE_E_SECURITY_UPDATE passed=$passed signed=true patch_level=$patchLevel " +
                "consent_gate=on channel=offline network_fetch=off auto_update=off telemetry=off"
    }
}
