package com.nosfabrica.observer.press.store

import java.io.Closeable
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * One SQLite file, opened once, migrated forward by number.
 *
 * Numbered migrations rather than a schema dump, the way the relay does it: the
 * version a running deployment is on is a fact you can read out of the file,
 * and adding a column later is an append rather than an edit to history.
 *
 * SQLite and one connection: this is a single-process app whose write volume is
 * a handful of rows per edition. A pool would buy nothing and cost the "who
 * holds the write lock" question. Calls are serialised on [lock] because Ktor
 * serves requests on many threads and a JDBC Connection is not thread-safe.
 */
class Db(
    path: String,
) : Closeable {
    private val lock = Any()
    private val conn: Connection =
        DriverManager.getConnection("jdbc:sqlite:$path").apply {
            // WAL so a long read (an archive listing) does not block the write
            // that finishes a publish. Foreign keys are OFF by default in
            // SQLite, which is a surprise worth removing.
            createStatement().use {
                it.execute("PRAGMA journal_mode=WAL")
                it.execute("PRAGMA foreign_keys=ON")
                it.execute("PRAGMA busy_timeout=5000")
            }
        }

    init {
        migrate()
    }

    fun <T> read(
        sql: String,
        vararg args: Any?,
        row: (ResultSet) -> T,
    ): List<T> =
        synchronized(lock) {
            conn.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(row(rs)) }
                }
            }
        }

    fun write(
        sql: String,
        vararg args: Any?,
    ): Int =
        synchronized(lock) {
            conn.prepareStatement(sql).use { st ->
                args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
                st.executeUpdate()
            }
        }

    private fun migrate() =
        synchronized(lock) {
            conn.createStatement().use { st ->
                st.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
                val at =
                    st.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use {
                        it.next()
                        it.getInt(1)
                    }
                MIGRATIONS.drop(at).forEachIndexed { offset, sql ->
                    sql
                        .split(";--")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .forEach(st::execute)
                    st.execute("INSERT INTO schema_version VALUES (${at + offset + 1})")
                }
            }
        }

    override fun close() = conn.close()

    private companion object {
        /**
         * Append only. Statements inside one migration are split on `;--`
         * because a plain `;` also ends a line inside a trigger body, and the
         * day this file grows one is the day a naive split starts corrupting
         * migrations that used to work.
         */
        val MIGRATIONS =
            listOf(
                """
                CREATE TABLE continuity (
                    pubkey      TEXT PRIMARY KEY,
                    masthead    TEXT NOT NULL,
                    motto       TEXT NOT NULL,
                    sections    TEXT NOT NULL,
                    headlines   TEXT NOT NULL,
                    updated_at  INTEGER NOT NULL
                );--
                CREATE TABLE drafts (
                    id          TEXT PRIMARY KEY,
                    pubkey      TEXT NOT NULL,
                    created_at  INTEGER NOT NULL,
                    expires_at  INTEGER NOT NULL,
                    state       TEXT NOT NULL,
                    progress    TEXT NOT NULL,
                    html        TEXT,
                    sha256      TEXT,
                    summary     TEXT,
                    error       TEXT
                );--
                CREATE INDEX drafts_by_owner ON drafts (pubkey, created_at DESC);--
                CREATE INDEX drafts_by_expiry ON drafts (expires_at);--
                CREATE TABLE published (
                    pubkey       TEXT NOT NULL,
                    day          TEXT NOT NULL,
                    sha256       TEXT NOT NULL,
                    naddr        TEXT NOT NULL,
                    servers      TEXT NOT NULL,
                    published_at INTEGER NOT NULL,
                    PRIMARY KEY (pubkey, day)
                )
                """,
                // The archive moved to where it belonged all along: the
                // reader's own kind 35128, on the reader's own relays, which
                // already carried every path and outlives this deployment.
                // Keeping a copy here made our database the record of somebody
                // else's back catalogue -- and a replaceable event rebuilt from
                // a record we might not have is how a back catalogue vanishes.
                //
                // Appended rather than edited above, because the migration list
                // is append-only: an installation that ran migration 1 needs a
                // statement that drops the table, not a version of migration 1
                // that never made it.
                """DROP TABLE IF EXISTS published""",
            )
    }
}
