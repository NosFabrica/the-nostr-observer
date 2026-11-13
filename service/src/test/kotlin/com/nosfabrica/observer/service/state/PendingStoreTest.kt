package com.nosfabrica.observer.service.state

import com.nosfabrica.observer.service.testing.TestPostgres
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

abstract class PendingStoreContract {
    abstract fun store(): PendingStore

    private val owner = "b".repeat(64)

    private fun paper(
        occurrence: Long,
        until: Long = 1_000,
        headline: String = "Twelve relays",
    ) = Pending(
        "observer",
        owner,
        occurrence,
        buildJsonObject {
            put("day", "2026-11-12")
            put("headline", headline)
        },
        until,
        listOf(
            PendingFile.of("/2026-11-12.html", "text/html", "<h1>$headline</h1>".encodeToByteArray()),
            PendingFile.of("/editions.json", "application/json", "[]".encodeToByteArray()),
        ),
    )

    @Test
    fun anOlderPaperWaitsBesideANewerOne() =
        runBlocking {
            val store = store()
            store.put(paper(200))
            store.put(paper(100))
            store.put(Pending("other", owner, 100, buildJsonObject {}, 1_000, emptyList()))
            val mine = store.list("observer", owner)
            assertEquals(listOf(100L, 200L), mine.map { it.occurrence })
            val first = mine.first()
            assertEquals("Twelve relays", first.meta["headline"]!!.jsonPrimitive.content)
            assertEquals(listOf("/2026-11-12.html", "/editions.json"), first.files.map { it.path }.sorted())
            val page = first.files.single { it.path == "/2026-11-12.html" }
            assertContentEquals("<h1>Twelve relays</h1>".encodeToByteArray(), page.bytes)
            assertEquals("text/html", page.contentType)
            assertEquals(sha256Hex(page.bytes), page.sha256)
            assertTrue(store.list("observer", "c".repeat(64)).isEmpty())
        }

    @Test
    fun theOwnersWithAnOutputWaitingAreListedOncePerTask() =
        runBlocking {
            val store = store()
            assertEquals(emptyList(), store.owners("observer"))
            val other = "a".repeat(64)
            store.put(paper(100))
            store.put(paper(200))
            store.put(Pending("observer", other, 100, buildJsonObject {}, 1_000, emptyList()))
            store.put(Pending("other", "c".repeat(64), 100, buildJsonObject {}, 1_000, emptyList()))
            assertEquals(listOf(other, owner), store.owners("observer"))
            store.delete("observer", other, 100)
            assertEquals(listOf(owner), store.owners("observer"))
        }

    @Test
    fun aPutReplacesTheSameOccurrence() =
        runBlocking {
            val store = store()
            store.put(paper(100))
            store.put(
                paper(100, until = 2_000, headline = "Later").let { Pending(it.task, it.owner, 100, it.meta, 2_000, it.files.take(1)) },
            )
            val only = store.list("observer", owner).single()
            assertEquals(2_000, only.until)
            assertEquals("Later", only.meta["headline"]!!.jsonPrimitive.content)
            assertEquals(1, only.files.size)
        }

    @Test
    fun deleteAndDropExpired() =
        runBlocking {
            val store = store()
            store.put(paper(100, until = 500))
            store.put(paper(200, until = 1_500))
            store.put(paper(300, until = 2_500))
            assertTrue(store.delete("observer", owner, 200))
            assertFalse(store.delete("observer", owner, 200))
            assertEquals(1, store.dropExpired(now = 1_000))
            assertEquals(listOf(300L), store.list("observer", owner).map { it.occurrence })
        }

    @Test
    fun aFileWhoseHashDoesNotNameItIsRefused() =
        runBlocking {
            val store = store()
            val wrong = PendingFile("/a.html", "0".repeat(64), "text/html", "x".encodeToByteArray())
            assertFailsWith<IllegalArgumentException> { store.put(Pending("observer", owner, 1, buildJsonObject {}, 1, listOf(wrong))) }
            assertTrue(store.list("observer", owner).isEmpty())
        }
}

class PostgresPendingStoreTest : PendingStoreContract() {
    override fun store(): PendingStore {
        val db = TestPostgres.database()
        runBlocking { Migrations.migrate(db) }
        return PostgresPendingStore(db)
    }
}

class MemoryPendingStoreTest : PendingStoreContract() {
    override fun store(): PendingStore = MemoryPendingStore()
}
