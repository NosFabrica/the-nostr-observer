package com.nosfabrica.observer.service.state

import com.vitorpamplona.quartz.utils.Log
import java.sql.Connection
import java.sql.SQLException

/**
 * The advisory-lock keys this service takes. Advisory locks are per database, and other programs
 * take their own in brainstorm_server's: these are spelled out once, here, and spell this
 * service's name.
 */
object AdvisoryLocks {
    /** Held while the `observer` schema is migrated. ASCII "obs-migr". */
    const val MIGRATIONS = 0x6f62732d6d696772L
}

/**
 * The `observer` schema, this service's own, applied by whichever of its processes starts first:
 * versioned SQL in `service/src/main/resources/observer/`, recorded in `observer.schema_version`,
 * under the [AdvisoryLocks.MIGRATIONS] lock so two processes starting together don't both apply one.
 * A file that shipped is never edited: a change is a new version.
 *
 * The schema itself is created only when it is missing: ops creates it with the role as its owner
 * (`service/README.md`, "Deploying"), and `CREATE SCHEMA IF NOT EXISTS` checks the database-level
 * CREATE right before it looks, which that role needn't have.
 */
object Migrations {
    const val SCHEMA = "observer"

    class Migration(
        val version: Int,
        val resource: String,
    )

    /** Every migration, in order. Append; never renumber. */
    val ALL =
        listOf(
            Migration(1, "V1__runs_and_pending.sql"),
        )

    /** The longest a process waits for another's migration before it gives up and exits. */
    const val LOCK_WAIT_MS = 120_000L

    /** Applies what is missing; returns the versions it applied (none when another process got there first). */
    suspend fun migrate(db: Db): List<Int> = db.use { migrate(it) }

    fun migrate(c: Connection): List<Int> {
        lock(c)
        try {
            if (!schemaExists(c)) c.createStatement().use { it.execute("CREATE SCHEMA $SCHEMA") }
            c.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $SCHEMA.schema_version (
                        version    integer     PRIMARY KEY,
                        name       text        NOT NULL,
                        applied_at timestamptz NOT NULL DEFAULT now()
                    )
                    """.trimIndent(),
                )
            }
            val applied = appliedVersions(c)
            val newest = applied.maxOrNull() ?: 0
            if (newest > ALL.last().version) {
                // A newer build migrated already (a rolling deploy): its tables are a superset.
                Log.i(TAG) { "schema version $newest is newer than this build's ${ALL.last().version}" }
            }
            val done = ArrayList<Int>()
            for (m in ALL) {
                if (m.version in applied) continue
                apply(c, m)
                done += m.version
                Log.i(TAG) { "applied ${m.resource}" }
            }
            return done
        } finally {
            unlock(c)
        }
    }

    private fun lock(c: Connection) {
        // A try-lock loop rather than a blocking pg_advisory_lock: a process stuck holding it
        // costs the others a bounded wait and a clear error, not a hang.
        val deadline = System.currentTimeMillis() + LOCK_WAIT_MS
        while (true) {
            val got =
                c.prepareStatement("SELECT pg_try_advisory_lock(?)").use { st ->
                    st.setLong(1, AdvisoryLocks.MIGRATIONS)
                    st.executeQuery().use { it.next() && it.getBoolean(1) }
                }
            if (got) return
            if (System.currentTimeMillis() > deadline) throw SQLException("another process held the migration lock for ${LOCK_WAIT_MS}ms")
            Thread.sleep(LOCK_POLL_MS)
        }
    }

    private fun unlock(c: Connection) {
        try {
            if (!c.autoCommit) c.rollback()
            c.autoCommit = true
            c.prepareStatement("SELECT pg_advisory_unlock(?)").use { st ->
                st.setLong(1, AdvisoryLocks.MIGRATIONS)
                st.executeQuery().close()
            }
        } catch (e: SQLException) {
            // The connection is closed next, and a session lock goes with its session.
            Log.d(TAG) { "unlock: ${e.javaClass.simpleName}" }
        }
    }

    private fun schemaExists(c: Connection): Boolean =
        c.prepareStatement("SELECT 1 FROM pg_namespace WHERE nspname = ?").use { st ->
            st.setString(1, SCHEMA)
            st.executeQuery().use { it.next() }
        }

    private fun appliedVersions(c: Connection): Set<Int> =
        c.createStatement().use { st ->
            st.executeQuery("SELECT version FROM $SCHEMA.schema_version").use { rs ->
                buildSet { while (rs.next()) add(rs.getInt(1)) }
            }
        }

    private fun apply(
        c: Connection,
        m: Migration,
    ) {
        val sql =
            Migrations::class.java
                .getResourceAsStream("/$SCHEMA/${m.resource}")
                ?.use { it.readBytes().decodeToString() }
                ?: error("missing migration resource ${m.resource}")
        c.autoCommit = false
        try {
            c.createStatement().use { it.execute(sql) }
            c.prepareStatement("INSERT INTO $SCHEMA.schema_version (version, name) VALUES (?, ?)").use { st ->
                st.setInt(1, m.version)
                st.setString(2, m.resource)
                st.executeUpdate()
            }
            c.commit()
        } catch (e: Exception) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    private const val TAG = "Migrations"
    private const val LOCK_POLL_MS = 200L
}
