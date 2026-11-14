package com.nosfabrica.observer.service.keys

import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * One Fernet key, as Python's `cryptography.fernet.Fernet` reads it: 32 bytes of URL-safe base64,
 * the first 16 signing (HMAC-SHA256), the last 16 encrypting (AES-128-CBC, PKCS7). A token is
 * `0x80 | timestamp (8) | IV (16) | ciphertext | HMAC (32)`, URL-safe base64.
 *
 * The TTL is never checked: brainstorm_server encrypts with none, and its own decrypt passes none.
 * [toString] says nothing of the key.
 */
class Fernet(
    key: String,
) {
    private val signing: ByteArray
    private val encryption: ByteArray

    init {
        val raw =
            try {
                Base64.getUrlDecoder().decode(urlSafe(key))
            } catch (e: IllegalArgumentException) {
                // Not the key itself in the message: it may be most of one.
                throw IllegalArgumentException("not a Fernet key: not URL-safe base64")
            }
        require(raw.size == KEY_BYTES) { "not a Fernet key: ${raw.size} bytes, not $KEY_BYTES" }
        signing = raw.copyOfRange(0, 16)
        encryption = raw.copyOfRange(16, 32)
        raw.fill(0)
    }

    /**
     * The plaintext of [token], or null when this key doesn't open it: not a token, another
     * key's, or tampered with. The HMAC is checked, in constant time, before anything is
     * decrypted, so a forged token never reaches the cipher (no padding oracle).
     */
    fun decrypt(token: String): ByteArray? {
        val data =
            try {
                Base64.getUrlDecoder().decode(urlSafe(token))
            } catch (e: IllegalArgumentException) {
                return null
            }
        if (data.size < MIN_TOKEN_BYTES || data[0] != VERSION) return null
        val signedEnd = data.size - HMAC_BYTES
        val expected = hmac(data, signedEnd)
        val given = data.copyOfRange(signedEnd, data.size)
        if (!MessageDigest.isEqual(expected, given)) return null
        val ciphertextLength = signedEnd - CIPHERTEXT_START
        if (ciphertextLength % BLOCK != 0) return null
        return try {
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(encryption, "AES"), IvParameterSpec(data, IV_START, BLOCK))
                doFinal(data, CIPHERTEXT_START, ciphertextLength)
            }
        } catch (e: GeneralSecurityException) {
            // A bad padding under a good HMAC: a token made by something that isn't Fernet.
            null
        }
    }

    /**
     * [plaintext] as a token, dated [at] (unix seconds). This service never encrypts; the tests do,
     * to make tokens over real nsecs, and the Python vectors check it reads what Python made.
     */
    fun encrypt(
        plaintext: ByteArray,
        at: Long = System.currentTimeMillis() / 1000,
        iv: ByteArray = ByteArray(BLOCK).also { SecureRandom().nextBytes(it) },
    ): String {
        require(iv.size == BLOCK) { "an IV is $BLOCK bytes" }
        val ciphertext =
            Cipher.getInstance(TRANSFORMATION).run {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryption, "AES"), IvParameterSpec(iv))
                doFinal(plaintext)
            }
        val body = ByteArray(CIPHERTEXT_START + ciphertext.size)
        body[0] = VERSION
        for (i in 0 until 8) body[1 + i] = (at ushr (8 * (7 - i))).toByte()
        iv.copyInto(body, IV_START)
        ciphertext.copyInto(body, CIPHERTEXT_START)
        return Base64.getUrlEncoder().encodeToString(body + hmac(body, body.size))
    }

    private fun hmac(
        data: ByteArray,
        length: Int,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(signing, "HmacSHA256"))
            update(data, 0, length)
            doFinal()
        }

    override fun toString() = "Fernet(key hidden)"

    companion object {
        /**
         * [s] as Python's `urlsafe_b64decode` reads it: `+` and `/` are taken for `-` and `_`, and
         * whitespace anywhere (a wrapped line in the key file) is dropped. Java's URL decoder
         * refuses both, and would make a token the server opens unreadable here.
         */
        internal fun urlSafe(s: String): String =
            buildString(s.length) {
                for (c in s) {
                    if (!c.isWhitespace()) {
                        append(
                            if (c == '+') {
                                '-'
                            } else if (c == '/') {
                                '_'
                            } else {
                                c
                            },
                        )
                    }
                }
            }

        private const val VERSION: Byte = 0x80.toByte()
        private const val KEY_BYTES = 32
        private const val BLOCK = 16
        private const val HMAC_BYTES = 32
        private const val IV_START = 9
        private const val CIPHERTEXT_START = IV_START + BLOCK

        /** Version, timestamp, IV, one block of ciphertext (PKCS7 always pads), HMAC. */
        private const val MIN_TOKEN_BYTES = CIPHERTEXT_START + BLOCK + HMAC_BYTES

        // Java's "PKCS5Padding" is PKCS7 for AES's 16-byte blocks, which is what Fernet pads with.
        private const val TRANSFORMATION = "AES/CBC/PKCS5Padding"
    }
}

/**
 * Keys tried in order, as Python's `MultiFernet.decrypt` does: the first that opens a token wins.
 * brainstorm_server rotates by writing `[new, old]` and then re-encrypting, so both are live for a
 * while.
 */
class MultiFernet(
    val keys: List<Fernet>,
) {
    fun decrypt(token: String): ByteArray? {
        for (key in keys) key.decrypt(token)?.let { return it }
        return null
    }

    override fun toString() = "MultiFernet(${keys.size} keys)"

    companion object {
        /**
         * The keys of a key file's text: comma-separated, blanks dropped, as the server's
         * `_parse_keys` reads them. A key that isn't one is skipped and counted in [skipped]
         * rather than failing every owner: the server would refuse to start on it, so it is a
         * file being written, or an operator's slip, and the others may still open tokens.
         */
        fun parse(
            text: String,
            skipped: (index: Int) -> Unit = {},
        ): MultiFernet =
            MultiFernet(
                text
                    .split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .mapIndexedNotNull { i, k ->
                        try {
                            Fernet(k)
                        } catch (e: IllegalArgumentException) {
                            skipped(i)
                            null
                        }
                    },
            )
    }
}
