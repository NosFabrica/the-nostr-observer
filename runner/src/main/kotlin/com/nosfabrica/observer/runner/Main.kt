package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.AssistantKeys
import com.nosfabrica.assistant.core.Db
import com.nosfabrica.assistant.core.FernetKeyFile
import com.nosfabrica.assistant.core.Migrations
import com.nosfabrica.assistant.core.PostgresPendingStore
import com.nosfabrica.assistant.core.PostgresRunStore
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
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The worker's settings, all from the environment. The database password ([Db.fromEnv]) is the
 * one secret here, and it is never logged; the Assistants' keys are in [keysFile].
 */
class RunnerConfig(
    val pollSeconds: Long,
    val indexRelays: List<String>,
    val searchRelay: String,
    /** brainstorm_server's Fernet key file, which opens the Assistants' nsecs. */
    val keysFile: Path = Paths.get(FernetKeyFile.DEFAULT_PATH),
    /** On SIGTERM, how long the print under way may still take before the process exits anyway. */
    val graceSeconds: Long = DEFAULT_GRACE_SECONDS,
) {
    companion object {
        const val DEFAULT_SEARCH_RELAY = "wss://search.brainstorm.world"

        /** A print's budget and the publish after it: the pod's terminationGracePeriodSeconds must be longer. */
        const val DEFAULT_GRACE_SECONDS = ObserverRunner.PRINT_TIMEOUT_SECONDS + 60

        fun fromEnv(env: Map<String, String> = System.getenv()): RunnerConfig {
            val search = env["OBSERVER_SEARCH_RELAY"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SEARCH_RELAY
            return RunnerConfig(
                pollSeconds = env["OBSERVER_RUNNER_POLL_SECONDS"]?.toLongOrNull()?.coerceAtLeast(30) ?: 300,
                indexRelays =
                    env["INDEX_RELAYS"]
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.takeIf { it.isNotEmpty() }
                        ?: listOf(search),
                searchRelay = search,
                keysFile = Paths.get(env["NSEC_KEYS_FILE"]?.trim()?.takeIf { it.isNotEmpty() } ?: FernetKeyFile.DEFAULT_PATH),
                graceSeconds = env["OBSERVER_RUNNER_GRACE_SECONDS"]?.toLongOrNull()?.coerceAtLeast(0) ?: DEFAULT_GRACE_SECONDS,
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
            RunnerConfig.fromEnv() to Db.fromEnv()
        } catch (e: IllegalArgumentException) {
            System.err.println("observer-runner: ${e.message}")
            exitProcess(2)
        }
    Log.minLevel = LogLevel.INFO

    // The `assistant_runner` schema: whichever process starts first applies what is missing.
    try {
        runBlocking { Migrations.migrate(db) }.takeIf { it.isNotEmpty() }?.let { Log.i("ObserverRunner") { "migrated: $it" } }
    } catch (e: Exception) {
        // The class only: a driver's message may carry the URL.
        System.err.println("observer-runner: can't migrate the assistant_runner schema: ${e.javaClass.simpleName}")
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
    val idle = CountDownLatch(1)
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val search = RelayUrlNormalizer.normalizeOrNull(config.searchRelay) ?: error("OBSERVER_SEARCH_RELAY is not a relay URL")
    val index = RunnerNostr.relaysOf(config.indexRelays)
    val ours = index + search
    val client =
        NostrClient(
            BasicOkHttpWebSocket.Builder(
                { if (it in ours) guardedSockets else budgetedSockets },
                { UrlGuard.refusal(it.url, setOf("wss")) == null },
            ),
            scope,
        )
    val nostr = RunnerNostr(QuartzRelayAccess(client), index, search)
    val keys = FernetKeyFile(config.keysFile)
    // The count only: never a key.
    Log.i("ObserverRunner") { "the key file holds ${keys.keyCount()} keys" }
    val assistant = LocalAssistant(AssistantKeys(db, keys))
    val runner =
        ObserverRunner(
            api = assistant,
            pairs = { assistant.pairs() },
            nostr = nostr,
            blossom = BlossomHttp(blossomClient),
            printer = PressPrinter(client, search, http, JsoupPaperBoundary),
            runs = PostgresRunStore(db),
            models = { http.listAgentModels(it) },
            accepting = { !stopping.get() },
            // In Postgres beside the runs: a paid paper survives a restart.
            pending = PostgresPendingStore(db),
            // Only ever the key's own node; through the same guarded client as the print.
            routstr = RoutstrFunds(HttpRoutstrNode(http)),
        )

    // SIGTERM: no new print starts; the one under way may finish within the grace period.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            stopping.set(true)
            Log.i("ObserverRunner") { "stopping: no new prints; waiting up to ${config.graceSeconds}s for the current one" }
            if (!idle.await(config.graceSeconds, TimeUnit.SECONDS)) Log.w("ObserverRunner") { "stopping: the grace period ran out" }
        },
    )

    Log.i("ObserverRunner") { "started: polling every ${config.pollSeconds}s" }
    try {
        // Not the main thread's own loop: the poll's work spreads over the cores, and the blocking
        // writes (run records, pending papers) move to the IO dispatcher on their own.
        runBlocking(Dispatchers.Default) {
            while (!stopping.get()) {
                try {
                    val outcomes = runner.poll()
                    Log.i("ObserverRunner") { "poll done: ${outcomes.size} schedules, ${outcomes.values.groupingBy { it }.eachCount()}" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("ObserverRunner") { "poll failed: ${e.javaClass.simpleName}" }
                }
                // Woken every second to see a SIGTERM, rather than sleeping through the grace period.
                var waited = 0L
                while (waited < config.pollSeconds && !stopping.get()) {
                    delay(1000)
                    waited++
                }
            }
        }
    } finally {
        idle.countDown()
    }
}
