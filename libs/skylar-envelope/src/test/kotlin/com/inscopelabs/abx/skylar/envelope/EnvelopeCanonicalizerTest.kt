package com.inscopelabs.abx.skylar.envelope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class EnvelopeCanonicalizerTest {

    @Test
    fun testParameterKeySorting_IsDeterministic() {
        val params1 = linkedMapOf("zebra" to 1, "apple" to 2, "mango" to 3)
        val params2 = linkedMapOf("apple" to 2, "mango" to 3, "zebra" to 1)

        val hash1 = EnvelopeCanonicalizer.computeWorkflowHash(
            envelopeVersion = 1,
            callerId = "caller",
            capability = "cap",
            params = params1,
            nonce = "nonce",
            issuedAt = 1000L,
            expiresAt = 2000L,
            scope = null
        )

        val hash2 = EnvelopeCanonicalizer.computeWorkflowHash(
            envelopeVersion = 1,
            callerId = "caller",
            capability = "cap",
            params = params2,
            nonce = "nonce",
            issuedAt = 1000L,
            expiresAt = 2000L,
            scope = null
        )

        assertEquals(hash1, hash2)
    }

    @Test
    fun testDelimiterEscaping() {
        // Values containing delimiters '&', ',', ':', '\'
        val params = mapOf(
            "complex" to "foo&bar=1,baz:2\\end",
            "nested" to mapOf("sub&key" to "sub:val")
        )

        val bytes = EnvelopeCanonicalizer.canonicalBytes(
            envelopeVersion = 1,
            callerId = "caller&evil",
            capability = "cap:action",
            params = params,
            nonce = "n,1",
            issuedAt = 1000L,
            expiresAt = 2000L,
            scope = "read&write"
        )

        val canonicalString = String(bytes, Charsets.UTF_8)
        assertNotEquals(-1, canonicalString.indexOf("\\&"))
        assertNotEquals(-1, canonicalString.indexOf("\\:"))
        assertNotEquals(-1, canonicalString.indexOf("\\,"))
    }

    @Test
    fun testNestedStructuresAndNulls() {
        val params = mapOf(
            "list" to listOf("a", "b", 3, null),
            "nestedMap" to mapOf("k1" to null, "k2" to true)
        )

        val hash = EnvelopeCanonicalizer.computeWorkflowHash(
            envelopeVersion = 1,
            callerId = "c",
            capability = "cap",
            params = params,
            nonce = "n",
            issuedAt = 100L,
            expiresAt = 200L,
            scope = null
        )

        assertEquals(64, hash.length) // 64 hex chars for SHA-256
    }
}
