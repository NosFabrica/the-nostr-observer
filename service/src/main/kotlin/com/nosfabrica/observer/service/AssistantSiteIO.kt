package com.nosfabrica.observer.service

import com.nosfabrica.observer.frontpage.ObserverPublisher
import com.nosfabrica.observer.frontpage.ObserverSiteIO
import com.nosfabrica.observer.frontpage.SiteRead
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.signers.EventTemplate
import com.vitorpamplona.quartz.nip5aStaticWebsites.NamedSiteEvent
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverEdition
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import com.vitorpamplona.quartz.nipB7Blossom.BlossomAuthorizationEvent
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** The newest of [events] by `created_at`, then lowest id, as NIP-01 picks a replaceable. */
internal fun newestOf(events: Collection<Event>): Event? =
    events.maxWithOrNull(compareBy<Event> { it.createdAt }.thenByDescending { it.id })

/** [event] as a [NamedSiteEvent], whatever class the parser gave it. */
internal fun asNamedSite(event: Event): NamedSiteEvent? =
    when {
        event is NamedSiteEvent -> event
        event.kind == NamedSiteEvent.KIND -> NamedSiteEvent(event.id, event.pubKey, event.createdAt, event.tags, event.content, event.sig)
        else -> null
    }

/**
 * The Assistant's Observer site for one owner (NIP-FB, Runner; Quartz's observer-site plan, "The
 * Assistant as runner"): kind 35128 `d=observer` signed by the Assistant with `["p", owner]`,
 * read from and published to the Assistant's write relays. Its files go only to the OWNER's
 * Blossom servers (their kind 10063, [ownerServers]), each upload authorised by the Assistant
 * ([AssistantSigner]). A server that refuses fails the upload; there is no fallback server.
 */
class AssistantSiteIO(
    private val owner: HexKey,
    private val assistant: HexKey,
    private val api: AssistantSigner,
    private val nostr: RunnerNostr,
    private val assistantRelays: Set<NormalizedRelayUrl>,
    private val ownerServers: List<String>,
    private val blossom: BlossomHttp,
    private val now: () -> Long = { TimeUtils.now() },
) : ObserverSiteIO {
    override suspend fun latestSite(author: HexKey): SiteRead {
        require(author == assistant) { "this IO reads the Assistant's site only" }
        val read = nostr.read(assistantRelays, ObserverSite.runnerSiteFilter(assistant, owner))
        // Only the Assistant's own site for THIS owner: a site by anyone else that tags them is not it.
        val site = ObserverSite.runnerSite(read.events, assistant, owner)
        return when {
            site != null -> SiteRead.Found(site)
            // Only the Assistant's own relays can say it has no site: the index relay answering
            // while all of them are down would read as "none", and the publish would rebuild the
            // archive from nothing.
            read.servedAny(assistantRelays) -> SiteRead.None
            else -> SiteRead.Unreadable
        }
    }

    override suspend fun servers(): List<String> = ownerServers

    override suspend fun download(
        servers: List<String>,
        sha256: HexKey,
    ): ByteArray? = blossom.download(servers.filter { UrlGuard.refusal(it) == null }, sha256)

    override suspend fun upload(
        servers: List<String>,
        bytes: ByteArray,
        sha256: HexKey,
        contentType: String,
    ): Boolean {
        // Never a server the owner didn't list, whatever the caller passed.
        val targets = servers.filter { it in ownerServers }
        if (targets.isEmpty()) return false
        val at = now()
        val template =
            BlossomAuthorizationEvent.build(
                verb = "upload",
                alt = "Upload $sha256",
                hash = sha256,
                size = bytes.size.toLong(),
                servers = targets,
                // The Assistant signs one at most 10 minutes ahead; 5 leaves room for clock skew.
                expiration = at + AUTH_SECONDS,
                createdAt = at,
            )
        // The Assistant refuses a 24242 with a `p` naming itself: never send one.
        check(template.tags.none { it.size > 1 && it[0] == "p" && it[1] == assistant }) { "an upload authorisation naming the Assistant" }
        val signed = api.sign(owner, template)
        if (signed.pubKey != assistant ||
            signed.kind != BlossomAuthorizationEvent.KIND
        ) {
            throw AssistantException("sign: the upload authorisation isn't the Assistant's")
        }
        val header = BlossomHttp.authHeader(signed)
        return coroutineScope {
            targets.map { server -> async { blossom.upload(server, bytes, sha256, contentType, header) } }.awaitAll().any { it }
        }
    }

    override suspend fun maxMessageLengths(): List<Int?> = emptyList()

    override suspend fun latestRunnerSite(
        runner: HexKey,
        reader: HexKey,
    ): SiteRead = if (runner == assistant && reader == owner) latestSite(assistant) else SiteRead.None

    // The Assistant adds Brainstorm's `client` tag to what it signs: one tag fewer for the editions.
    override suspend fun maxEventTags(): List<Int?> = listOf(ObserverSite.DEFAULT_MAX_TAGS - 1)

    override suspend fun publish(template: EventTemplate<NamedSiteEvent>): NamedSiteEvent {
        val signed = api.sign(owner, template)
        val site = asNamedSite(signed) ?: throw AssistantException("sign: not a named site")
        if (!ObserverSite.isRunnerSiteFor(
                site,
                assistant,
                owner,
            )
        ) {
            throw AssistantException("sign: not the Assistant's site for this owner")
        }
        val accepted = nostr.access.publish(site, assistantRelays)
        if (accepted == 0) throw AssistantException("no relay accepted the Observer site")
        return site
    }

    companion object {
        const val AUTH_SECONDS = 300L
    }
}

