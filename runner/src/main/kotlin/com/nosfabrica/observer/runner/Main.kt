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
import java.util.concurrent.TimeUnit
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
) {
    companion object {
        const val DEFAULT_SEARCH_RELAY = "wss://search.brainstorm.world"

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

    val base =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    // brainstorm_server is on the internal network: its client is the only unguarded one.
    val serverClient = base.newBuilder().followRedirects(false).build()
    // Everything a reader named (AI endpoint, Blossom servers, relays) goes through the guard.
    val guarded = guardedClient(base, setOf("https"))
    val guardedSockets = guardedClient(base, setOf("wss"))
    val http =
        object : IRoleBasedHttpClientBuilder {
            override fun okHttpClientForMoney(url: String) = guarded

            override fun okHttpClientForUploads(url: String) = guarded
        }

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val client = NostrClient(BasicOkHttpWebSocket.Builder({ guardedSockets }, { UrlGuard.refusal(it.url, setOf("wss")) == null }), scope)
    val search = RelayUrlNormalizer.normalizeOrNull(config.searchRelay) ?: error("OBSERVER_SEARCH_RELAY is not a relay URL")
    val nostr = RunnerNostr(QuartzRelayAccess(client), RunnerNostr.relaysOf(config.indexRelays), search)
    val runner =
        ObserverRunner(
            api = HttpInternalApi(config.serverUrl, config.token, serverClient),
            nostr = nostr,
            blossom = BlossomHttp(guarded),
            printer = PressPrinter(client, search, http, JsoupPaperBoundary),
            records = FileRunRecords(config.stateFile),
            models = { http.listAgentModels(it) },
        )

    Log.i("ObserverRunner") { "started: polling every ${config.pollSeconds}s" }
    runBlocking {
        while (true) {
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
            delay(config.pollSeconds * 1000)
        }
    }
}
