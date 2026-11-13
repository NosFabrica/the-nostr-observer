package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.AgentModel
import com.nosfabrica.observer.frontpage.FrontPagePress
import com.nosfabrica.observer.frontpage.IRoleBasedHttpClientBuilder
import com.nosfabrica.observer.frontpage.ObserverPage
import com.nosfabrica.observer.frontpage.ObserverPublishResult
import com.nosfabrica.observer.frontpage.ObserverPublisher
import com.nosfabrica.observer.frontpage.PaperBoundary
import com.nosfabrica.observer.frontpage.PrintResult
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
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.EventListener
import java.util.concurrent.ConcurrentHashMap

/** How one schedule went on one poll. Logged by name, with the owner: never a key or a plaintext. */
enum class RunOutcome {
    PRINTED,

    /** Printed and paid for, but not published (Blossom, a relay or `sign` failed): kept, and published again on later polls. */
    PUBLISH_PENDING,

    /** The owner's newest schedule names no runner: they took printing back. */
    NOT_NAMED,

    /** Too few of the owner's write relays answered to trust the schedule read is the newest: nothing is spent. */
    NO_QUORUM,

    /** The schedule changed between the decision and the print (re-read right before spending): the next poll decides again. */
    SCHEDULE_CHANGED,

    /**
     * The `key` param names an endpoint the Assistant's list doesn't have, and the schedule names
     * a `model`: that model is never asked of another endpoint. Nothing spent; not tried again
     * for this occurrence.
     */
    KEY_NOT_GIVEN,

    /** The schedule's public `p` isn't exactly one key (two `p` tags: NIP-FB's conflict, nobody runs it). */
    RUNNER_CONFLICT,

    /** Its author isn't the owner of the Assistant it names, it names another key than the owner's Assistant, or its signature is bad. */
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

    /** The print ran past [ObserverRunner.PRINT_TIMEOUT_SECONDS]: stopped, and (it may have spent) not run again. */
    TIMED_OUT,

    /** The run records can't be written or read ([RunRecordsException]): nothing is printed until an operator looks. */
    RECORDS_UNAVAILABLE,

    /** The worker is shutting down: no new print starts. */
    STOPPING,

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

/**
 * [PaperPrinter] with Amethyst's press: the same desks, writer and page checks as the apps.
 *
 * Each print's calls to the AI endpoint are cancelled the moment the print is (its timeout, or
 * the worker stopping): the engine reads a stream with blocking reads, which a coroutine's
 * cancellation alone doesn't interrupt, so a server sending a byte every few minutes would
 * otherwise hold the print until the client's own timeouts.
 */
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
        cancellingCalls(http) { tracked ->
            FrontPagePress(
                client,
                searchRelay,
                boundary,
                { messages, onEvent -> tracked.chatComplete(key, model, messages, onEvent) },
            ).print(owner, readerName)
        }
}

/**
 * [block] with an [IRoleBasedHttpClientBuilder] whose money calls are all cancelled the moment
 * [block] is (a timeout, the worker stopping), not when their blocking reads next return.
 */
