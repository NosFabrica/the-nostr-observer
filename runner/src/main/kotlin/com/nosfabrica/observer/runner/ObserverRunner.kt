package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.AgentModel
import com.nosfabrica.observer.frontpage.FrontPagePress
import com.nosfabrica.observer.frontpage.IRoleBasedHttpClientBuilder
import com.nosfabrica.observer.frontpage.ObserverPage
import com.nosfabrica.observer.frontpage.ObserverPublishResult
import com.nosfabrica.observer.frontpage.ObserverPublisher
import com.nosfabrica.observer.frontpage.PaperBoundary
import com.nosfabrica.observer.frontpage.PrintResult
import com.nosfabrica.observer.frontpage.SavedEdition
import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.chatComplete
import com.nosfabrica.observer.frontpage.listAgentModels
import com.nosfabrica.observer.frontpage.profilesFrom
import com.nosfabrica.observer.frontpage.publishedSha256
import com.nosfabrica.observer.frontpage.rankModels
import com.nosfabrica.observer.frontpage.shortNpub
import com.nosfabrica.observer.frontpage.withPaperCsp
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverParams
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSchedule
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverSite
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.TrustProviderListEvent
import com.vitorpamplona.quartz.nip85TrustedAssertions.list.tags.ProviderTypes
import com.vitorpamplona.quartz.nipB7Blossom.BlossomServersEvent
import com.vitorpamplona.quartz.nipFAAgentKeys.AgentKeyEvent
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFAAgentKeys.usableAgentKeys
import com.vitorpamplona.quartz.nipFBScheduledTasks.CronZone
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskClaims
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import com.vitorpamplona.quartz.nipFBScheduledTasks.ZoneIdCronZone
import com.vitorpamplona.quartz.utils.Log
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CancellationException

/** How one schedule went on one poll. Logged by name, with the owner: never a key or a plaintext. */
enum class RunOutcome {
    PRINTED,

    /** The schedule's public `p` isn't exactly one key (two `p` tags: NIP-FB's conflict, nobody runs it). */
    RUNNER_CONFLICT,

    /** Its author isn't the owner of the Assistant it names, or it names no Assistant of ours, or its signature is bad. */
    REJECTED,

    /** brainstorm_server has no Assistant for the owner, or refused to decrypt. */
    NO_ASSISTANT,
    DECRYPT_REFUSED,
    UNREADABLE,

    /** A param this code doesn't understand: NIP-FB says not to run it. */
    UNKNOWN_PARAMS,
    UNKNOWN_ZONE,
    NOT_RUNNABLE,
    NOT_DUE,

    /** This worker already ran the occurrence (and may have paid for it). */
    ATTEMPTED,

    /**
     * A device of the owner holds a live claim on the occurrence: it may be printing it now. Not
     * run, nothing spent; a later poll runs it only once that claim is stale and unfinished.
     */
    CLAIMED_BY_DEVICE,

    /** The occurrence's paper is on the owner's site or the Assistant's. */
    ALREADY_PRINTED,

    /** The Assistant's site couldn't be read: publishing would start from nothing. */
    SITE_UNREADABLE,

    /** The owner listed no Blossom server (kind 10063): nothing goes to a server they didn't choose. */
    NO_SERVERS,

    /** The Assistant has no write relays to publish its site to. */
    NO_RELAYS,

    /** The owner names no public rank provider (kind 10040): the ranking would silently be anonymous. */
    NO_LENS,

    /** The Assistant holds no usable AI key (its kind 12000). */
    NO_KEY,

    /** The key's URL is loopback, private or internal: the worker never calls it. */
    REFUSED_URL,

    /** No `model` param and the endpoint lists none. */
    NO_MODEL,

    /** The ranking isn't projected yet: nothing was spent, the next poll tries again. */
    NOT_READY,
    WRITER_FAILED,

    /** The page failed the page checks twice: never published. */
    PAGE_REFUSED,
    PUBLISH_FAILED,
    ERROR,
}

/** One print with the engine: the owner's corpus, the Assistant's key and model. Tests fake it. */
fun interface PaperPrinter {
    suspend fun print(
        key: AgentKeyTag,
        model: String,
        owner: HexKey,
        readerName: String,
    ): PrintResult
}

/** [PaperPrinter] with Amethyst's press: the same desks, writer and page checks as the apps. */
class PressPrinter(
    private val client: INostrClient,
    private val searchRelay: NormalizedRelayUrl,
    private val http: IRoleBasedHttpClientBuilder,
    private val boundary: PaperBoundary,
) : PaperPrinter {
    override suspend fun print(
        key: AgentKeyTag,
        model: String,
        owner: HexKey,
        readerName: String,
    ): PrintResult =
        FrontPagePress(
            client,
            searchRelay,
            boundary,
            { messages, onEvent -> http.chatComplete(key, model, messages, onEvent) },
        ).print(owner, readerName)
}

