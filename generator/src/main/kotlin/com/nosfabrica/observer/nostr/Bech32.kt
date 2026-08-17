package com.nosfabrica.observer.nostr

/**
 * Just enough bech32 to turn an `npub1…` into the hex a filter needs.
 *
 * Quartz does this properly and will own it from Phase 3, when signing arrives
 * and we need the rest of NIP-19 anyway. Until then a CLI that only accepts
 * 64 hex characters is a CLI nobody can paste into, and fifty lines of a
 * fully-specified encoding is a smaller liability than a dependency added for
 * one function.
 */
object Bech32 {
    private const val CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    private val GEN = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)

    class InvalidBech32(
        message: String,
    ) : IllegalArgumentException(message)

    /** Accepts hex or `npub1…`/`nsec1…`-style bech32 and always returns 64-char hex. */
    fun toHexPubkey(input: String): String {
        val s = input.trim()
        if (s.matches(Regex("^[0-9a-fA-F]{64}$"))) return s.lowercase()
        val (hrp, data) = decode(s)
        if (hrp != "npub") throw InvalidBech32("expected an npub, got '$hrp'")
        val bytes = fromWords(data)
        if (bytes.size != 32) throw InvalidBech32("npub decoded to ${bytes.size} bytes, expected 32")
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun decode(bech: String): Pair<String, ByteArray> {
        val s = bech.lowercase()
        if (s != bech && bech.lowercase() != bech && bech.uppercase() != bech) {
            throw InvalidBech32("mixed case")
        }
        val split = s.lastIndexOf('1')
        if (split < 1 || split + 7 > s.length) throw InvalidBech32("no separator")
        val hrp = s.substring(0, split)
        val dataPart = s.substring(split + 1)
        val data =
            ByteArray(dataPart.length) { i ->
                val v = CHARSET.indexOf(dataPart[i])
                if (v < 0) throw InvalidBech32("bad character '${dataPart[i]}'")
                v.toByte()
            }
        if (polymod(expandHrp(hrp) + data.map { it.toInt() }) != 1) throw InvalidBech32("bad checksum")
        return hrp to data.copyOfRange(0, data.size - 6)
    }

    /** 5-bit groups back to 8-bit bytes. The trailing partial group must be zero padding. */
    fun fromWords(words: ByteArray): ByteArray {
        var acc = 0
        var bits = 0
        val out = ArrayList<Byte>(words.size * 5 / 8)
        for (w in words) {
            val v = w.toInt() and 0xff
            if (v shr 5 != 0) throw InvalidBech32("value out of range")
            acc = (acc shl 5) or v
            bits += 5
            while (bits >= 8) {
                bits -= 8
                out.add(((acc shr bits) and 0xff).toByte())
            }
        }
        if (bits >= 5 || ((acc shl (8 - bits)) and 0xff) != 0) throw InvalidBech32("bad padding")
        return out.toByteArray()
    }

    private fun expandHrp(hrp: String): List<Int> = hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 }

    private fun polymod(values: List<Int>): Int {
        var chk = 1
        for (v in values) {
            val top = chk shr 25
            chk = ((chk and 0x1ffffff) shl 5) xor v
            for (i in 0..4) if (((top shr i) and 1) != 0) chk = chk xor GEN[i]
        }
        return chk
    }
}
