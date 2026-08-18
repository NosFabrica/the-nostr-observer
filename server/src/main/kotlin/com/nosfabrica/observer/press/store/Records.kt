package com.nosfabrica.observer.press.store

import com.nosfabrica.observer.write.Continuity
import kotlinx.serialization.json.Json
import java.time.Instant

private val json = Json { ignoreUnknownKeys = true }

/**
 * The masthead a reader's paper keeps between days.
 *
 * "Softly in place" is the design: the title and the standing phrases persist so
 * the paper feels like the same paper, and a big enough day is allowed to change
 * them. Persisting it is what makes that possible at all — without a record,
 * every edition is the first edition.
 */
class Continuities(
    private val db: Db,
) {
    fun of(pubkey: String): Continuity =
        db
            .read(
                "SELECT masthead, motto, sections, headlines FROM continuity WHERE pubkey = ?",
                pubkey,
            ) { rs ->
                Continuity(
                    masthead = rs.getString(1),
                    motto = rs.getString(2),
                    sections = strings(rs.getString(3)),
                    recentHeadlines = strings(rs.getString(4)),
                )
            }.firstOrNull() ?: Continuity()

    /**
     * Record what today's paper actually called itself.
     *
     * Headlines are capped at the last handful: the continuity record is a
     * reminder of the recent past, and an unbounded list would grow into the
     * prompt one day at a time until it crowded out the corpus.
     */
    fun remember(
        pubkey: String,
        masthead: String,
        motto: String,
        sections: List<String>,
        headlines: List<String>,
    ) {
        db.write(
            """
            INSERT INTO continuity (pubkey, masthead, motto, sections, headlines, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(pubkey) DO UPDATE SET
                masthead = excluded.masthead, motto = excluded.motto,
                sections = excluded.sections, headlines = excluded.headlines,
                updated_at = excluded.updated_at
            """.trimIndent(),
            pubkey,
            masthead,
            motto,
            json.encodeToString(sections),
            json.encodeToString(headlines.take(12)),
            Instant.now().epochSecond,
        )
    }

    private fun strings(raw: String?): List<String> =
        runCatching { json.decodeFromString<List<String>>(raw ?: "[]") }.getOrDefault(emptyList())
}

/** An edition that reached somebody's own servers. Address and hash, nothing else. */
class Published(
    private val db: Db,
) {
    data class Row(
        val day: String,
        val sha256: String,
        val naddr: String,
        val servers: List<String>,
        val publishedAt: Long,
    )

    fun record(
        pubkey: String,
        day: String,
        sha256: String,
        naddr: String,
        servers: List<String>,
    ) {
        db.write(
            """
            INSERT INTO published (pubkey, day, sha256, naddr, servers, published_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT(pubkey, day) DO UPDATE SET
                sha256 = excluded.sha256, naddr = excluded.naddr,
                servers = excluded.servers, published_at = excluded.published_at
            """.trimIndent(),
            pubkey,
            day,
            sha256,
            naddr,
            json.encodeToString(servers),
            Instant.now().epochSecond,
        )
    }

    fun of(pubkey: String): List<Row> =
        db.read(
            "SELECT day, sha256, naddr, servers, published_at FROM published WHERE pubkey = ? ORDER BY day DESC",
            pubkey,
        ) { rs ->
            Row(
                day = rs.getString(1),
                sha256 = rs.getString(2),
                naddr = rs.getString(3),
                servers = runCatching { json.decodeFromString<List<String>>(rs.getString(4)) }.getOrDefault(emptyList()),
                publishedAt = rs.getLong(5),
            )
        }

    /**
     * The archive as the manifest wants it: every day this reader has published.
     *
     * A `kind 35128` is replaceable, so each publish REPLACES the whole manifest
     * rather than appending to it. Rebuilding the full path list from our own
     * index is what stops day two from silently deleting day one.
     */
    fun paths(pubkey: String): List<Pair<String, String>> = of(pubkey).map { "/${it.day}" to it.sha256 }
}
