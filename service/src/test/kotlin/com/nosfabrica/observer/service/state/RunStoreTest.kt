package com.nosfabrica.observer.service.state

import com.nosfabrica.observer.service.testing.TestPostgres
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims.OwnRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same rules on Postgres and in memory. */
abstract class RunStoreContract {
    abstract fun store(): RunStore

    private val owner = "a".repeat(64)

    @Test
    fun onlyOneOfManyConcurrentClaimsWins() =
        runBlocking {
            val store = store()
            val wins = List(8) { async(Dispatchers.IO) { store.claim("observer", owner, 100, "sched") } }.awaitAll()
            assertEquals(1, wins.count { it })
            assertEquals(RunState.STARTED, store.find("observer", owner, 100)!!.state)
            assertEquals("sched", store.find("observer", owner, 100)!!.scheduleId)
            // Another task, another occurrence: separate claims.
            assertTrue(store.claim("other", owner, 100))
            assertTrue(store.claim("observer", owner, 200))
        }

    @Test
    fun aRunThatSpentNothingIsForgottenAndMayRunAgain() =
        runBlocking {
            val store = store()
            assertTrue(store.claim("observer", owner, 100))
            assertTrue(store.isSettled("observer", owner, 100))
            assertTrue(store.forget("observer", owner, 100))
            assertNull(store.find("observer", owner, 100))
            assertFalse(store.isSettled("observer", owner, 100))
            assertTrue(store.claim("observer", owner, 100))
        }

    @Test
    fun anEndedRunIsNeverForgottenOrRunAgain() =
        runBlocking {
            val store = store()
            assertFalse(store.end("observer", owner, 100, "PRINTED"))
            assertTrue(store.claim("observer", owner, 100))
            assertTrue(store.end("observer", owner, 100, "PRINTED"))
            val row = store.find("observer", owner, 100)!!
            assertEquals(RunState.ENDED, row.state)
            assertEquals("PRINTED", row.outcome)
            assertTrue(row.endedAt != null)
            assertFalse(store.end("observer", owner, 100, "AGAIN"))
            assertFalse(store.forget("observer", owner, 100))
            assertFalse(store.claim("observer", owner, 100))
        }

    @Test
    fun quartzReadsTheRowsAsItsOwnRun() =
        runBlocking {
            val store = store()
            assertEquals(OwnRun.NONE, store.ownRun("observer", owner, 100))
            store.claim("observer", owner, 100)
            // Started and never ended: a runner never takes it up again.
            assertEquals(OwnRun.DIED, store.ownRun("observer", owner, 100))
            store.end("observer", owner, 100, "X")
            assertEquals(OwnRun.ENDED, store.ownRun("observer", owner, 100))
            store.claim("observer", owner, 300)
            store.claim("observer", owner, 200)
            assertEquals(listOf(300L, 200L, 100L), store.occurrences("observer", owner))
            assertEquals(listOf(300L), store.occurrences("observer", owner, limit = 1))
        }
}

class PostgresRunStoreTest : RunStoreContract() {
    override fun store(): RunStore {
        val db = TestPostgres.database()
        runBlocking { Migrations.migrate(db) }
        return PostgresRunStore(db)
    }
}

class MemoryRunStoreTest : RunStoreContract() {
    override fun store(): RunStore = MemoryRunStore()
}
