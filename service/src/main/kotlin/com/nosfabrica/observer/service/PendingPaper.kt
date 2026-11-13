package com.nosfabrica.observer.service

import com.nosfabrica.assistant.core.Pending
import com.nosfabrica.assistant.core.PendingFile
import com.nosfabrica.observer.frontpage.SavedEdition
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.utils.Log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * A paper that was printed (and paid for) but not published: Blossom was down, a relay refused,
 * or a sign failed. It is kept, page and all, in `assistant_runner.pending` ([toPending]: the
 * edition's fields in `meta`, the page as a file), and published again until [until], as
 * Amethyst's `publishPending` does. It is never printed again: its run is ended. An older paper
 * that never got out isn't replaced by a newer one: it goes out with it.
 */
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

    /** As the store keeps it: the edition's fields in `meta`, the page in a file. */
    fun toPending(): Pending =
        Pending(
            task = ObserverRunner.TASK,
            owner = owner,
            occurrence = occurrence,
            meta = json.encodeToJsonElement(Meta(day, code, printedAt, model, quotes, posts, headline, sha256, readerName)).jsonObject,
            until = until,
            files = listOf(PendingFile.of(PAGE, PAGE_TYPE, html.encodeToByteArray())),
        )

    override fun toString() = "PendingPaper(owner=$owner, occurrence=$occurrence, day=$day)"

    /** The edition's fields, as `meta` holds them. */
    @Serializable
    private class Meta(
        val day: String,
        val code: String,
        val printedAt: Long,
        val model: String,
        val quotes: Int,
        val posts: Int,
        val headline: String? = null,
        val sha256: String,
        val readerName: String,
    )

    companion object {
        private const val TAG = "PendingPapers"

        /** How long a paid paper is retried: the morning's three-hour window and then some. */
        const val RETRY_SECONDS = 12 * 3_600L

        /** The page's path among the stored files. */
        const val PAGE = "/page.html"
        const val PAGE_TYPE = "text/html; charset=utf-8"

        private val json = Json { ignoreUnknownKeys = true }

        /** [pending] as a paper, or null when it isn't one of ours (no page, a `meta` that doesn't parse): logged, left for an operator. */
        fun of(pending: Pending): PendingPaper? {
            val page = pending.files.firstOrNull { it.path == PAGE }
            val meta =
                try {
                    json.decodeFromJsonElement<Meta>(pending.meta)
                } catch (e: Exception) {
                    null
                }
            if (page == null || meta == null) {
                Log.w(TAG) { "owner=${pending.owner} occurrence=${pending.occurrence}: a pending row that isn't a paper; left as it is" }
                return null
            }
            return PendingPaper(
                owner = pending.owner,
                occurrence = pending.occurrence,
                day = meta.day,
                code = meta.code,
                printedAt = meta.printedAt,
                model = meta.model,
                quotes = meta.quotes,
                posts = meta.posts,
                headline = meta.headline,
                sha256 = meta.sha256,
                html = page.bytes.decodeToString(),
                readerName = meta.readerName,
                until = pending.until,
            )
        }
    }
}
