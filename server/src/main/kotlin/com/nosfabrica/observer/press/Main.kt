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
import com.nosfabrica.observer.press.store.Drafts
import com.nosfabrica.observer.press.store.Published
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

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
    val drafts = Drafts(db)
    val continuities = Continuities(db)
    val published = Published(db)
    val announce = Announce(relays, config.searchRelay)
    val blossom = Blossom()
    val press = Press(relays, config.searchRelay, effort(config.effort))
    val editions = Editions(press, drafts, continuities, scope)

    /**
     * Templates issued but not yet signed, held only in memory.
     *
     * They exist to be compared against what comes back, so they need to
     * survive a round trip through a signer and nothing longer. Losing them on
     * restart costs the reader one press of Prepare.
     */
    val pending = java.util.concurrent.ConcurrentHashMap<String, Pending>()

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
    if (System.getenv("ANTHROPIC_API_KEY").isNullOrBlank()) {
        println("  no ANTHROPIC_API_KEY: readiness and previews will work, generation will not")
    }
    embeddedServer(Netty, port = app.config.port, host = app.config.host) { routes(app) }.start(wait = true)
}