/**
 * The Assistant's print worker: one [poll] reads every owner's Observer schedule that names
 * their Assistant, checks it, decides, prints and publishes. See runner/README.md.
 */
class ObserverRunner(
    private val api: InternalApi,
    private val nostr: RunnerNostr,
    private val blossom: BlossomHttp,
    private val printer: PaperPrinter,
    private val records: RunRecords,
    /** The models an endpoint lists, for a schedule with no `model` param ([listAgentModels]). */
    private val models: suspend (AgentKeyTag) -> List<AgentModel>,
    private val zoneOf: (String?) -> CronZone? = { it?.let(ZoneIdCronZone::of) },
    private val now: () -> Long = { TimeUtils.now() },
) {
    /** Every (owner, Assistant) brainstorm_server holds, page by page. */
    suspend fun assistants(): List<AssistantPair> {
        val all = ArrayList<AssistantPair>()
        var after: HexKey? = null
        do {
            val page = api.assistants(after)
            all += page.items
            after = page.next?.takeIf { it != after }
        } while (after != null)
        return all
    }

    /** One pass over every schedule. Returns each owner's outcome, for tests and the log. */
    suspend fun poll(): Map<HexKey, RunOutcome> {
        val pairs = assistants()
        val byAssistant = pairs.associateBy { it.assistant }
        val outcomes = LinkedHashMap<HexKey, RunOutcome>()
        for (event in nostr.schedules(pairs)) {
            val outcome =
                try {
                    runSchedule(event, byAssistant)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: RunnerTokenRejectedException) {
                    throw e
                } catch (e: Exception) {
                    // The class only: a message could carry what a provider or server echoed.
                    Log.w(TAG) { "owner=${event.pubKey} outcome=${RunOutcome.ERROR} error=${e.javaClass.simpleName}" }
                    RunOutcome.ERROR
                }
            outcomes[event.pubKey] = outcome
        }
        return outcomes
    }

    /** Ends a schedule's run with [outcome], already reported. */
    private class Stop(
        val outcome: RunOutcome,
    ) : Exception(outcome.name)

    private fun stop(
        owner: HexKey,
        outcome: RunOutcome,
        detail: String? = null,
    ): Nothing = throw Stop(report(owner, outcome, detail))

    private suspend fun runSchedule(
        event: ScheduledTaskEvent,
        byAssistant: Map<HexKey, AssistantPair>,
    ): RunOutcome =
        try {
            runChecked(event, byAssistant)
        } catch (s: Stop) {
            s.outcome
        }

    private suspend fun runChecked(
        event: ScheduledTaskEvent,
        byAssistant: Map<HexKey, AssistantPair>,
    ): RunOutcome {
        val owner = event.pubKey
        val named = ScheduledTask(event.tags).runners
        // NIP-FB: with more than one `p`, nobody runs the task. Nothing is decrypted.
        if (named.size != 1) return report(owner, RunOutcome.RUNNER_CONFLICT, "${named.size} p tags")
        val pair = byAssistant[named[0]] ?: return report(owner, RunOutcome.REJECTED)
        // Being named is not enough: the author must be the owner of THAT Assistant, and it must verify.
        val check = ScheduledTaskClaims.checkRunner(event, pair.assistant, pair.owner)
        if (check != ScheduledTaskClaims.RunnerCheck.OK) return report(owner, RunOutcome.REJECTED, check.name)

        val task = ScheduledTask(event.tags, decryptTags(pair, event))
        if (!ObserverParams.understands(task)) return report(owner, RunOutcome.UNKNOWN_PARAMS)
        val zone = zoneOf(task.tz) ?: return report(owner, RunOutcome.UNKNOWN_ZONE)
        val at = now()

        val first =
            ScheduledTaskClaims.decideAsRunner(task, ObserverSchedule.TASK, pair.assistant, zone, at, ownRun = {
                records.ownRun(owner, it)
            })
        if (!first.shouldRun) return report(owner, verdictOutcome(first.verdict))
        val occurrence = first.occurrence!!

        // Both sites, before anything is spent: a print by the owner, a device or the Assistant covers the morning.
        val ownerRelays = nostr.writeRelays(owner)
        val assistantRelays = nostr.writeRelays(pair.assistant)
        val servers = ownerServers(owner, ownerRelays)
        val readerSite = ReaderSiteIO(owner, nostr, ownerRelays, servers, blossom)
        val assistantSite = AssistantSiteIO(owner, pair.assistant, api, nostr, assistantRelays, servers, blossom, now)
        val runnerEditions =
            when (val read = editionsOn(assistantSite, pair.assistant)) {
                SiteEditions.Unreadable -> return report(owner, RunOutcome.SITE_UNREADABLE)
                is SiteEditions.Read -> read.editions
            }
        // The owner's site unreadable: decide from the Assistant's own (and its records), as a device decides from its store.
        val readerEditions = (editionsOn(readerSite, owner) as? SiteEditions.Read)?.editions.orEmpty()
        val second =
            ScheduledTaskClaims.decideAsRunner(
                task,
                ObserverSchedule.TASK,
                pair.assistant,
                zone,
                at,
                ownRun = { records.ownRun(owner, it) },
            ) {
                ObserverSite.alreadyPrinted(readerEditions, runnerEditions, it, zone)
            }
        if (!second.shouldRun) return report(owner, verdictOutcome(second.verdict))

        if (servers.isEmpty()) return report(owner, RunOutcome.NO_SERVERS)
        if (assistantRelays.isEmpty()) return report(owner, RunOutcome.NO_RELAYS)
        if (!hasLens(owner, ownerRelays)) return report(owner, RunOutcome.NO_LENS)

        val key = assistantKey(pair, task, assistantRelays)
        // SSRF: the worker is inside Brainstorm's network. The guarded client checks again at connect time.
        UrlGuard.refusal(key.url)?.let { return report(owner, RunOutcome.REFUSED_URL, it) }
        val model = ObserverParams.model(task) ?: defaultModel(key) ?: return report(owner, RunOutcome.NO_MODEL)

        val name = readerName(owner, ownerRelays)
        records.started(owner, occurrence)
        val result =
            try {
                printer.print(key, model, owner, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WriterException) {
                records.ended(owner, occurrence)
                // The failure's name and status only: what the provider said may echo the request.
                return report(owner, RunOutcome.WRITER_FAILED, listOfNotNull(e.failure.name, e.status?.toString()).joinToString(" "))
            }
        return when (result) {
            is PrintResult.NotReady -> {
                records.forget(owner, occurrence)
                report(owner, RunOutcome.NOT_READY, result.verdict.name)
            }

            is PrintResult.Refused -> {
                records.ended(owner, occurrence)
                report(owner, RunOutcome.PAGE_REFUSED, "${result.violations.size} problems")
            }

            is PrintResult.Printed -> {
                records.ended(owner, occurrence)
                publish(pair, result, model, name, assistantSite)
            }
        }
    }

    private suspend fun publish(
        pair: AssistantPair,
        printed: PrintResult.Printed,
        model: String,
        name: String,
        site: AssistantSiteIO,
    ): RunOutcome {
        val at = now()
        // The page as it leaves the engine: with the paper's CSP, named by those bytes.
        val html = withPaperCsp(printed.html)
        val sha = publishedSha256(html)
        val edition =
            SavedEdition(
                day = ObserverSite.utcDay(at),
                code = printed.corpus.code,
                printedAt = at,
                model = model,
                quotes = printed.quotes,
                posts = printed.corpus.events.size,
                headline = printed.headline,
                sha256 = sha,
            )
        val publisher = ObserverPublisher(site, pair.assistant, { name }, now, forReader = pair.owner)
        return when (val published = publisher.publish(listOf(ObserverPage(ObserverPublisher.entryFor(edition, sha), html)))) {
            is ObserverPublishResult.Published -> report(pair.owner, RunOutcome.PRINTED)
            is ObserverPublishResult.Failed -> report(pair.owner, RunOutcome.PUBLISH_FAILED, published.reason.name)
        }
    }

    /** The schedule's private tags, decrypted by the server from the whole signed event. */
    private suspend fun decryptTags(
        pair: AssistantPair,
        event: ScheduledTaskEvent,
    ): Array<Array<String>> = parseTags(decrypt(pair, event)) ?: stop(pair.owner, RunOutcome.UNREADABLE)

    /** [event]'s content, decrypted by the owner's Assistant on the server. The plaintext stays in memory, unlogged. */
    private suspend fun decrypt(
        pair: AssistantPair,
        event: Event,
    ): String =
        try {
            api.decrypt(pair.owner, event)
        } catch (e: NoAssistantException) {
            stop(pair.owner, RunOutcome.NO_ASSISTANT)
        } catch (e: RefusedByServerException) {
            stop(pair.owner, RunOutcome.DECRYPT_REFUSED)
        }

    /** The Assistant's own NIP-FA list (kind 12000, by the Assistant), decrypted by the server, and the key the schedule picks. */
    private suspend fun assistantKey(
        pair: AssistantPair,
        task: ScheduledTask,
        relays: Set<NormalizedRelayUrl>,
    ): AgentKeyTag {
        val list =
            nostr.newest(pair.assistant, relays, Filter(kinds = listOf(AgentKeyEvent.KIND), authors = listOf(pair.assistant), limit = 4))
                ?: stop(pair.owner, RunOutcome.NO_KEY)
        val keys = parseTags(decrypt(pair, list))?.usableAgentKeys().orEmpty()
        return ObserverParams.key(task, keys) ?: stop(pair.owner, RunOutcome.NO_KEY)
    }

    private suspend fun defaultModel(key: AgentKeyTag): String? {
        val listed =
            try {
                models(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        return rankModels(listed.map { it.id }).firstOrNull()
    }

    /** The owner's Blossom servers (kind 10063), in their order, only those a guarded client may dial. */
    private suspend fun ownerServers(
        owner: HexKey,
        relays: Set<NormalizedRelayUrl>,
    ): List<String> {
        val list =
            nostr.newest(
                owner,
                relays,
                Filter(kinds = listOf(BlossomServersEvent.KIND), authors = listOf(owner), limit = 4),
            ) as? BlossomServersEvent
        return list
            ?.servers()
            .orEmpty()
            .map { it.trim().trimEnd('/') }
            .filter { UrlGuard.refusal(it) == null }
            .distinct()
    }

    /** A public rank provider in the owner's kind 10040: without one `observer:<owner>` silently ranks anonymously. */
    private suspend fun hasLens(
        owner: HexKey,
        relays: Set<NormalizedRelayUrl>,
    ): Boolean {
        val list =
            nostr.newest(
                owner,
                relays,
                Filter(kinds = listOf(TrustProviderListEvent.KIND), authors = listOf(owner), limit = 4),
            ) as? TrustProviderListEvent
        return list?.providerFor(ProviderTypes.rank) != null
    }

    private suspend fun readerName(
        owner: HexKey,
        relays: Set<NormalizedRelayUrl>,
    ): String {
        val profile = nostr.newest(owner, relays, Filter(kinds = listOf(0), authors = listOf(owner), limit = 4))
        return profile?.let { profilesFrom(listOf(it))[owner]?.name } ?: shortNpub(owner)
    }

    private fun verdictOutcome(verdict: ScheduledTaskClaims.RunnerVerdict): RunOutcome =
        when (verdict) {
            ScheduledTaskClaims.RunnerVerdict.NOT_THIS_RUNNER -> RunOutcome.REJECTED
            ScheduledTaskClaims.RunnerVerdict.RUNNER_CONFLICT -> RunOutcome.RUNNER_CONFLICT
            ScheduledTaskClaims.RunnerVerdict.NOT_RUNNABLE -> RunOutcome.NOT_RUNNABLE
            ScheduledTaskClaims.RunnerVerdict.NOT_DUE -> RunOutcome.NOT_DUE
            ScheduledTaskClaims.RunnerVerdict.ALREADY_DONE -> RunOutcome.ALREADY_PRINTED
            ScheduledTaskClaims.RunnerVerdict.ATTEMPTED_HERE -> RunOutcome.ATTEMPTED
            ScheduledTaskClaims.RunnerVerdict.CLAIMED_BY_DEVICE -> RunOutcome.CLAIMED_BY_DEVICE
            ScheduledTaskClaims.RunnerVerdict.RUN -> RunOutcome.ERROR
        }

    private fun report(
        owner: HexKey,
        outcome: RunOutcome,
        detail: String? = null,
    ): RunOutcome {
        // Owner and outcome; the detail is always one of ours (an enum name, a count, a guard reason).
        if (outcome == RunOutcome.NOT_DUE) {
            Log.d(TAG) { "owner=$owner outcome=$outcome" }
        } else {
            Log.i(TAG) { "owner=$owner outcome=$outcome" + (detail?.let { " detail=$it" } ?: "") }
        }
        return outcome
    }

    companion object {
        private const val TAG = "ObserverRunner"

        /** A decrypted tag array, or null when the plaintext isn't one. */
        fun parseTags(plaintext: String): Array<Array<String>>? =
            try {
                PrivateTagsInContent.decode(plaintext)
            } catch (e: Exception) {
                null
            }
    }
}
