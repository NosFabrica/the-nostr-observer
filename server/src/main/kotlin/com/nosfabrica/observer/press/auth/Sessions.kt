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

    /**
     * Drop what has expired.
     *
     * [of] already refuses an expired entry, but only for a token somebody
     * still presents. A reader who signs in from a phone and never comes back
     * leaves a row that nothing ever looks at again, so nothing ever removes
     * it: expiry that only happens on access is not expiry, it is a leak with
     * a policy attached.
     */
    fun sweep(): List<String> {
        val now = Instant.now().epochSecond
        val gone = live.entries.filter { it.value.expiresAt <= now }.map { it.key }
        gone.forEach(live::remove)
        // The KEYS, not a count, because something else is holding resources
        // under them: a NIP-46 session owns an open subscription to the
        // reader's signer, and expiring the session without closing that leaves
        // it connected for the life of the process.
        return gone
    }

    fun size() = live.size

    private fun hash(token: String) = fingerprint(token)

    companion object {
        private val RANDOM = SecureRandom()

        /**
         * A session's identity, without the session's credentials.
         *
         * Public because [com.nosfabrica.observer.press.auth.Bunkers] keys on
         * it too. It used to key on the raw cookie value, which quietly undid
         * the reason this class hashes at all -- a live map of working cookies,
         * next door to the one that deliberately holds none -- and it meant
         * nothing could pair a swept session with the signer connection it
         * owned, because the two were filed under different names.
         */
        fun fingerprint(token: String): String =
            HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(token.toByteArray()),
            )
    }
}
