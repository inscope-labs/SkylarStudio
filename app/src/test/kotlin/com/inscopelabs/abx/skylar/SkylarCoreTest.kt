package com.inscopelabs.abx.skylar

import com.inscopelabs.abx.skylar.config.SkylarConfig
import com.inscopelabs.abx.skylar.core.EnvelopeVerifier
import com.inscopelabs.abx.skylar.core.RequestEnvelope
import com.inscopelabs.abx.skylar.mesh.TransportCredential
import com.inscopelabs.abx.skylar.policy.AuthorizationMatrix
import com.inscopelabs.abx.skylar.policy.RoutingTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkylarCoreTest {

    @Test
    fun testAuthorizationMatrix_DefaultDeny() {
        val matrix = AuthorizationMatrix(
            version = "0.5.0",
            matrix = mapOf(
                "caller.alpha" to mapOf(
                    "context.query" to setOf("read"),
                    "action.execute" to setOf("write")
                )
            )
        )

        // Allowed caller and capability
        assertTrue(matrix.isAuthorized("caller.alpha", "context.query", "read"))
        assertTrue(matrix.isAuthorized("caller.alpha", "action.execute", "write"))

        // Missing scope
        assertFalse(matrix.isAuthorized("caller.alpha", "context.query", "admin"))

        // Unauthorized capability
        assertFalse(matrix.isAuthorized("caller.alpha", "storage.access"))

        // Unknown caller (Default-Deny)
        assertFalse(matrix.isAuthorized("caller.unknown", "context.query"))
    }

    @Test
    fun testRoutingTable_ResolutionAndDefaultDeny() {
        val routing = RoutingTable(
            version = "0.5.0",
            routes = mapOf(
                "context.query" to "starlight",
                "storage.access" to "sfm",
                "system.tools" to "xtools"
            )
        )

        assertEquals("starlight", routing.resolveTarget("context.query"))
        assertEquals("sfm", routing.resolveTarget("storage.access"))
        assertEquals("xtools", routing.resolveTarget("system.tools"))

        // Unknown capability
        assertNull(routing.resolveTarget("unregistered.capability"))
    }

    @Test
    fun testEnvelopeVerifier_ValidWorkflowHashAndTemporalWindow() {
        val verifier = EnvelopeVerifier(SkylarConfig.DEFAULT)
        val now = System.currentTimeMillis()
        val expiresAt = now + 60_000L

        val params = mapOf("query" to "user_status")
        val computedHash = verifier.computeWorkflowHash(
            callerId = "caller.test",
            capability = "context.query",
            params = params,
            nonce = "nonce-12345",
            expiresAt = expiresAt
        )

        assertNotNull(computedHash)
        assertTrue(computedHash.isNotEmpty())

        val validEnvelope = RequestEnvelope(
            callerId = "caller.test",
            capability = "context.query",
            params = params,
            nonce = "nonce-12345",
            issuedAt = now,
            expiresAt = expiresAt,
            workflowHash = computedHash,
            signature = "sig_valid_test_signature_123"
        )

        val result = verifier.verify(validEnvelope)
        assertTrue(result.isSuccess)
    }

    @Test
    fun testEnvelopeVerifier_ExpiredEnvelopeRejected() {
        val verifier = EnvelopeVerifier(SkylarConfig(clockSkewToleranceMs = 1000L))
        val now = System.currentTimeMillis()
        val expiredTime = now - 100_000L

        val params = emptyMap<String, Any?>()
        val hash = verifier.computeWorkflowHash("caller.test", "context.query", params, "nonce-expired", expiredTime)

        val expiredEnvelope = RequestEnvelope(
            callerId = "caller.test",
            capability = "context.query",
            params = params,
            nonce = "nonce-expired",
            issuedAt = expiredTime - 60_000L,
            expiresAt = expiredTime,
            workflowHash = hash,
            signature = "sig_valid_test_signature_123"
        )

        val result = verifier.verify(expiredEnvelope)
        assertTrue(result.isError)
    }

    @Test
    fun testTransportCredential_Validity() {
        val now = System.currentTimeMillis()
        val validCred = TransportCredential(
            credentialId = "cred-1",
            authKey = "tskey-auth-mock",
            meshNodeId = "node-1",
            issuedAt = now,
            expiresAt = now + 3600_000L
        )
        assertTrue(validCred.isValid(now))

        val expiredCred = TransportCredential(
            credentialId = "cred-2",
            authKey = "tskey-auth-mock",
            meshNodeId = "node-2",
            issuedAt = now - 7200_000L,
            expiresAt = now - 3600_000L
        )
        assertFalse(expiredCred.isValid(now))

        val revokedCred = validCred.copy(isRevoked = true)
        assertFalse(revokedCred.isValid(now))
    }
}
