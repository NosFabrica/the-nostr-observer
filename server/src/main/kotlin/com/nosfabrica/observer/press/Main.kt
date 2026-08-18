package com.nosfabrica.observer.press

import com.anthropic.models.messages.OutputConfig
import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Relays
import com.nosfabrica.observer.press.auth.Bunkers
import com.nosfabrica.observer.press.auth.Sessions
import com.nosfabrica.observer.press.auth.SignIn
import com.nosfabrica.observer.press.publish.Announce
import com.nosfabrica.observer.press.publish.Blossom
import com.nosfabrica.observer.press.store.Continuities
import com.nosfabrica.observer.press.store.Db
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Everything the app needs, read once from the environment.
 *
 * Nothing here has a secret in it except the model key, which is read by the
 * Anthropic client itself from `ANTHROPIC_API_KEY` and never passes through
 * this object. The reason there is a server at all is that the key and the
 * system prompt must live somewhere the reader cannot read them.
 */
data class Config(
    val port: Int = env("PORT")?.toIntOrNull() ?: 8080,
    val host: String = env("HOST") ?: "0.0.0.0",
    val database: String = env("OBSERVER_DB") ?: "observer.db",
    /**
     * What this server is called from outside. NOT derived from the request.
     *
     * A NIP-98 signature names the URL it is for, and that check is only worth
     * anything if the URL it is compared against is OURS. Reconstructing it from
     * `Host` or `X-Forwarded-Host` hands the comparison to the caller: any site
     * can ask a visitor to sign an event for a URL that site controls, and then
     * replay it here with a matching header to be signed in as them. That was a
     * real hole here, and it is what these two tests hold shut.
     *
     * The default is only right for local work. A deployment must set this.
     */
    val publicUrl: String = env("OBSERVER_PUBLIC_URL") ?: "http://localhost:${env("PORT")?.toIntOrNull() ?: 8080}",
    val searchRelay: String = env("OBSERVER_RELAY") ?: "wss://search-staging.brainstorm.world",
    val effort: String = env("OBSERVER_EFFORT") ?: "high",
    /**
     * Whether a cookie may travel over plain HTTP.
     *
     * Defaults to demanding HTTPS. A deployment that turns this off is saying
     * it terminates TLS somewhere else, and saying it out loud.
     */
    val insecureCookies: Boolean = env("OBSERVER_INSECURE_COOKIES") == "true",
) {
    companion object {
        private fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
    }
}

/** Everything wired together, in one place, so a test can build it differently. */
class App(
    val config: Config = Config(),
) {
    val scope = CoroutineScope(SupervisorJob())
    val db = Db(config.database)
    val relays = Relays()
    val sessions = Sessions()
    val bunkers = Bunkers(relays.client)
    val signIn = SignIn()
    val runs = Runs()
    val continuities = Continuities(db)
    val blossom = Blossom()
    val press = Press(relays, config.searchRelay, effort(config.effort))
    val announce = Announce(relays, config.searchRelay, press)
    val editions = Editions(press, runs, announce, continuities, scope)

    /**
     * Everything that expires, swept on a timer rather than on access.
     *
     * Runs and sessions both have a TTL, and both used to be enforced only when
     * something happened to touch them. That is not expiry: a reader who
     * abandons a print, or signs in once from a phone and never returns, leaves
     * something nothing ever looks at again — and an abandoned run is holding a
     * whole edition in memory.
     */
    fun housekeeping() =
        scope.launch {
            while (isActive) {
                delay(10 * 60 * 1000L)
                runCatching {
                    val gone = runs.sweep() + sessions.sweep()
                    if (gone > 0) log.info("swept $gone expired run(s) and session(s)")
                }.onFailure { log.warn("housekeeping failed", it) }
            }
        }

    fun close() {
        scope.cancel()
        // The press owns a lazily-started browser for the proof render, so it
        // closes before the things it does not own.
        press.close()
        relays.close()
        db.close()
    }

    private val log = LoggerFactory.getLogger(App::class.java)

    private fun effort(name: String) =
        when (name.lowercase()) {
            "low" -> OutputConfig.Effort.LOW
            "medium" -> OutputConfig.Effort.MEDIUM
            "xhigh" -> OutputConfig.Effort.XHIGH
            "max" -> OutputConfig.Effort.MAX
            else -> OutputConfig.Effort.HIGH
        }
}

fun main() {
    val app = App()
    println("observer-press on ${app.config.host}:${app.config.port}, reading ${app.config.searchRelay}")
    println("  public url ${app.config.publicUrl} (sign-in signatures must name it)")
    if (System.getenv("OBSERVER_PUBLIC_URL").isNullOrBlank()) {
        println("  OBSERVER_PUBLIC_URL is unset: every sign-in will be rejected unless readers reach this exact address")
    }
    if (System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) {
        println("  no ANTHROPIC_API_KEY: readiness and previews will work, generation will not")
    }
    app.housekeeping()

    val server = embeddedServer(Netty, port = app.config.port, host = app.config.host) { routes(app) }
    // Sockets and the database file get closed on the way out. Without this a
    // restart leaves a WAL to recover and relay connections to time out.
    Runtime.getRuntime().addShutdownHook(Thread { app.close() })
    server.start(wait = true)
}
