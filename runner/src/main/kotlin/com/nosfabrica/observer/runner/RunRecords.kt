package com.nosfabrica.observer.runner

import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import com.vitorpamplona.quartz.utils.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The worker's own record of the occurrences it ran (NIP-FB: a runner can't write `claim` or
 * `ran`, so its records and its published site are what keep it from running one twice). A run
 * that reached the model ENDED, whatever came of it: it may have spent the Assistant's money, so
 * it is never run again on its own. A run that died before ending is [OwnRun.DIED] and may be
 * taken up again.
 */
interface RunRecords {
    fun ownRun(
        owner: HexKey,
        occurrence: Long,
    ): OwnRun

    fun started(
        owner: HexKey,
        occurrence: Long,
    )

    fun ended(
        owner: HexKey,
        occurrence: Long,
    )

    /** Forget a start that spent nothing (the ranking wasn't ready): the next poll may try again. */
    fun forget(
        owner: HexKey,
        occurrence: Long,
    )
}

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

    init {
        if (file != null && file.exists()) {
            try {
                json.decodeFromString<List<Record>>(file.readText()).forEach { records[it.owner to it.occurrence] = it.ended }
            } catch (e: Exception) {
                Log.w(TAG, "Could not read the run records; starting empty", e)
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
            true -> OwnRun.ENDED
            false -> OwnRun.DIED
        }

    @Synchronized
    override fun started(
        owner: HexKey,
        occurrence: Long,
    ) = put(owner, occurrence, false)

    @Synchronized
    override fun ended(
        owner: HexKey,
        occurrence: Long,
    ) = put(owner, occurrence, true)

    @Synchronized
    override fun forget(
        owner: HexKey,
        occurrence: Long,
    ) {
        records.remove(owner to occurrence)
        save()
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
        save()
    }

    private fun save() {
        val file = file ?: return
        try {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(records.map { (k, v) -> Record(k.first, k.second, v) }))
            if (!tmp.renameTo(file)) Log.w(TAG) { "Could not replace the run records file" }
        } catch (e: Exception) {
            Log.w(TAG, "Could not write the run records", e)
        }
    }

    companion object {
        private const val TAG = "RunRecords"
    }
}
