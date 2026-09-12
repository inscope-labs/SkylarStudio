package com.inscopelabs.abx.skylar.envelope

import com.inscopelabs.abx.skylar.envelope.crypto.Base64Codec
import com.inscopelabs.abx.skylar.envelope.crypto.EcdsaP256SignatureProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class SignatureProviderTest {

    private val provider = EcdsaP256SignatureProvider()

    @Test
    fun testSignAndVerify_Valid() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        val message = "Canonical payload for Skylar security contract".toByteArray(Charsets.UTF_8)
        val sig = provider.sign(kp.private.encoded, message)

        assertTrue(provider.verify(kp.public.encoded, message, sig))
    }

    @Test
    fun testVerify_TamperedMessageFails() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        val message = "Legitimate request".toByteArray(Charsets.UTF_8)
        val sig = provider.sign(kp.private.encoded, message)

        val tamperedMessage = "Tampered request".toByteArray(Charsets.UTF_8)
        assertFalse(provider.verify(kp.public.encoded, tamperedMessage, sig))
    }

    @Test
    fun testVerify_WrongKeyFails() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp1 = kpg.generateKeyPair()
        val kp2 = kpg.generateKeyPair()

        val message = "Message".toByteArray(Charsets.UTF_8)
        val sig = provider.sign(kp1.private.encoded, message)

        assertFalse(provider.verify(kp2.public.encoded, message, sig))
    }

    @Test
    fun testVerify_CorruptedSignatureFailsSafe() {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"))
        val kp = kpg.generateKeyPair()

        val message = "Message".toByteArray(Charsets.UTF_8)
        val badSig = ByteArray(32) { 0xFF.toByte() }

        // Must return false, never throw
        assertFalse(provider.verify(kp.public.encoded, message, badSig))
    }

    @Test
    fun testBase64Codec_RoundTrip() {
        val original = "Test string with special chars: !@#$%^&*()_+~`|}{[]:;?><,./".toByteArray(Charsets.UTF_8)
        val encoded = Base64Codec.encode(original)
        val decoded = Base64Codec.decode(encoded)
        assertEquals(String(original, Charsets.UTF_8), String(decoded, Charsets.UTF_8))
    }

    @Test
    fun testBase64Codec_EmptyAndPadded() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        assertEquals(0, Base64Codec.decode("").size)

        val oneByte = byteArrayOf(65) // "A" -> "QQ=="
        val enc1 = Base64Codec.encode(oneByte)
        assertTrue(enc1.endsWith("=="))
        assertEquals(1, Base64Codec.decode(enc1).size)

        val twoBytes = byteArrayOf(65, 66) // "AB" -> "QUI="
        val enc2 = Base64Codec.encode(twoBytes)
        assertTrue(enc2.endsWith("="))
        assertEquals(2, Base64Codec.decode(enc2).size)
    }
}
