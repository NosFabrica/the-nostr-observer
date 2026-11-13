package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.AssistantKeys
import com.nosfabrica.assistant.core.BrainstormSchema
import com.nosfabrica.assistant.core.Db
import com.nosfabrica.assistant.core.Fernet
import com.nosfabrica.assistant.core.FernetKeyFile
import com.nosfabrica.assistant.core.Migrations
import com.nosfabrica.assistant.core.PostgresPendingStore
import com.nosfabrica.assistant.core.PostgresRunStore
import com.nosfabrica.assistant.core.RunState
import com.nosfabrica.assistant.core.TestPostgres
import com.nosfabrica.assistant.core.capturingLogs
import com.vitorpamplona.quartz.nip01Core.core.toHexKey
import com.vitorpamplona.quartz.nip19Bech32.toNsec
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.security.SecureRandom
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The service against a real Postgres (a throwaway cluster, or CI's service; skipped, saying so,
 * when there is neither): brainstorm_server's view and Fernet key file for the Assistant's key,
 * `assistant_runner.runs` for the claim and its end, `assistant_runner.pending` for the paper.
 * Relays, Blossom and the model are fakes, as everywhere.
 *
 * `runBlocking`, not `runTest`: the stores run on the IO dispatcher, and while the test thread
 * waits for them `runTest`'s virtual clock would run every relay deadline out.
 */
class PostgresServiceTest : RunnerWorld() {
    private val fernetKey = Base64.getUrlEncoder().encodeToString(ByteArray(32).also { SecureRandom().nextBytes(it) })
    private val keyFile = Files.createTempDirectory("keys").resolve("nsec_encryption_keys").also { Files.writeString(it, fernetKey) }
    private val nsec = assistant.keyPair.privKey!!.toNsec()
    private val nsecHex = assistant.keyPair.privKey!!.toHexKey()
    private val token = Fernet(fernetKey).encrypt(nsec.encodeToByteArray())

    /** A fresh database with brainstorm_server's side (the owner's Assistant, Fernet-encrypted) and the migrated schema. */
    private fun database(): Db {
        val db = TestPostgres.database()
        BrainstormSchema.create(db)
        BrainstormSchema.insert(db, owner.pubKey, assistant.pubKey, nsec, token)
        runBlocking { Migrations.migrate(db) }
        return db
    }

    private fun localAssistant(db: Db) = LocalAssistant(AssistantKeys(db, FernetKeyFile(keyFile)), { clock })

    @Test
    fun aPrintClaimsEndsAndIsNeverRunTwice() =
        runBlocking {
            val db = database()
            world()
            schedule()
            val runs = PostgresRunStore(db)
            val worker = runnerWith(api = localAssistant(db), runs = runs, pending = PostgresPendingStore(db))
            val (outcome, logs) = capturingLogs { worker.poll()[owner.pubKey] }
            assertEquals(RunOutcome.PRINTED, outcome)
            val row = assertNotNull(runs.find(ObserverRunner.TASK, owner.pubKey, six))
            assertEquals(RunState.ENDED, row.state)
            assertEquals("PRINTED", row.outcome)
            // Signed by the Assistant, from the key the view and the key file gave.
            val site = relays.published.single().first
            assertTrue(ObserverSite.isRunnerSiteFor(site, assistant.pubKey, owner.pubKey))
            // A new service (a restart, another replica) reads the row and doesn't print again.
            assertEquals(RunOutcome.ATTEMPTED, runnerWith(api = localAssistant(db), runs = PostgresRunStore(db)).poll()[owner.pubKey])
            assertEquals(1, printer.calls.size)
            for (secret in listOf(nsec, nsecHex, token, fernetKey, openRouter.key, secondOpenRouter.key)) {
                assertFalse(secret in logs, "a key or a token reached the log")
            }
        }

    @Test
    fun aPaperThatDidNotGoOutWaitsInPostgresAndGoesOutLater() =
        runBlocking {
            val db = database()
            world()
            schedule()
            val pending = PostgresPendingStore(db)
            blossom.down = true
            assertEquals(
                RunOutcome.PUBLISH_PENDING,
                runnerWith(api = localAssistant(db), runs = PostgresRunStore(db), pending = pending).poll()[owner.pubKey],
            )
            val waiting = pending.list(ObserverRunner.TASK, owner.pubKey).single()
            assertEquals(six, waiting.occurrence)
            val paper = assertNotNull(PendingPaper.of(waiting))
            assertEquals("2026-11-12", paper.day)
            assertTrue(paper.html.contains("Twelve relays"))
            assertEquals(clock + PendingPaper.RETRY_SECONDS, waiting.until)
            // Back up: a new service publishes it from the store, without printing again.
            blossom.down = false
            runnerWith(api = localAssistant(db), runs = PostgresRunStore(db), pending = PostgresPendingStore(db)).poll()
            assertEquals(1, printer.calls.size)
            assertTrue(relays.published.any { ObserverSite.isRunnerSiteFor(it.first, assistant.pubKey, owner.pubKey) })
            assertTrue(pending.list(ObserverRunner.TASK, owner.pubKey).isEmpty())
        }
}
