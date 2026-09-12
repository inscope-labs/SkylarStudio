package com.inscopelabs.abx.skylar.envelope

import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import com.inscopelabs.abx.skylar.envelope.crypto.InMemoryKeyRegistry
import com.inscopelabs.abx.skylar.envelope.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.envelope.policy.PolicyArtifactReader
import com.inscopelabs.abx.skylar.envelope.policy.RoutingTable
import com.inscopelabs.abx.skylar.envelope.policy.SignedPolicyArtifact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.ECGenParameterSpec

/**
 * Validates Phase 1 criterion V1.4:
 * "Unsigned policy artefacts are rejected"
 */
class PolicyArtifactTest {

    private lateinit var keyRegistry: InMemoryKeyRegistry
    private lateinit var signerPrivateKey: PrivateKey
    private val signerId = "policy-authority-1"

    @Before
    fun setUp() {
        keyRegistry = InMemoryKeyRegistry()
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()
        signerPrivateKey = kp.private
        keyRegistry.register(signerId, kp.public.encoded)
    }

    private fun signPayload(payload: String): String {
        val sig = EcdsaP256SignatureProvider().sign(signerPrivateKey.encoded, payload.toByteArray(Charsets.UTF_8))
        return Base64Codec.encode(sig)
    }

    @Test
    fun testValidSignedPolicyArtifact_Accepted() {
        val payload = "caller.alpha:context.query:read"
        val artifact = SignedPolicyArtifact(
            version = "1.0.0",
            canonicalPayload = payload,
            signerId = signerId,
            signature = signPayload(payload)
        )

        assertTrue("Valid signed artifact must verify", artifact.isValid(keyRegistry))
    }

    @Test
    fun testUnsignedPolicyArtifact_Rejected() {
        // V1.4 requirement: unsigned policy artefacts are rejected
        val payload = "caller.alpha:context.query:read"
        val unsignedArtifact = SignedPolicyArtifact(
            version = "1.0.0",
            canonicalPayload = payload,
            signerId = signerId,
            signature = ""
        )

        assertFalse("Unsigned policy artifact must be rejected", unsignedArtifact.isValid(keyRegistry))
    }

    @Test
    fun testTamperedPolicyPayload_Rejected() {
        val payload = "caller.alpha:context.query:read"
        val artifact = SignedPolicyArtifact(
            version = "1.0.0",
            canonicalPayload = "caller.alpha:context.query:admin", // tampered!
            signerId = signerId,
            signature = signPayload(payload)
        )

        assertFalse("Tampered policy payload must be rejected", artifact.isValid(keyRegistry))
    }

    @Test
    fun testUnregisteredSigner_Rejected() {
        val payload = "caller.alpha:context.query:read"
        val artifact = SignedPolicyArtifact(
            version = "1.0.0",
            canonicalPayload = payload,
            signerId = "unknown-untrusted-signer",
            signature = signPayload(payload)
        )

        assertFalse("Unregistered signer must be rejected", artifact.isValid(keyRegistry))
    }

    @Test
    fun testPolicyArtifactReader_LoadsAuthorizationMatrix() {
        val rawMatrixPayload = "caller.alpha=context.query:read,action.exec:write|caller.beta=context.query:*"
        val artifact = SignedPolicyArtifact(
            version = "1.0.0",
            canonicalPayload = rawMatrixPayload,
            signerId = signerId,
            signature = signPayload(rawMatrixPayload)
        )

        val reader = PolicyArtifactReader(keyRegistry)
        val result = reader.readAuthorizationMatrix(artifact) { payload ->
            // Simple canonical parser demonstration
            payload.split("|").associate { callerBlock ->
                val (cId, capsBlock) = callerBlock.split("=")
                val caps = capsBlock.split(",").associate { capBlock ->
                    val (capName, scope) = capBlock.split(":")
                    capName to setOf(scope)
                }
                cId to caps
            }
        }

        assertTrue(result.isSuccess)
        val matrix = result.getOrNull()
        assertNotNull(matrix)
        assertTrue(matrix!!.isAuthorized("caller.alpha", "context.query", "read"))
        assertFalse(matrix.isAuthorized("caller.alpha", "context.query", "admin"))
        assertTrue(matrix.isAuthorized("caller.beta", "context.query", "any_scope"))
        assertFalse(matrix.isAuthorized("caller.gamma", "context.query")) // default-deny
    }

    @Test
    fun testPolicyArtifactReader_LoadsRoutingTable() {
        val rawRoutePayload = "context.query=starlight|action.exec=sfm|tool.run=xtools"
        val artifact = SignedPolicyArtifact(
            version = "1.0.0",
            canonicalPayload = rawRoutePayload,
            signerId = signerId,
            signature = signPayload(rawRoutePayload)
        )

        val reader = PolicyArtifactReader(keyRegistry)
        val result = reader.readRoutingTable(artifact) { payload ->
            payload.split("|").associate {
                val (cap, target) = it.split("=")
                cap to target
            }
        }

        assertTrue(result.isSuccess)
        val table = result.getOrNull()
        assertNotNull(table)
        assertEquals("starlight", table!!.resolveTarget("context.query"))
        assertEquals("sfm", table.resolveTarget("action.exec"))
        assertEquals("xtools", table.resolveTarget("tool.run"))
        assertNull(table.resolveTarget("unknown.capability")) // default-deny
    }
}