/**
 * The owner's own Observer site, read only: the service reads it to see whether the owner (or one
 * of their devices) already printed the morning. It never writes it: it can't sign as the owner.
 */
class ReaderSiteIO(
    private val owner: HexKey,
    private val nostr: RunnerNostr,
    private val ownerRelays: Set<NormalizedRelayUrl>,
    private val ownerServers: List<String>,
    private val blossom: BlossomHttp,
) : ObserverSiteIO {
    override suspend fun latestSite(author: HexKey): SiteRead {
        val filter = Filter(kinds = listOf(NamedSiteEvent.KIND), authors = listOf(owner), tags = mapOf("d" to listOf(ObserverSite.D_TAG)))
        val read = nostr.read(ownerRelays, filter)
        val site =
            newestOf(
                read.events.filter {
                    it.pubKey == owner && it.kind == NamedSiteEvent.KIND &&
                        it.tags.any { t -> t.size > 1 && t[0] == "d" && t[1] == ObserverSite.D_TAG }
                },
            )?.let(::asNamedSite)
        return when {
            site != null -> SiteRead.Found(site)
            // As for the Assistant's site: only the owner's own relays can say there is none.
            read.servedAny(ownerRelays) -> SiteRead.None
            else -> SiteRead.Unreadable
        }
    }

    override suspend fun servers(): List<String> = ownerServers

    override suspend fun download(
        servers: List<String>,
        sha256: HexKey,
    ): ByteArray? = blossom.download(servers.filter { UrlGuard.refusal(it) == null }, sha256)

    override suspend fun upload(
        servers: List<String>,
        bytes: ByteArray,
        sha256: HexKey,
        contentType: String,
    ): Boolean = false

    override suspend fun maxMessageLengths(): List<Int?> = emptyList()

    override suspend fun maxEventTags(): List<Int?> = emptyList()

    override suspend fun publish(template: EventTemplate<NamedSiteEvent>): NamedSiteEvent =
        throw UnsupportedOperationException("the service never signs as the owner")
}

/** The editions of [io]'s site (read through the engine's own reader), or null when it can't be read. */
suspend fun editionsOn(
    io: ObserverSiteIO,
    author: HexKey,
): SiteEditions {
    val read =
        try {
            io.latestSite(author)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ObserverRunner", "Could not read an Observer site", e)
            SiteRead.Unreadable
        }
    return when (read) {
        SiteRead.Unreadable -> SiteEditions.Unreadable
        SiteRead.None -> SiteEditions.Read(emptyList())
        is SiteRead.Found -> {
            val view = ObserverPublisher(SingleReadIO(io, read), author, { null }).read()
            SiteEditions.Read(view?.editions.orEmpty(), view?.servers.orEmpty())
        }
    }
}

/** A site's editions, or that it couldn't be read. */
sealed interface SiteEditions {
    data class Read(
        val editions: List<ObserverEdition>,
        /** Where its files are: the servers its pages are read back from. */
        val servers: List<String> = emptyList(),
    ) : SiteEditions

    data object Unreadable : SiteEditions
}

/** [io] with its site read once already: the engine's reader asks again, and the answer must not change. */
private class SingleReadIO(
    private val io: ObserverSiteIO,
    private val read: SiteRead,
) : ObserverSiteIO by io {
    override suspend fun latestSite(author: HexKey): SiteRead = read
}
