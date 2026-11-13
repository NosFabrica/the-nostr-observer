package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.SavedEdition
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * A paper that was printed (and paid for) but not published: Blossom was down, a relay refused,
 * or `sign` failed. It is kept, page and all, and published again on later polls until [until],
 * as Amethyst's `publishPending` does. It is never printed again: its run is recorded as ended.
 */
@Serializable
class PendingPaper(
    val owner: HexKey,
    val occurrence: Long,
    val day: String,
    val code: String,
    val printedAt: Long,
    val model: String,
    val quotes: Int,
    val posts: Int,
    val headline: String?,
    val sha256: String,
    /** The page as it leaves the engine, with the paper's CSP: what [sha256] names. */
    val html: String,
    val readerName: String,
    /** Unix seconds after which it is dropped, published or not. */
    val until: Long,
) {
    fun edition() =
        SavedEdition(
            day = day,
            code = code,
            printedAt = printedAt,
            model = model,
            quotes = quotes,
            posts = posts,
            headline = headline,
            sha256 = sha256,
        )
}

/**
 * The papers waiting to be published, one per (owner, occurrence): an older paper that never got
 * out is not replaced by a newer one, it goes out with it (or after it). Kept in memory and, with
 * a [dir], one file per paper on the state volume, so a restart doesn't lose a paid paper. A paper
 * is kept BEFORE its first publish, so a worker stopped mid-publish still has it.
 */
class PendingPapers(
    private val dir: File?,
) {
    private data class Key(
        val owner: HexKey,
        val occurrence: Long,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val papers = ConcurrentHashMap<Key, PendingPaper>()

    /** The file each paper was read from: one written by an older worker is named by its owner alone. */
    private val files = ConcurrentHashMap<Key, File>()

    init {
        dir?.listFiles { f -> f.name.endsWith(".json") }?.forEach { file ->
            try {
                val paper = json.decodeFromString<PendingPaper>(file.readText())
                val key = Key(paper.owner, paper.occurrence)
                papers[key] = paper
                files[key] = file
            } catch (e: Exception) {
                Log.w(TAG, "Could not read a pending paper; leaving ${file.name} for an operator", e)
            }
        }
    }

    /** [owner]'s papers waiting, oldest occurrence first. */
    fun of(owner: HexKey): List<PendingPaper> = papers.values.filter { it.owner == owner }.sortedBy { it.occurrence }

    fun all(): List<PendingPaper> = papers.values.sortedWith(compareBy({ it.owner }, { it.occurrence }))

    fun keep(paper: PendingPaper) {
        val key = Key(paper.owner, paper.occurrence)
        papers[key] = paper
        val dir = dir ?: return
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("could not create ${dir.path}")
            val file = fileOf(key)
            val tmp = File(file.path + ".tmp")
            FileOutputStream(tmp).use { out ->
                out.write(json.encodeToString(paper).encodeToByteArray())
                out.fd.sync()
            }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            files.put(key, file)?.takeIf { it != file }?.let(::delete)
        } catch (e: IOException) {
            // Still kept in memory: retried until a restart.
            Log.w(TAG, "Could not write a pending paper to the state volume", e)
        }
    }

    fun drop(paper: PendingPaper) {
        val key = Key(paper.owner, paper.occurrence)
        papers.remove(key)
        files.remove(key)?.let(::delete)
    }

    private fun delete(file: File) {
        if (!file.delete() && file.exists()) Log.w(TAG) { "Could not delete ${file.name}" }
    }

    private fun fileOf(key: Key) = File(dir, "${key.owner}-${key.occurrence}.json")

    companion object {
        private const val TAG = "PendingPapers"

        /** How long a paid paper is retried: the morning's three-hour window and then some. */
        const val RETRY_SECONDS = 12 * 3_600L
    }
}
