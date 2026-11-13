package com.nosfabrica.observer.service.state

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.security.MessageDigest
import java.sql.Connection

/** One file of a [Pending] output: what the task publishes (the Observer's page). [toString] leaves the bytes out. */
class PendingFile(
    val path: String,
    /** Lowercase hex SHA-256 of [bytes]: what Blossom names it by. Checked when it is stored. */
    val sha256: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    override fun toString() = "PendingFile($path, $sha256, $contentType, ${bytes.size} bytes)"

    companion object {
        fun of(
            path: String,
            contentType: String,
            bytes: ByteArray,
        ) = PendingFile(path, sha256Hex(bytes), contentType, bytes)
    }
}

/**
 * A task's output that was made (and paid for) but isn't published yet: the Observer's printed
 * paper, as its `PendingPapers` kept it. Stored BEFORE its first publish, so a process stopped
 * mid-publish still has it; published again until [until]; deleted once it is out. Never made
 * again: its run is ended.
 */
class Pending(
    val task: String,
    val owner: HexKey,
    val occurrence: Long,
    /** The task's own fields (the Observer's edition: day, model, headline, …). */
    val meta: JsonObject,
    /** Unix seconds after which it is dropped, published or not. */
    val until: Long,
    val files: List<PendingFile>,
) {
    override fun toString() = "Pending($task, owner=$owner, occurrence=$occurrence, ${files.size} files)"
}

/**
 * `observer.pending` and `pending_files`, one output per (task, owner, occurrence): an
 * older output that never got out isn't replaced by a newer one, it waits beside it.
 */
interface PendingStore {
    /** Stores [pending], replacing the same occurrence's meta, time and files. */
    suspend fun put(pending: Pending)

    /** [owner]'s outputs of [task] waiting, oldest occurrence first, files included. */
    suspend fun list(
        task: String,
        owner: HexKey,
    ): List<Pending>

    /**
     * The owners with an output of [task] waiting, in order: what a task service's retry loop
     * walks, publishing each owner's again.
     */
    suspend fun owners(task: String): List<HexKey>

    suspend fun delete(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): Boolean

    /** Drops every output whose `until` is before [now] (unix seconds); returns how many. */
    suspend fun dropExpired(now: Long): Int
}

