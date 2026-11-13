package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.AssistantKeys
import com.nosfabrica.assistant.core.Db
import com.nosfabrica.assistant.core.FernetKeyFile
import com.nosfabrica.assistant.core.Migrations
import com.nosfabrica.assistant.core.PostgresPendingStore
import com.nosfabrica.assistant.core.PostgresRunStore
import com.nosfabrica.assistant.core.WakeRequest
import com.nosfabrica.observer.frontpage.IRoleBasedHttpClientBuilder
import com.nosfabrica.observer.frontpage.JsoupPaperBoundary
import com.nosfabrica.observer.frontpage.listAgentModels
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.LogLevel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The service's settings, all from the environment. The database password ([Db.fromEnv]) is the
 * one secret among them, and it is never logged; the Assistants' keys open with [keysFile].
 */
class ServiceConfig(
    /** The port `POST /v1/run` is served on. */
    val port: Int = DEFAULT_PORT,
    /** Owners' paths at once; a wake past them is answered 429. */
    val parallelism: Int = ObserverRunner.PARALLELISM,
    /** Seconds between the retry loop's passes over the papers still waiting. */
    val pendingSeconds: Long = DEFAULT_PENDING_SECONDS,
    val indexRelays: List<String> = listOf(DEFAULT_SEARCH_RELAY),
    val searchRelay: String = DEFAULT_SEARCH_RELAY,
    /** brainstorm_server's Fernet key file, which opens the Assistants' nsecs. */
    val keysFile: Path = Paths.get(FernetKeyFile.DEFAULT_PATH),
    /** On SIGTERM, how long the prints under way may still take before the process exits anyway. */
    val graceSeconds: Long = DEFAULT_GRACE_SECONDS,
) {
    companion object {
        const val DEFAULT_SEARCH_RELAY = "wss://search.brainstorm.world"
        const val DEFAULT_PORT = 8080
        const val DEFAULT_PENDING_SECONDS = 300L

        /** A print's budget and the publish after it: the pod's terminationGracePeriodSeconds must be longer. */
        const val DEFAULT_GRACE_SECONDS = ObserverRunner.PRINT_TIMEOUT_SECONDS + 60

        fun fromEnv(env: Map<String, String> = System.getenv()): ServiceConfig {
            fun value(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }

            fun number(
                name: String,
                range: LongRange,
            ): Long? =
                value(name)?.let {
                    it.toLongOrNull()?.takeIf { n -> n in range } ?: throw IllegalArgumentException("$name must be a number in $range")
                }
            val search = value("OBSERVER_SEARCH_RELAY") ?: DEFAULT_SEARCH_RELAY
            return ServiceConfig(
                port = number("PORT", 1L..65_535L)?.toInt() ?: DEFAULT_PORT,
                parallelism = number("PARALLELISM", 1L..64L)?.toInt() ?: ObserverRunner.PARALLELISM,
                pendingSeconds = number("PENDING_SECONDS", 30L..86_400L) ?: DEFAULT_PENDING_SECONDS,
                indexRelays =
                    value("INDEX_RELAYS")
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf(search),
                searchRelay = search,
                keysFile = Paths.get(value("NSEC_KEYS_FILE") ?: FernetKeyFile.DEFAULT_PATH),
                graceSeconds = number("GRACE_SECONDS", 0L..3_600L) ?: DEFAULT_GRACE_SECONDS,
            )
        }
    }
}

/** What one reader's relay socket may read per [RELAY_READ_WINDOW_MS]: far over any list or site, far under the heap. */
const val RELAY_READ_BUDGET_BYTES = 16L * 1024 * 1024

const val RELAY_READ_WINDOW_MS = 10_000L

