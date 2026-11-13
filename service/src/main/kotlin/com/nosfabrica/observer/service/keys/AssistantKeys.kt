package com.nosfabrica.observer.service.keys

import com.nosfabrica.observer.service.state.Db
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nip19Bech32.decodePrivateKeyAsHexOrNull
import com.vitorpamplona.quartz.utils.Hex
import com.vitorpamplona.quartz.utils.Log
import java.sql.Connection

/** An owner and the Assistant brainstorm_server holds for them, as its table lists them: nothing decrypted. */
data class AssistantPair(
    val owner: HexKey,
    val assistant: HexKey,
)

/** Why an owner's Assistant key can't be had. Logged by name, with the owner. */
enum class KeyFailure {
    /** `brainstorm_nsec` has no row for the owner, or one with no `assistant_pubkey`: no Assistant (or it was removed). */
    NO_ASSISTANT,

    /** No key in the key file opens the token, even after reading it again, or the plaintext isn't an nsec. */
    NSEC_UNREADABLE,

    /** The nsec opens, but its public key isn't the stored `assistant`: a row that doesn't say what it holds. */
    NSEC_MISMATCH,
}

/** An owner's Assistant key, or why there is none. [toString] never shows the key. */
sealed interface AssistantKey {
    class Ready(
        val owner: HexKey,
        val signer: NostrSignerInternal,
    ) : AssistantKey {
        override fun toString() = "AssistantKey.Ready(owner=$owner, assistant=${signer.pubKey})"
    }

    class Failed(
        val owner: HexKey,
        val failure: KeyFailure,
    ) : AssistantKey {
        override fun toString() = "AssistantKey.Failed(owner=$owner, $failure)"
    }
}

/** Where the owners, their Assistants and the Assistants' keys come from. [AssistantKeys] reads Postgres; tests fake it. */
interface AssistantDirectory {
    /** Up to [limit] pairs with an owner after [after] (all when null), by owner. */
    suspend fun page(
        after: HexKey?,
        limit: Int = PAGE,
    ): List<AssistantPair>

    /** Every pair, page by page. */
    suspend fun pairs(): List<AssistantPair> {
        val all = ArrayList<AssistantPair>()
        var after: HexKey? = null
        while (true) {
            val page = page(after, PAGE)
            all += page
            if (page.size < PAGE) return all
            after = page.last().owner
        }
    }

    /** [owner]'s Assistant key, decrypted now; held by the caller in memory only. */
    suspend fun key(owner: HexKey): AssistantKey

    companion object {
        const val PAGE = 500
    }
}

/**
 * brainstorm_server's `brainstorm_nsec` table, read as its own repo (`app/repos/brainstorm_nsec.py`)
 * reads it, and its Fernet key file. Only four columns ([COLUMNS]), the ones ops grants the role:
 * this service is coupled to them, so a server migration that renames or drops one breaks it.
 * The runner that wakes this service reads the same columns with code of its own: the two share
 * the columns and the server's rule, never this code.
 *
 * - [page] is the server's `select_owner_assistant_pairs_on_db`: `pubkey` and `assistant_pubkey`
 *   where an Assistant is set, by `pubkey`; nothing is decrypted.
 * - [key] reads one owner's `assistant_pubkey`, `encrypted_nsec` and `nsec`, and opens them as the
 *   server's `_plaintext_nsec` does: `encrypted_nsec` when it is non-empty (a value that is
 *   already an `nsec1` is taken as it is, as `decrypt_nsec` does), `nsec` only otherwise. A stale
 *   plaintext `nsec` beside a token is never used: the token wins.
 *
 * Neither the nsec, the token nor the plaintext is ever logged, and no exception that could carry
 * one escapes: a failure is a [KeyFailure].
 */