/** [PendingStore] on Postgres. */
class PostgresPendingStore(
    private val db: Db,
) : PendingStore {
    override suspend fun put(pending: Pending) {
        checkFiles(pending)
        db.transaction { c ->
            c
                .prepareStatement(
                    "INSERT INTO $PENDING (task, owner, occurrence, meta, until) VALUES (?, ?, ?, ?::jsonb, ?) " +
                        "ON CONFLICT (task, owner, occurrence) DO UPDATE SET meta = EXCLUDED.meta, until = EXCLUDED.until",
                ).use { st ->
                    st.setString(1, pending.task)
                    st.setString(2, pending.owner)
                    st.setLong(3, pending.occurrence)
                    st.setString(4, pending.meta.toString())
                    st.setLong(5, pending.until)
                    st.executeUpdate()
                }
            c.prepareStatement("DELETE FROM $FILES WHERE task = ? AND owner = ? AND occurrence = ?").use { st ->
                st.setString(1, pending.task)
                st.setString(2, pending.owner)
                st.setLong(3, pending.occurrence)
                st.executeUpdate()
            }
            c
                .prepareStatement(
                    "INSERT INTO $FILES (task, owner, occurrence, path, sha256, content_type, bytes) VALUES (?, ?, ?, ?, ?, ?, ?)",
                ).use { st ->
                    for (f in pending.files) {
                        st.setString(1, pending.task)
                        st.setString(2, pending.owner)
                        st.setLong(3, pending.occurrence)
                        st.setString(4, f.path)
                        st.setString(5, f.sha256)
                        st.setString(6, f.contentType)
                        st.setBytes(7, f.bytes)
                        st.addBatch()
                    }
                    if (pending.files.isNotEmpty()) st.executeBatch()
                }
        }
    }

    override suspend fun list(
        task: String,
        owner: HexKey,
    ): List<Pending> =
        db.use { c ->
            val heads =
                c
                    .prepareStatement(
                        "SELECT occurrence, meta::text, until FROM $PENDING WHERE task = ? AND owner = ? ORDER BY occurrence",
                    ).use { st ->
                        st.setString(1, task)
                        st.setString(2, owner)
                        st.executeQuery().use { rs ->
                            buildList { while (rs.next()) add(Triple(rs.getLong(1), rs.getString(2), rs.getLong(3))) }
                        }
                    }
            val files = files(c, task, owner)
            heads.map { (occurrence, meta, until) ->
                Pending(task, owner, occurrence, Json.parseToJsonElement(meta).jsonObject, until, files[occurrence].orEmpty())
            }
        }

    override suspend fun owners(task: String): List<HexKey> =
        db.use { c ->
            c.prepareStatement("SELECT DISTINCT owner FROM $PENDING WHERE task = ? ORDER BY owner").use { st ->
                st.setString(1, task)
                st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
            }
        }

    private fun files(
        c: Connection,
        task: String,
        owner: HexKey,
    ): Map<Long, List<PendingFile>> =
        c
            .prepareStatement(
                "SELECT occurrence, path, sha256, content_type, bytes FROM $FILES WHERE task = ? AND owner = ? ORDER BY occurrence, path",
            ).use { st ->
                st.setString(1, task)
                st.setString(2, owner)
                st.executeQuery().use { rs ->
                    val out = LinkedHashMap<Long, MutableList<PendingFile>>()
                    while (rs.next()) {
                        out
                            .getOrPut(rs.getLong(1)) { ArrayList() }
                            .add(PendingFile(rs.getString(2), rs.getString(3), rs.getString(4), rs.getBytes(5)))
                    }
                    out
                }
            }

    override suspend fun delete(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): Boolean =
        db.use { c ->
            // Its files go with it (ON DELETE CASCADE).
            c.prepareStatement("DELETE FROM $PENDING WHERE task = ? AND owner = ? AND occurrence = ?").use { st ->
                st.setString(1, task)
                st.setString(2, owner)
                st.setLong(3, occurrence)
                st.executeUpdate() == 1
            }
        }

    override suspend fun dropExpired(now: Long): Int =
        db.use { c ->
            c.prepareStatement("DELETE FROM $PENDING WHERE until < ?").use { st ->
                st.setLong(1, now)
                st.executeUpdate()
            }
        }

    companion object {
        const val PENDING = "${Migrations.SCHEMA}.pending"
        const val FILES = "${Migrations.SCHEMA}.pending_files"
    }
}

/** [PendingStore] in memory, with the same rules: for tests. */
class MemoryPendingStore : PendingStore {
    private val rows = LinkedHashMap<Triple<String, HexKey, Long>, Pending>()

    override suspend fun put(pending: Pending) {
        synchronized(rows) {
            checkFiles(pending)
            rows[Triple(pending.task, pending.owner, pending.occurrence)] = pending
        }
    }

    override suspend fun list(
        task: String,
        owner: HexKey,
    ): List<Pending> = synchronized(rows) { rows.values.filter { it.task == task && it.owner == owner }.sortedBy { it.occurrence } }

    override suspend fun owners(task: String): List<HexKey> =
        synchronized(rows) {
            rows.values
                .filter { it.task == task }
                .map { it.owner }
                .distinct()
                .sorted()
        }

    override suspend fun delete(
        task: String,
        owner: HexKey,
        occurrence: Long,
    ): Boolean = synchronized(rows) { rows.remove(Triple(task, owner, occurrence)) != null }

    override suspend fun dropExpired(now: Long): Int {
        synchronized(rows) {
            val gone = rows.filterValues { it.until < now }.keys
            gone.forEach { rows.remove(it) }
            return gone.size
        }
    }
}

/** A file whose hash doesn't name its bytes would be published under the wrong name: refused before it is stored. */
private fun checkFiles(pending: Pending) {
    val paths = HashSet<String>()
    for (f in pending.files) {
        require(paths.add(f.path)) { "two files at ${f.path}" }
        require(f.sha256 == sha256Hex(f.bytes)) { "the sha256 of ${f.path} doesn't name its bytes" }
    }
}

internal fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