fun main() {
    val (config, db) =
        try {
            ServiceConfig.fromEnv() to Db.fromEnv()
        } catch (e: IllegalArgumentException) {
            System.err.println("observer-service: ${e.message}")
            exitProcess(2)
        }
    Log.minLevel = LogLevel.INFO

    // The `assistant_runner` schema: whichever process starts first applies what is missing.
    try {
        runBlocking { Migrations.migrate(db) }.takeIf { it.isNotEmpty() }?.let { Log.i(TAG) { "migrated: $it" } }
    } catch (e: Exception) {
        // The class only: a driver's message may carry the URL.
        System.err.println("observer-service: can't migrate the assistant_runner schema: ${e.javaClass.simpleName}")
        exitProcess(3)
    }

    val base =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    // Everything a reader named (AI endpoint, Blossom servers, relays) goes through the guard.
    val guarded = guardedClient(base, setOf("https"))
    val blossomClient = guarded.newBuilder().callTimeout(2, TimeUnit.MINUTES).build()
    // A call to the AI endpoint never outlives a print's budget, whatever the server sends.
    val moneyClient = guarded.newBuilder().callTimeout(ObserverRunner.PRINT_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    // A reader's relays: each socket reads at most RELAY_READ_BUDGET per window, since OkHttp
    // bounds no inbound WebSocket message. The index relays are Brainstorm's own, and carry every
    // print's corpus: they aren't held to it.
    val guardedSockets = guardedClient(base, setOf("wss"))
    val budgetedSockets = guardedClient(base, setOf("wss"), readBudget = ReadBudget(RELAY_READ_BUDGET_BYTES, RELAY_READ_WINDOW_MS))
    val http =
        object : IRoleBasedHttpClientBuilder {
            override fun okHttpClientForMoney(url: String) = moneyClient

            override fun okHttpClientForUploads(url: String) = blossomClient
        }

    val stopping = AtomicBoolean(false)
    val stopped = CountDownLatch(1)
    val sockets = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // The wakes' runs: off the server's threads, spread over the cores.
    val runs = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val search = RelayUrlNormalizer.normalizeOrNull(config.searchRelay) ?: error("OBSERVER_SEARCH_RELAY is not a relay URL")
    val index = RunnerNostr.relaysOf(config.indexRelays)
    val ours = index + search
    val client =
        NostrClient(
            BasicOkHttpWebSocket.Builder(
                { if (it in ours) guardedSockets else budgetedSockets },
                { UrlGuard.refusal(it.url, setOf("wss")) == null },
            ),
            sockets,
        )
    val keys = FernetKeyFile(config.keysFile)
    // The count only: never a key.
    Log.i(TAG) { "the key file holds ${keys.keyCount()} keys" }
    val runStore = PostgresRunStore(db)
    // In Postgres beside the runs: a paid paper survives a restart.
    val pending = PostgresPendingStore(db)
    val runner =
        ObserverRunner(
            api = LocalAssistant(AssistantKeys(db, keys)),
            nostr = RunnerNostr(QuartzRelayAccess(client), index, search),
            blossom = BlossomHttp(blossomClient),
            printer = PressPrinter(client, search, http, JsoupPaperBoundary),
            runs = runStore,
            models = { http.listAgentModels(it) },
            accepting = { !stopping.get() },
            pending = pending,
            // Only ever the key's own node; through the same guarded client as the print.
            routstr = RoutstrFunds(HttpRoutstrNode(http)),
        )
    val service = WakeService(runner, runStore, pending, runs, config.parallelism, accepting = { !stopping.get() })
    val server =
        try {
            WakeServer(service, config.port).also { it.start() }
        } catch (e: IOException) {
            System.err.println("observer-service: can't listen on port ${config.port}: ${e.javaClass.simpleName}")
            exitProcess(3)
        }

    // SIGTERM: no new wake is taken, and the prints under way may finish within the grace
    // period; a paper they print is in the store before it is published, so it isn't lost.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            stopping.set(true)
            server.stop()
            Log.i(TAG) { "stopping: no new wakes; waiting up to ${config.graceSeconds}s for ${service.busy} runs" }
            if (!runBlocking { service.drain(config.graceSeconds * 1000) }) Log.w(TAG) { "stopping: the grace period ran out" }
            stopped.await(5, TimeUnit.SECONDS)
        },
    )

    Log.i(TAG) { "started: POST ${WakeRequest.PATH} on port ${server.port}, ${config.parallelism} at a time" }
    try {
        runBlocking(Dispatchers.Default) {
            while (!stopping.get()) {
                // Woken every second to see a SIGTERM, rather than sleeping through the grace period.
                var waited = 0L
                while (waited < config.pendingSeconds && !stopping.get()) {
                    delay(1000)
                    waited++
                }
                if (stopping.get()) break
                try {
                    service.republishPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG) { "the retry pass failed: ${e.javaClass.simpleName}" }
                }
            }
        }
    } finally {
        stopped.countDown()
    }
}

private const val TAG = "ObserverService"