class AssistantKeys(
    private val db: Db,
    private val keyFile: FernetKeyFile,
    private val table: String = TABLE,
) : AssistantDirectory {
    init {
        require(TABLE_NAME.matches(table)) { "not a table name" }
    }

    override suspend fun page(
        after: HexKey?,
        limit: Int,
    ): List<AssistantPair> =
        db.use { c ->
            // Two statements rather than `? IS NULL OR pubkey > ?`: Postgres can't type an untyped null parameter.
            // A row the server wrote wrong is left out by the query, not after it: a page short of
            // LIMIT is what ends [pairs], so one bad row filtered here would drop every owner after it.
            val sql =
                if (after == null) {
                    "SELECT pubkey, assistant_pubkey FROM $table WHERE $VALID ORDER BY pubkey LIMIT ?"
                } else {
                    "SELECT pubkey, assistant_pubkey FROM $table WHERE $VALID AND pubkey > ? ORDER BY pubkey LIMIT ?"
                }
            c.prepareStatement(sql).use { st ->
                var i = 1
                if (after != null) st.setString(i++, after)
                st.setInt(i, limit)
                st.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            val owner = rs.getString(1)
                            val assistant = rs.getString(2)
                            // [VALID] already says so; checked again so a pair is never built from anything else.
                            if (isPubKeyHex(owner) && isPubKeyHex(assistant)) add(AssistantPair(owner, assistant))
                        }
                    }
                }
            }
        }

    override suspend fun key(owner: HexKey): AssistantKey {
        val row = db.use { c -> row(c, owner) } ?: return failed(owner, KeyFailure.NO_ASSISTANT)
        val assistant = row.assistant ?: return failed(owner, KeyFailure.NO_ASSISTANT)
        val hex = plaintextOf(row)?.let { decodePrivateKeyAsHexOrNull(it.trim()) }?.takeIf { it.length == 64 }
        val keyPair = hex?.let(::keyPairOrNull) ?: return failed(owner, KeyFailure.NSEC_UNREADABLE)
        if (keyPair.pubKey.toHexKey() != assistant.lowercase()) return failed(owner, KeyFailure.NSEC_MISMATCH)
        return AssistantKey.Ready(owner, NostrSignerInternal(keyPair))
    }

    private fun failed(
        owner: HexKey,
        failure: KeyFailure,
    ): AssistantKey.Failed {
        Log.d(TAG) { "owner=$owner key=$failure" }
        return AssistantKey.Failed(owner, failure)
    }

    /** One owner's row. Strings can't be wiped, so it lives only as long as [key] runs. */
    private class Row(
        val assistant: String?,
        val encrypted: String?,
        val nsec: String?,
    )

    private fun row(
        c: Connection,
        owner: HexKey,
    ): Row? =
        c.prepareStatement("SELECT assistant_pubkey, encrypted_nsec, nsec FROM $table WHERE pubkey = ?").use { st ->
            st.setString(1, owner)
            st.executeQuery().use { rs -> if (rs.next()) Row(rs.getString(1), rs.getString(2), rs.getString(3)) else null }
        }

    /** The nsec's text, or null when nothing opens it: the server's `_plaintext_nsec`. */
    private fun plaintextOf(row: Row): String? {
        val token = row.encrypted
        // The server's `if encrypted_nsec:` reads an empty token as none too.
        if (token.isNullOrEmpty()) return row.nsec
        // The server's decrypt_nsec: a value that was never encrypted is used as it is.
        if (token.startsWith("nsec1")) return token
        val bytes = keyFile.decrypt(token) ?: return null
        return try {
            bytes.decodeToString()
        } finally {
            bytes.fill(0)
        }
    }

    private fun keyPairOrNull(hex: String): KeyPair? =
        try {
            val priv = Hex.decode(hex)
            try {
                KeyPair(priv.copyOf())
            } finally {
                priv.fill(0)
            }
        } catch (e: Exception) {
            // Zero, or past the curve's order: not a key. The exception is dropped, not logged.
            null
        }

    companion object {
        private const val TAG = "AssistantKeys"

        /** brainstorm_server's table (its `BrainstormNsec` model). */
        const val TABLE = "brainstorm_nsec"

        /** The only columns read, and the only ones ops grants the role (`service/README.md`, "Deploying"). */
        val COLUMNS = listOf("pubkey", "assistant_pubkey", "encrypted_nsec", "nsec")

        private val TABLE_NAME = Regex("[a-z_][a-z0-9_]*(\\.[a-z_][a-z0-9_]*)?")

        /** A pair that can be matched to a schedule: both keys 64 lowercase hex ([isPubKeyHex]); a null fails the match. */
        private const val VALID = "pubkey ~ '^[0-9a-f]{64}$' AND assistant_pubkey ~ '^[0-9a-f]{64}$'"
    }
}
