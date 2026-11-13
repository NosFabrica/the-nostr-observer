package com.nosfabrica.observer.service.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import java.sql.ResultSet
import java.time.Instant

/** Where a run of one occurrence is: claimed and under way (or interrupted), or over. */
enum class RunState(
    val sql: String,
) {
    STARTED("started"),
    ENDED("ended"),
    ;

    companion object {
        fun of(sql: String) = entries.first { it.sql == sql }
    }
}

/** One row of `observer.runs`. */
data class RunRecord(
    val task: String,
    val owner: HexKey,
    val occurrence: Long,
    val state: RunState,
    /** The task service's own outcome name; null while it runs. */
    val outcome: String?,
    val scheduleId: String?,
    val startedAt: Instant,
    val endedAt: Instant?,
)

/**
 * `observer.runs`: which occurrences this service ran. Its own table: no other program reads it
 * (the runner learns an occurrence is settled from the wake's `200` answer, not from here).
 *
 * - [claim] is the only way a run starts: `INSERT … ON CONFLICT DO NOTHING`, and only the caller
 *   that inserted runs the occurrence, so two replicas never run it twice. It is written before
 *   anything is spent; if it can't be written (it throws), nothing is spent.
 * - A run that stopped before spending [forget]s its row, and may be tried again.
 * - A run that may have spent [end]s, and is never run again on its own.
 *
 * A row that stays `started` (the process died mid-run) is never taken up again: nobody can tell
 * whether that run spent. Quartz reads it as [OwnRun.DIED], which a runner never resumes.
 */
interface RunStore {
    /** Claims (task, owner, occurrence). True when this call inserted the row, false when one was there. */
    suspend fun claim(
        task: String,
        owner: HexKey,
        occurrence: Long,
        scheduleId: String? = null,
    ): Boolean

    /** Ends a started run with [outcome]. False when there was no started run to end. */
    suspend fun end(
        task: String,
        owner: HexKey,
        occurrence: Long,
        outcome: String,
    ): Boolean

    /** Deletes a started run that spent nothing, so it may be tried again. An ended run is never forgotten. */
    suspend fun forget(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): Boolean

    suspend fun find(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): RunRecord?

    /** Whether the occurrence has a row, started or ended: nobody should be woken for it, and nobody run it. */
    suspend fun isSettled(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): Boolean = find(task, owner, occurrence) != null

    /** The row as Quartz's runner rules read it (`ScheduledTaskClaims.decideAsRunner`'s `ownRun`). */
    suspend fun ownRun(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): OwnRun =
        when (find(task, owner, occurrence)?.state) {
            null -> OwnRun.NONE
            RunState.STARTED -> OwnRun.DIED
            RunState.ENDED -> OwnRun.ENDED
        }

    /** [owner]'s occurrences of [task] with a row, newest first, at most [limit]: the Observer's edition number counts their days. */
    suspend fun occurrences(
        task: String,
        owner: HexKey,
        limit: Int = 64,
    ): List<Long>
}

