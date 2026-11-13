package com.nosfabrica.observer.runner

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import com.vitorpamplona.quartz.utils.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.concurrent.Volatile

/**
 * The worker's own record of the occurrences it ran (NIP-FB: a runner can't write `claim` or
 * `ran`, so its records and its published site are what keep it from running one twice). A run
 * that reached the model ENDED, whatever came of it: it may have spent the Assistant's money, so
 * it is never run again on its own. A run that was interrupted (the worker died, or the run threw
 * after it started) counts as ENDED too, never [OwnRun.DIED]: the worker can't tell whether it
 * reached the model, so it never risks paying for the same morning twice. Only a start that spent
 * nothing ([forget], the ranking wasn't ready) is tried again. A start that can't be written
 * down isn't a start: [started] throws, and nothing is printed.
 */
interface RunRecords {
    fun ownRun(
        owner: HexKey,
        occurrence: Long,
    ): OwnRun

    /**
     * Records that a run of [occurrence] starts, durably, before anything is spent.
     * @throws RunRecordsException when it can't be kept: then the run must not start.
     */
    fun started(
        owner: HexKey,
        occurrence: Long,
    )

    fun ended(
        owner: HexKey,
        occurrence: Long,
    )

    /**
     * Forget a start that spent nothing (the ranking wasn't ready, or the endpoint's provider was
     * down before any answer): a later poll may try again. Forgotten on disk too, best effort: if
     * that write fails, the start stays on disk, and after a restart it reads as ended.
     */
    fun forget(
        owner: HexKey,
        occurrence: Long,
    )
}

/** The run records can't be kept (a write failed) or read (a corrupt file): nothing may be printed. */
class RunRecordsException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

@Serializable
private data class Record(
    val owner: String,
    val occurrence: Long,
    val ended: Boolean,
)

/** [RunRecords] in memory, written through to [file] when there is one (a volume, so a restart remembers). */
class FileRunRecords(
    private val file: File?,
    private val keepPerOwner: Int = 8,
) : RunRecords {
    private val json = Json { ignoreUnknownKeys = true }
    private val records = LinkedHashMap<Pair<HexKey, Long>, Boolean>()

    /**
     * Why the records on disk can't be trusted, or null. A file that doesn't parse is never read
     * as empty: that would forget every run it holds and pay for those mornings again. The worker
     * prints nothing ([started] throws) until an operator looks at the file.
     */
    @Volatile
    var unusable: String? = null
        private set

    init {
        if (file != null && file.exists()) {
            try {
                json.decodeFromString<List<Record>>(file.readText()).forEach { records[it.owner to it.occurrence] = it.ended }
            } catch (e: Exception) {
                unusable = "the run records file ${file.path} doesn't parse"
                Log.e(TAG, "Could not read the run records: printing stops until the file is fixed or moved away", e)
            }
        }
    }

    @Synchronized
    override fun ownRun(
        owner: HexKey,
        occurrence: Long,
    ): OwnRun =
        when (records[owner to occurrence]) {
            null -> OwnRun.NONE
            // Started and never ended: interrupted. Never taken up again on its own.
            else -> OwnRun.ENDED
        }

    @Synchronized
    override fun started(
        owner: HexKey,
        occurrence: Long,
    ) {
        unusable?.let { throw RunRecordsException(it) }
        val before = records[owner to occurrence]
        put(owner, occurrence, false)
        try {
            save()
        } catch (e: IOException) {
            // Not kept, so not started: nothing was spent, and a later poll may try again.
            if (before == null) records.remove(owner to occurrence) else records[owner to occurrence] = before
            throw RunRecordsException("could not write the run records", e)
        }
    }

    @Synchronized
    override fun ended(
        owner: HexKey,
        occurrence: Long,
    ) {
        put(owner, occurrence, true)
        // A failure here leaves the start on disk, which reads as ended too: logged, not thrown.
        saveOrLog()
    }

    @Synchronized
    override fun forget(
        owner: HexKey,
        occurrence: Long,
    ) {
        records.remove(owner to occurrence)
        // A failed write leaves the start on disk, read as ended after a restart: that morning is
        // then not tried again, which costs a paper, never a second payment.
        saveOrLog()
    }

    private fun put(
        owner: HexKey,
        occurrence: Long,
        ended: Boolean,
    ) {
        records[owner to occurrence] = ended
        // Only the newest few per owner matter: an occurrence is runnable only until the next one is due.
        records.keys
            .filter { it.first == owner }
            .sortedByDescending { it.second }
            .drop(keepPerOwner)
            .forEach { records.remove(it) }
    }

    private fun saveOrLog() {
        try {
            save()
        } catch (e: IOException) {
            Log.w(TAG, "Could not write the run records", e)
        }
    }

    /** The records to a temporary file, synced, then moved over the old one: a crash leaves one or the other, whole. */
    private fun save() {
        val file = file ?: return
        val tmp = File(file.path + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(records.map { (k, v) -> Record(k.first, k.second, v) }).encodeToByteArray())
            out.fd.sync()
        }
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // The rename itself is durable once the directory is synced (Linux allows opening it to sync).
        try {
            FileChannel.open(file.absoluteFile.parentFile.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (e: IOException) {
            Log.d(TAG) { "Could not sync the records directory: ${e.javaClass.simpleName}" }
        }
    }

    companion object {
        private const val TAG = "RunRecords"
    }
}

/**
 * One worker per state file: an exclusive lock on `<state>.lock` beside it, held for the life of
 * the process. The lock is on a file of its own because [FileRunRecords] replaces the state file
 * by renaming, and a lock on the old file would not cover the new one. Null when another process
 * holds it: two workers would both print the same morning.
 */
object StateLock {
    fun acquire(state: File): FileLock? {
        state.absoluteFile.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw IOException("could not create ${it.path}") }
        val channel = FileChannel.open(File(state.path + ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
        val lock =
            try {
                channel.tryLock()
            } catch (e: OverlappingFileLockException) {
                null
            }
        if (lock == null) channel.close()
        return lock
    }
}
