package com.inscopelabs.abx.skylar.envelope.crypto

/**
 * Dependency-free Base64 (RFC 4648, standard alphabet, with padding).
 *
 * Implemented without android.util.Base64 or java.util.Base64 so it runs
 * identically in pure JVM environments, tests, and Android runtimes down to API 24.
 */
object Base64Codec {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i < bytes.size) {
            val b0 = bytes[i].toInt() and 0xFF
            val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
            val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0

            sb.append(ALPHABET[b0 ushr 2])
            sb.append(ALPHABET[(b0 shl 4 or (b1 ushr 4)) and 0x3F])
            sb.append(if (i + 1 < bytes.size) ALPHABET[(b1 shl 2 or (b2 ushr 6)) and 0x3F] else '=')
            sb.append(if (i + 2 < bytes.size) ALPHABET[b2 and 0x3F] else '=')
            i += 3
        }
        return sb.toString()
    }

    fun decode(encoded: String): ByteArray {
        val clean = encoded.trim().trimEnd('=')
        val out = ArrayList<Byte>((clean.length * 3) / 4)
        var buffer = 0
        var bitsCollected = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c)
            require(value >= 0) { "Invalid Base64 character: '$c'" }
            buffer = (buffer shl 6) or value
            bitsCollected += 6
            if (bitsCollected >= 8) {
                bitsCollected -= 8
                out.add(((buffer shr bitsCollected) and 0xFF).toByte())
            }
        }
        return out.toByteArray()
    }
}
