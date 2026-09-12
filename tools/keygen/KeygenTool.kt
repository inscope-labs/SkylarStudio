package com.inscopelabs.abx.skylar.tools.keygen

import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * JVM utility for generating Base64-encoded ECDSA P-256 test keypairs.
 *
 * For development and testing only — never use for production Issuer keys.
 */
object KeygenTool {

    data class KeyPairBase64(
        val identityId: String,
        val privateKeyBase64: String,
        val publicKeyBase64: String
    )

    fun generateKeyPair(identityId: String): KeyPairBase64 {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = kpg.generateKeyPair()

        return KeyPairBase64(
            identityId = identityId,
            privateKeyBase64 = Base64Codec.encode(keyPair.private.encoded),
            publicKeyBase64 = Base64Codec.encode(keyPair.public.encoded)
        )
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val identities = if (args.isNotEmpty()) args else arrayOf("starlight", "sfm", "xtools", "policy-signer")
        println("=== Skylar Test Keypair Generator ===")
        for (id in identities) {
            val kp = generateKeyPair(id)
            println("Identity: ${kp.identityId}")
            println("  Private Key (PKCS#8 Base64): ${kp.privateKeyBase64}")
            println("  Public Key  (X.509 Base64):  ${kp.publicKeyBase64}")
        }
    }
}
