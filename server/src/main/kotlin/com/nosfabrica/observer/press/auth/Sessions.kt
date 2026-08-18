package com.nosfabrica.observer.press.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap

/**
 * Who is signed in, held in memory on purpose.
 *
 * Sessions are the one thing here that SHOULD be lost on restart. A signed-in
 * reader can re-sign in with one signer prompt; a session table that survives a
 * deploy is a set of credentials with a long life and no revocation story.
 *
 * The cookie carries a random id and the table holds only its SHA-256, so a
 * dump of process memory or a future decision to persist this does not hand
 * anyone a working cookie.
 */
class Sessions(
    private val ttlSeconds: Long = 30 * 24 * 60 * 60,
) {
    private val live = ConcurrentHashMap<String, Entry>()

    data class Entry(
        val pubkey: String,
        val signer: String,
        val expiresAt: Long,
    )

    /** How the reader's signature reaches us, which decides who can sign later. */
    enum class Signer {
        /** A browser extension. Every signature needs the tab open and the reader present. */
        NIP07,

        /** A remote signer we hold a connection to. Phase 4's scheduled runs need this one. */
        NIP46,
    }

    fun open(
        pubkey: String,
        signer: Signer,
    ): String {
        val token = HexFormat.of().formatHex(ByteArray(32).also(RANDOM::nextBytes))
        live[hash(token)] = Entry(pubkey, signer.name, Instant.now().epochSecond + ttlSeconds)
        return token
    }

    fun of(token: String?): Entry? {
        if (token.isNullOrBlank()) return null
        val key = hash(token)
        val entry = live[key] ?: return null
        if (entry.expiresAt <= Instant.now().epochSecond) {
            live.remove(key)
            return null
        }
        return entry
    }

    fun close(token: String?) {
        if (!token.isNullOrBlank()) live.remove(hash(token))
    }

    private fun hash(token: String) =
        HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray()),
        )

    private companion object {
        val RANDOM = SecureRandom()
    }
}