suspend fun <T> cancellingCalls(
    http: IRoleBasedHttpClientBuilder,
    block: suspend (IRoleBasedHttpClientBuilder) -> T,
): T =
    coroutineScope {
        val calls = ConcurrentHashMap.newKeySet<Call>()
        val tracked =
            object : IRoleBasedHttpClientBuilder {
                override fun okHttpClientForMoney(url: String) =
                    http
                        .okHttpClientForMoney(url)
                        .newBuilder()
                        .eventListenerFactory { call ->
                            calls += call
                            EventListener.NONE
                        }.build()

                override fun okHttpClientForUploads(url: String) = http.okHttpClientForUploads(url)
            }
        // Suspended until the block ends one way or the other; cancelled with it, it cancels the calls.
        val watchdog =
            launch {
                try {
                    awaitCancellation()
                } finally {
                    calls.forEach { it.cancel() }
                }
            }
        try {
            block(tracked)
        } finally {
            watchdog.cancel()
        }
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
    /** How many owners' schedules run at once: one slow reader's server never holds up the rest. */
    private val parallelism: Int = PARALLELISM,
    /** The longest a print may take, the model's calls included ([PRINT_TIMEOUT_SECONDS]). */
    private val printTimeoutMs: Long = PRINT_TIMEOUT_SECONDS * 1000,
    /** False once the worker is stopping: a run that hasn't started its print yet doesn't. */
    private val accepting: () -> Boolean = { true },
    /** Papers printed but not yet published ([PendingPaper]). */
    private val pending: PendingPapers = PendingPapers(null),
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

    /**
     * What this worker already settled for an owner's occurrence, so a poll doesn't read both
     * sites and decrypt the keys again every few minutes until the next one is due: [outcome]
     * holds for [scheduleId]'s [occurrence] until [until] (the occurrence's end for a paper that
     * exists or a run that ended, an hour for something the owner can fix, like a missing 10063).
     */
    private class Settled(
        val scheduleId: String,
        val occurrence: Long,
        val outcome: RunOutcome,
        val until: Long,
    )

    private val settled = ConcurrentHashMap<HexKey, Settled>()

    /** Decrypted schedule tags by event id: an event never changes, so neither does its plaintext. Pruned to the schedules of the last poll. */
    private val decryptedTags = ConcurrentHashMap<String, Array<Array<String>>>()

    /** One pass over every schedule. Returns each owner's outcome, for tests and the log. */
    suspend fun poll(): Map<HexKey, RunOutcome> {
        val pairs = assistants()
        val byOwner = pairs.associateBy { it.owner }
        val gate = Semaphore(parallelism)
        val reads = nostr.schedules(byOwner.keys)
        decryptedTags.keys.retainAll(reads.map { it.event.id }.toSet())
        settled.keys.retainAll(byOwner.keys)
        val ran =
            coroutineScope {
                reads
                    .mapNotNull { read -> byOwner[read.event.pubKey]?.let { read to it } }
                    .map { (read, pair) ->
                        async {
                            gate.withPermit {
                                pair.owner to
                                    try {
                                        runSchedule(read, pair)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: RunnerTokenRejectedException) {
                                        throw e
                                    } catch (e: Exception) {
                                        // The class only: a message could carry what a provider or server echoed.
                                        Log.w(TAG) { "owner=${pair.owner} outcome=${RunOutcome.ERROR} error=${e.javaClass.simpleName}" }
                                        RunOutcome.ERROR
                                    }
                            }
                        }
                    }.awaitAll()
            }
        // A pending paper whose owner no longer has an Assistant, or is past its time, goes.
        pending.owners().forEach { owner ->
            val paper = pending.of(owner) ?: return@forEach
            if (owner !in byOwner || now() > paper.until) {
                pending.drop(owner)
                report(owner, RunOutcome.PUBLISH_FAILED, "pending paper dropped")
            }
        }
        return ran.toMap(LinkedHashMap())
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
        read: ScheduleRead,
        pair: AssistantPair,
    ): RunOutcome =
        try {
            runChecked(read, pair)
        } catch (s: Stop) {
            s.outcome
        }

    private suspend fun runChecked(
        read: ScheduleRead,
        pair: AssistantPair,
    ): RunOutcome {
        val event = read.event
        val owner = pair.owner
        val named = ScheduledTask(event.tags).runners
        // The owner took printing back (their newest version names no runner): nothing to do, said quietly.
        if (named.isEmpty()) return report(owner, RunOutcome.NOT_NAMED)
        // NIP-FB: with more than one `p`, nobody runs the task. Nothing is decrypted.
        if (named.size != 1) return report(owner, RunOutcome.RUNNER_CONFLICT, "${named.size} p tags")
        // Being named is not enough: the author must be the owner of THAT Assistant, and it must verify.
        val check = ScheduledTaskClaims.checkRunner(event, pair.assistant, pair.owner)
        if (check != ScheduledTaskClaims.RunnerCheck.OK) return report(owner, RunOutcome.REJECTED, check.name)
        // Too few of the owner's relays answered: a newer version (printing taken back) may be on the others.
        if (!read.quorum) return report(owner, RunOutcome.NO_QUORUM)

        // Both NIP-65 lists are cached; the owner's 10063 is read only once something needs it.
        val ownerRelays = nostr.writeRelays(owner)
        val assistantRelays = nostr.writeRelays(pair.assistant)
        var serversRead: List<String>? = null

        suspend fun servers() = serversRead ?: ownerServers(owner, ownerRelays).also { serversRead = it }

        suspend fun assistantSite() = AssistantSiteIO(owner, pair.assistant, api, nostr, assistantRelays, servers(), blossom, now)
        // A paper already paid for goes out first, before anything else is decided.
        pending.of(owner)?.let { publishPending(pair, it, assistantSite()) }

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
        settled[owner]?.let { s ->
            if (s.scheduleId == event.id && s.occurrence == occurrence &&
                at < s.until
            ) {
                return report(owner, s.outcome, "settled", quiet = true)
            }
        }
        val nextDue = task.expression()?.nextAfter(occurrence, zone) ?: (occurrence + DAY_SECONDS)

        fun settle(
            outcome: RunOutcome,
            detail: String? = null,
        ): RunOutcome {
            val until = if (outcome in FIXABLE) minOf(at + FIXABLE_RETRY_SECONDS, nextDue) else nextDue
            settled[owner] = Settled(event.id, occurrence, outcome, until)
            return report(owner, outcome, detail)
        }

        if (assistantRelays.isEmpty()) return settle(RunOutcome.NO_RELAYS)
        // Both sites, before anything is spent: a print by the owner, a device or the Assistant covers the morning.
        val servers = servers()
        val assistantSite = assistantSite()
        val readerSite = ReaderSiteIO(owner, nostr, ownerRelays, servers, blossom)
        val runnerEditions =
            when (val site = editionsOn(assistantSite, pair.assistant)) {
                SiteEditions.Unreadable -> return report(owner, RunOutcome.SITE_UNREADABLE)
                is SiteEditions.Read -> site.editions
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
        if (!second.shouldRun) {
            val outcome = verdictOutcome(second.verdict)
            return if (outcome == RunOutcome.ALREADY_PRINTED) settle(outcome) else report(owner, outcome)
        }

        if (servers.isEmpty()) return settle(RunOutcome.NO_SERVERS)
        if (!hasLens(owner, ownerRelays)) return settle(RunOutcome.NO_LENS)

        val key = assistantKey(pair, task, event, assistantRelays) ?: return settle(RunOutcome.NO_KEY)
        // The `model` param names a model at the `key` param's endpoint: never carried over to another one.
        val choice = ObserverParams.keyChoice(task)
        if (choice != null && ObserverParams.model(task) != null && !choice.isAt(key)) return settle(RunOutcome.KEY_NOT_GIVEN)
        // SSRF: the worker is inside Brainstorm's network. The guarded client checks again at connect time.
        UrlGuard.refusal(key.url)?.let { return settle(RunOutcome.REFUSED_URL, it) }
        val model = ObserverParams.model(task) ?: defaultModel(key) ?: return settle(RunOutcome.NO_MODEL)

        val name = readerName(owner, ownerRelays)
        if (!accepting()) return report(owner, RunOutcome.STOPPING)
        // Right before spending: the owner's newest schedule, read again with a quorum, must still be this one.
        val again = nostr.schedule(owner)
        if (again == null || !again.quorum) return report(owner, RunOutcome.NO_QUORUM, "re-read")
        if (again.event.id != event.id) return report(owner, RunOutcome.SCHEDULE_CHANGED)
        try {
            records.started(owner, occurrence)
        } catch (e: RunRecordsException) {
            // Not written down, so not started: printing now could pay for this morning twice after a restart.
            return report(owner, RunOutcome.RECORDS_UNAVAILABLE, e.message)
        }
        val result =
            try {
                withTimeout(printTimeoutMs) { printer.print(key, model, owner, name) }
            } catch (e: TimeoutCancellationException) {
                records.ended(owner, occurrence)
                return settle(RunOutcome.TIMED_OUT)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WriterException) {
                records.ended(owner, occurrence)
                // The failure's name and status only: what the provider said may echo the request.
                return settle(RunOutcome.WRITER_FAILED, listOfNotNull(e.failure.name, e.status?.toString()).joinToString(" "))
            }
        return when (result) {
            is PrintResult.NotReady -> {
                records.forget(owner, occurrence)
                report(owner, RunOutcome.NOT_READY, result.verdict.name)
            }

            is PrintResult.Refused -> {
                records.ended(owner, occurrence)
                settle(RunOutcome.PAGE_REFUSED, "${result.violations.size} problems")
            }

            is PrintResult.Printed -> {
                records.ended(owner, occurrence)
                val paper = pendingFor(owner, occurrence, zone, result, model, name)
                settle(publish(pair, paper, assistantSite))
            }
        }
    }

    /**
     * [printed] as it will be published: dated by the [occurrence] it is for, never by when the
     * print finished (Amethyst's rule): a paper begun at 23:55 and done at 00:03 is that
     * evening's, and the next day's 23:55 run must not skip as already printed. Its day is the
     * occurrence's on the schedule's calendar.
     */
    private fun pendingFor(
        owner: HexKey,
        occurrence: Long,
        zone: CronZone,
        printed: PrintResult.Printed,
        model: String,
        name: String,
    ): PendingPaper {
        // The page as it leaves the engine: with the paper's CSP, named by those bytes.
        val html = withPaperCsp(printed.html)
        return PendingPaper(
            owner = owner,
            occurrence = occurrence,
            day = ObserverSite.utcDay(zone.dayOf(occurrence) * DAY_SECONDS),
            code = printed.corpus.code,
            printedAt = occurrence,
            model = model,
            quotes = printed.quotes,
            posts = printed.corpus.events.size,
            headline = printed.headline,
            sha256 = publishedSha256(html),
            html = html,
            readerName = name,
            until = now() + PendingPapers.RETRY_SECONDS,
        )
    }

    /** Publishes [paper] to the Assistant's site; on any failure it is kept to be published again on a later poll, never printed again. */
    private suspend fun publish(
        pair: AssistantPair,
        paper: PendingPaper,
        site: AssistantSiteIO,
    ): RunOutcome {
        val publisher = ObserverPublisher(site, pair.assistant, { paper.readerName }, now, forReader = pair.owner)
        val failure =
            try {
                when (
                    val published =
                        publisher.publish(
                            listOf(ObserverPage(ObserverPublisher.entryFor(paper.edition(), paper.sha256), paper.html)),
                        )
                ) {
                    is ObserverPublishResult.Published -> null
                    is ObserverPublishResult.Failed -> published.reason.name
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: RunnerTokenRejectedException) {
                pending.keep(paper)
                throw e
            } catch (e: Exception) {
                // `sign` refused, Blossom threw: the class only, never a message.
                e.javaClass.simpleName
            }
        if (failure == null) {
            pending.drop(pair.owner)
            return RunOutcome.PRINTED
        }
        pending.keep(paper)
        Log.i(TAG) { "owner=${pair.owner} publish failed ($failure): kept to publish again" }
        return RunOutcome.PUBLISH_PENDING
    }

    /** A paid paper that didn't go out, published again (never printed again); dropped once out, or once [PendingPaper.until] passes. */
    private suspend fun publishPending(
        pair: AssistantPair,
        paper: PendingPaper,
        site: AssistantSiteIO,
    ) {
        if (now() > paper.until) {
            pending.drop(pair.owner)
            report(pair.owner, RunOutcome.PUBLISH_FAILED, "pending paper dropped")
            return
        }
        if (publish(pair, paper, site) == RunOutcome.PRINTED) report(pair.owner, RunOutcome.PRINTED, "published late")
    }

    /** The schedule's private tags, decrypted by the server from the whole signed event, once per event. */
    private suspend fun decryptTags(
        pair: AssistantPair,
        event: ScheduledTaskEvent,
    ): Array<Array<String>> {
        decryptedTags[event.id]?.let { return it }
        val tags = parseTags(decrypt(pair, event, schedule = null)) ?: stop(pair.owner, RunOutcome.UNREADABLE)
        decryptedTags[event.id] = tags
        return tags
    }

    /**
     * [event]'s content, decrypted by the owner's Assistant on the server. The plaintext stays in
     * memory, unlogged. The Assistant's own keys (12000) open only beside the [schedule] being printed.
     */
    private suspend fun decrypt(
        pair: AssistantPair,
        event: Event,
        schedule: ScheduledTaskEvent?,
    ): String =
        try {
            api.decrypt(pair.owner, event, schedule)
        } catch (e: NoAssistantException) {
            stop(pair.owner, RunOutcome.NO_ASSISTANT)
        } catch (e: RefusedByServerException) {
            stop(pair.owner, RunOutcome.DECRYPT_REFUSED)
        }

    /** The Assistant's own NIP-FA list (kind 12000, by the Assistant), decrypted by the server beside [schedule], and the key the schedule picks; null when none. */
    private suspend fun assistantKey(
        pair: AssistantPair,
        task: ScheduledTask,
        schedule: ScheduledTaskEvent,
        relays: Set<NormalizedRelayUrl>,
    ): AgentKeyTag? {
        val list =
            nostr.newest(pair.assistant, relays, Filter(kinds = listOf(AgentKeyEvent.KIND), authors = listOf(pair.assistant), limit = 4))
                ?: return null
        val keys = parseTags(decrypt(pair, list, schedule))?.usableAgentKeys().orEmpty()
        return ObserverParams.key(task, keys)
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
        quiet: Boolean = false,
    ): RunOutcome {
        // Owner and outcome; the detail is always one of ours (an enum name, a count, a guard reason).
        if (quiet || outcome == RunOutcome.NOT_DUE || outcome == RunOutcome.NOT_NAMED) {
            Log.d(TAG) { "owner=$owner outcome=$outcome" }
        } else {
            Log.i(TAG) { "owner=$owner outcome=$outcome" + (detail?.let { " detail=$it" } ?: "") }
        }
        return outcome
    }

    companion object {
        private const val TAG = "ObserverRunner"

        /** Owners run at once ([poll]). */
        const val PARALLELISM = 4

        private const val DAY_SECONDS = 86_400L

        /** Outcomes the owner can fix within the morning (a list published, a key added): looked at again after [FIXABLE_RETRY_SECONDS]. */
        private val FIXABLE = setOf(RunOutcome.NO_SERVERS, RunOutcome.NO_RELAYS, RunOutcome.NO_LENS, RunOutcome.NO_KEY, RunOutcome.NO_MODEL)

        const val FIXABLE_RETRY_SECONDS = 3_600L

        /**
         * A print's whole budget. Amethyst's scheduled run has 9 min 30 s (its FrontPageWorker's
         * RUN_LIMIT_MS) and keeps 132 s of it for `ran` (FrontPageMorning.RAN_RESERVE_SECONDS),
         * so a device's print never gets more than 438 s; the worker writes no `ran` and claims
         * nothing, and gives a print the same 438 s at most.
         */
        const val PRINT_TIMEOUT_SECONDS = 438L

        /** A decrypted tag array, or null when the plaintext isn't one. */
        fun parseTags(plaintext: String): Array<Array<String>>? =
            try {
                PrivateTagsInContent.decode(plaintext)
            } catch (e: Exception) {
                null
            }
    }
}
