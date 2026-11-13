package com.nosfabrica.observer.runner

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
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * The worker's settings, all from the environment. The token is the one secret, and it is
 * never logged.
 */
class RunnerConfig(
    val serverUrl: String,
    val token: String,
    val pollSeconds: Long,
    val indexRelays: List<String>,
    val searchRelay: String,
    val stateFile: File?,
    /** On SIGTERM, how long the print under way may still take before the process exits anyway. */
    val graceSeconds: Long = DEFAULT_GRACE_SECONDS,
) {
    companion object {
        const val DEFAULT_SEARCH_RELAY = "wss://search.brainstorm.world"

        /** A print's budget and the publish after it: the pod's terminationGracePeriodSeconds must be longer. */
        const val DEFAULT_GRACE_SECONDS = ObserverRunner.PRINT_TIMEOUT_SECONDS + 60

        fun fromEnv(env: Map<String, String> = System.getenv()): RunnerConfig {
            fun need(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("$name is not set")
            val search = env["OBSERVER_SEARCH_RELAY"]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_SEARCH_RELAY
            return RunnerConfig(
                serverUrl = need("BRAINSTORM_SERVER_URL"),
                token = need("OBSERVER_RUNNER_TOKEN"),
                pollSeconds = env["OBSERVER_RUNNER_POLL_SECONDS"]?.toLongOrNull()?.coerceAtLeast(30) ?: 300,
                indexRelays =
                    env["OBSERVER_INDEX_RELAYS"]
                        ?.split(',')
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?: listOf(search),
                searchRelay = search,
                stateFile = env["OBSERVER_RUNNER_STATE"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::File),
                graceSeconds = env["OBSERVER_RUNNER_GRACE_SECONDS"]?.toLongOrNull()?.coerceAtLeast(0) ?: DEFAULT_GRACE_SECONDS,
            )
        }
    }
}

fun main() {
    val config =
        try {
            RunnerConfig.fromEnv()
        } catch (e: IllegalArgumentException) {
            System.err.println("observer-runner: ${e.message}")
            exitProcess(2)
        }
    Log.minLevel = LogLevel.INFO

    // One worker per state file: a second pod (an overlapping deploy) would print the same mornings.
    val lock =
        config.stateFile?.let {
            try {
                StateLock.acquire(it) ?: run {
                    System.err.println("observer-runner: another worker holds ${it.path}.lock; exiting")
                    exitProcess(3)
                }
            } catch (e: IOException) {
                System.err.println("observer-runner: can't lock ${it.path}.lock: ${e.javaClass.simpleName}")
                exitProcess(3)
            }
        }
    if (lock ==
        null
    ) {
        Log.w("ObserverRunner") { "OBSERVER_RUNNER_STATE is unset: the run records live in memory, and a restart forgets them" }
    }

    val base =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    // brainstorm_server is on the internal network: its client is the only unguarded one.
    val serverClient =
        base
            .newBuilder()
            .followRedirects(false)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    // Everything a reader named (AI endpoint, Blossom servers, relays) goes through the guard.
    val guarded = guardedClient(base, setOf("https"))
    val blossomClient = guarded.newBuilder().callTimeout(2, TimeUnit.MINUTES).build()
    // A call to the AI endpoint never outlives a print's budget, whatever the server sends.
    val moneyClient = guarded.newBuilder().callTimeout(ObserverRunner.PRINT_TIMEOUT_SECONDS, TimeUnit.SECONDS).build()
    val guardedSockets = guardedClient(base, setOf("wss"))
    val http =
        object : IRoleBasedHttpClientBuilder {
            override fun okHttpClientForMoney(url: String) = moneyClient

            override fun okHttpClientForUploads(url: String) = blossomClient
        }

    val stopping = AtomicBoolean(false)
    val idle = CountDownLatch(1)
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val client = NostrClient(BasicOkHttpWebSocket.Builder({ guardedSockets }, { UrlGuard.refusal(it.url, setOf("wss")) == null }), scope)
    val search = RelayUrlNormalizer.normalizeOrNull(config.searchRelay) ?: error("OBSERVER_SEARCH_RELAY is not a relay URL")
    val nostr = RunnerNostr(QuartzRelayAccess(client), RunnerNostr.relaysOf(config.indexRelays), search)
    val runner =
        ObserverRunner(
            api = HttpInternalApi(config.serverUrl, config.token, serverClient),
            nostr = nostr,
            blossom = BlossomHttp(blossomClient),
            printer = PressPrinter(client, search, http, JsoupPaperBoundary),
            records = FileRunRecords(config.stateFile),
            models = { http.listAgentModels(it) },
            accepting = { !stopping.get() },
            // Beside the run records, on the same volume: a paid paper survives a restart.
            pending = PendingPapers(config.stateFile?.let { File(it.absoluteFile.parentFile, "pending") }),
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
        runBlocking {
            while (!stopping.get()) {
                try {
                    val outcomes = runner.poll()
                    Log.i("ObserverRunner") { "poll done: ${outcomes.size} schedules, ${outcomes.values.groupingBy { it }.eachCount()}" }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RunnerTokenRejectedException) {
                    Log.e("ObserverRunner") { "brainstorm_server answers 404 to the runner: OBSERVER_RUNNER_TOKEN is wrong or unset there" }
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
        lock?.release()
    }
}
