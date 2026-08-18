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