/** [RunStore] on Postgres. */
class PostgresRunStore(
    private val db: Db,
) : RunStore {
    override suspend fun claim(
        task: String,
        owner: HexKey,
        occurrence: Long,
        scheduleId: String?,
    ): Boolean =
        db.use { c ->
            c
                .prepareStatement(
                    "INSERT INTO $TABLE (task, owner, occurrence, state, schedule_id) VALUES (?, ?, ?, 'started', ?) " +
                        "ON CONFLICT DO NOTHING",
                ).use { st ->
                    st.setString(1, task)
                    st.setString(2, owner)
                    st.setLong(3, occurrence)
                    st.setString(4, scheduleId)
                    st.executeUpdate() == 1
                }
        }

    override suspend fun end(
        task: String,
        owner: HexKey,
        occurrence: Long,
        outcome: String,
    ): Boolean =
        db.use { c ->
            c
                .prepareStatement(
                    "UPDATE $TABLE SET state = 'ended', outcome = ?, ended_at = now() " +
                        "WHERE task = ? AND owner = ? AND occurrence = ? AND state = 'started'",
                ).use { st ->
                    st.setString(1, outcome)
                    st.setString(2, task)
                    st.setString(3, owner)
                    st.setLong(4, occurrence)
                    st.executeUpdate() == 1
                }
        }

    override suspend fun forget(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): Boolean =
        db.use { c ->
            c.prepareStatement("DELETE FROM $TABLE WHERE task = ? AND owner = ? AND occurrence = ? AND state = 'started'").use { st ->
                st.setString(1, task)
                st.setString(2, owner)
                st.setLong(3, occurrence)
                st.executeUpdate() == 1
            }
        }

    override suspend fun find(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): RunRecord? =
        db.use { c ->
            c
                .prepareStatement(
                    "SELECT task, owner, occurrence, state, outcome, schedule_id, started_at, ended_at FROM $TABLE " +
                        "WHERE task = ? AND owner = ? AND occurrence = ?",
                ).use { st ->
                    st.setString(1, task)
                    st.setString(2, owner)
                    st.setLong(3, occurrence)
                    st.executeQuery().use { rs -> if (rs.next()) record(rs) else null }
                }
        }

    override suspend fun occurrences(
        task: String,
        owner: HexKey,
        limit: Int,
    ): List<Long> =
        db.use { c ->
            c.prepareStatement("SELECT occurrence FROM $TABLE WHERE task = ? AND owner = ? ORDER BY occurrence DESC LIMIT ?").use { st ->
                st.setString(1, task)
                st.setString(2, owner)
                st.setInt(3, limit)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
            }
        }

    private fun record(rs: ResultSet) =
        RunRecord(
            task = rs.getString(1),
            owner = rs.getString(2),
            occurrence = rs.getLong(3),
            state = RunState.of(rs.getString(4)),
            outcome = rs.getString(5),
            scheduleId = rs.getString(6),
            startedAt = rs.getTimestamp(7).toInstant(),
            endedAt = rs.getTimestamp(8)?.toInstant(),
        )

    companion object {
        const val TABLE = "${Migrations.SCHEMA}.runs"
    }
}

/** [RunStore] in memory, with the same rules: for tests. */
class MemoryRunStore(
    private val now: () -> Instant = Instant::now,
) : RunStore {
    private data class Key(
        val task: String,
        val owner: HexKey,
        val occurrence: Long,
    )

    private val rows = LinkedHashMap<Key, RunRecord>()

    override suspend fun claim(
        task: String,
        owner: HexKey,
        occurrence: Long,
        scheduleId: String?,
    ): Boolean {
        synchronized(rows) {
            val key = Key(task, owner, occurrence)
            if (key in rows) return false
            rows[key] = RunRecord(task, owner, occurrence, RunState.STARTED, null, scheduleId, now(), null)
            return true
        }
    }

    override suspend fun end(
        task: String,
        owner: HexKey,
        occurrence: Long,
        outcome: String,
    ): Boolean {
        synchronized(rows) {
            val key = Key(task, owner, occurrence)
            val row = rows[key]?.takeIf { it.state == RunState.STARTED } ?: return false
            rows[key] = row.copy(state = RunState.ENDED, outcome = outcome, endedAt = now())
            return true
        }
    }

    override suspend fun forget(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): Boolean {
        synchronized(rows) {
            val key = Key(task, owner, occurrence)
            if (rows[key]?.state != RunState.STARTED) return false
            rows.remove(key)
            return true
        }
    }

    override suspend fun find(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): RunRecord? = synchronized(rows) { rows[Key(task, owner, occurrence)] }

    override suspend fun occurrences(
        task: String,
        owner: HexKey,
        limit: Int,
    ): List<Long> =
        synchronized(rows) {
            rows.keys
                .filter { it.task == task && it.owner == owner }
                .map { it.occurrence }
                .sortedDescending()
                .take(limit)
        }
}
