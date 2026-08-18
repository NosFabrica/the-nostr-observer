package com.nosfabrica.observer.press.store

import java.security.SecureRandom
import java.time.Instant
import java.util.HexFormat

/**
 * Editions that exist but nobody has published.
 *
 * Generate-then-publish, never publish-to-see: the reader looks at the finished
 * page before anything is signed. That means a private draft has to live
 * somewhere for a while, and every property of this table is about keeping that
 * window small and unguessable.
 *
 * The id is 128 bits of [SecureRandom]. Drafts also carry their owner and every
 * read checks it — an unguessable id is a defence against a stranger, not
 * against a signed-in reader who tries somebody else's id. Both are needed.
 */
class Drafts(
    private val db: Db,
    private val ttlSeconds: Long = 6 * 60 * 60,
) {
    enum class State {
        RUNNING,
        READY,
        FAILED,
    }

    data class Draft(
        val id: String,
        val pubkey: String,
        val state: State,
        val progress: String,
        val html: String?,
        val sha256: String?,
        val summary: String?,
        val error: String?,
        val createdAt: Long,
    )

    fun open(pubkey: String): String {
        val now = Instant.now().epochSecond
        val id = HexFormat.of().formatHex(ByteArray(16).also(RANDOM::nextBytes))
        db.write(
            "INSERT INTO drafts (id, pubkey, created_at, expires_at, state, progress) VALUES (?, ?, ?, ?, ?, ?)",
            id,
            pubkey,
            now,
            now + ttlSeconds,
            State.RUNNING.name,
            "[]",
        )
        return id
    }

    fun progress(
        id: String,
        progressJson: String,
    ) = db.write("UPDATE drafts SET progress = ? WHERE id = ?", progressJson, id)

    fun ready(
        id: String,
        html: String,
        sha256: String,
        summaryJson: String,
        progressJson: String,
    ) = db.write(
        "UPDATE drafts SET state = ?, html = ?, sha256 = ?, summary = ?, progress = ? WHERE id = ?",
        State.READY.name,
        html,
        sha256,
        summaryJson,
        progressJson,
        id,
    )

    fun failed(
        id: String,
        error: String,
        progressJson: String,
    ) = db.write(
        "UPDATE drafts SET state = ?, error = ?, progress = ? WHERE id = ?",
        State.FAILED.name,
        error,
        progressJson,
        id,
    )

    /**
     * Owner-scoped by construction.
     *
     * Taking the pubkey as an argument rather than returning the row and letting
     * the caller compare is deliberate: the version where the check is the
     * caller's job is the version where one route forgets.
     */
    fun of(
        id: String,
        pubkey: String,
    ): Draft? =
        // No sweep here. The browser polls this every two seconds while an
        // edition is being written, and a DELETE on the read path made each of
        // those a write transaction. The expiry is enforced by the WHERE clause
        // below, which is what correctness needs; actually removing the rows is
        // the housekeeping timer's job.
        db
            .read(
                """
                SELECT id, pubkey, state, progress, html, sha256, summary, error, created_at
                FROM drafts WHERE id = ? AND pubkey = ? AND expires_at > ?
                """.trimIndent(),
                id,
                pubkey,
                Instant.now().epochSecond,
            ) { rs ->
                Draft(
                    id = rs.getString(1),
                    pubkey = rs.getString(2),
                    state = State.valueOf(rs.getString(3)),
                    progress = rs.getString(4),
                    html = rs.getString(5),
                    sha256 = rs.getString(6),
                    summary = rs.getString(7),
                    error = rs.getString(8),
                    createdAt = rs.getLong(9),
                )
            }.firstOrNull()

    /** Expiry that actually deletes. A TTL nobody enforces is a retention policy of "forever". */
    fun sweep(): Int = db.write("DELETE FROM drafts WHERE expires_at <= ?", Instant.now().epochSecond)

    private companion object {
        val RANDOM = SecureRandom()
    }
}
