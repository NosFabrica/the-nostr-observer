package com.nosfabrica.observer.runner

import com.nosfabrica.assistant.core.AssistantPair
import com.nosfabrica.assistant.core.MemoryPendingStore
import com.nosfabrica.assistant.core.PendingStore
import com.nosfabrica.assistant.core.RunStore
import com.nosfabrica.observer.frontpage.AgentModel
import com.nosfabrica.observer.frontpage.EditionClock
import com.nosfabrica.observer.frontpage.EditionContext
import com.nosfabrica.observer.frontpage.FrontPagePress
import com.nosfabrica.observer.frontpage.IRoleBasedHttpClientBuilder
import com.nosfabrica.observer.frontpage.ObserverPage
import com.nosfabrica.observer.frontpage.ObserverPublishResult
import com.nosfabrica.observer.frontpage.ObserverPublisher
import com.nosfabrica.observer.frontpage.ObserverSiteIO
import com.nosfabrica.observer.frontpage.PaperBoundary
import com.nosfabrica.observer.frontpage.PaperLinkSet
import com.nosfabrica.observer.frontpage.PrintResult
import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.WriterFailure
import com.nosfabrica.observer.frontpage.WritingEffort
import com.nosfabrica.observer.frontpage.chatComplete
import com.nosfabrica.observer.frontpage.editionNumber
import com.nosfabrica.observer.frontpage.outputLimitFor
import com.nosfabrica.observer.frontpage.profilesFrom
import com.nosfabrica.observer.frontpage.publishedSha256
import com.nosfabrica.observer.frontpage.rankModels
import com.nosfabrica.observer.frontpage.recentHeadlines
import com.nosfabrica.observer.frontpage.savedFromSite
import com.nosfabrica.observer.frontpage.shortNpub
import com.nosfabrica.observer.frontpage.standingPaper
import com.nosfabrica.observer.frontpage.withPaperCsp
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip01Core.relay.client.INostrClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip51Lists.encryption.PrivateTagsInContent
import com.vitorpamplona.quartz.nip5aStaticWebsites.observer.ObserverEdition
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.EventListener
import java.util.concurrent.ConcurrentHashMap

/** How one wake (or one re-publish) went for one owner. Logged by name, with the owner: never a key or a plaintext. */
enum class RunOutcome {
    PRINTED,

    /** Printed and paid for, but not published (Blossom, a relay or `sign` failed): kept, and published again later ([republish]). */
    PUBLISH_PENDING,

    /** The owner has no Observer schedule on the relays read (or none of them answered). */
    NO_SCHEDULE,

    /** The owner's newest schedule names no runner: they took printing back. */
    NOT_NAMED,

    /** Too few of the owner's write relays answered to trust the schedule read is the newest: nothing is spent. */
    NO_QUORUM,

    /** The schedule changed between the decision and the print (re-read right before spending): the next wake decides again. */
    SCHEDULE_CHANGED,

    /**
     * The `key` param names an endpoint the Assistant's list doesn't have: the reader chose to
     * spend there, so no other provider's key is used instead, with or without a `model` param.
     * Nothing spent; looked at again when the Assistant's list changes.
     */
    KEY_NOT_GIVEN,

    /** The schedule's public `p` isn't exactly one key (two `p` tags: NIP-FB's conflict, nobody runs it). */
    RUNNER_CONFLICT,

    /** Its author isn't the owner of the Assistant it names, it names another key than the owner's Assistant, or its signature is bad. */
    REJECTED,

    /** `brainstorm_nsec` has no Assistant for the owner. */
    NO_ASSISTANT,

    /** The owner's Assistant key doesn't open (no Fernet key opens it) or isn't the stored Assistant's. */
    KEY_UNREADABLE,

    /** The Assistant's allowlist refused to decrypt the event ([LocalAssistant]). */
    DECRYPT_REFUSED,

    /** The schedule's or the key list's content doesn't decrypt, or isn't a tag array. */
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
     * run, nothing spent; a later wake runs it only once that claim is stale and unfinished.
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

    /** The key's URL is a Tor onion: the worker has no Tor, and never resolves one. */
    UNREACHABLE_ONION,

    /**
     * A Routstr key's balance doesn't cover the print: under [RoutstrFunds.requiredMsats] before it
     * (nothing spent, looked at again hourly), or the node's 402 during it (ended).
     */
    INSUFFICIENT_BALANCE,

    /** A Routstr node's 503 `payment_settlement_failed`: it charged and withheld the page. Ended, never resent. */
    CHARGED_NO_ANSWER,

    /** The endpoint's own provider is down (a Routstr node's 424): nothing spent, tried again hourly. */
    UPSTREAM_UNAVAILABLE,

    /** No `model` param and the endpoint lists none. */
    NO_MODEL,

    /** The ranking isn't projected yet: nothing was spent (the claim is forgotten), the next wake tries again. */
    NOT_READY,
    WRITER_FAILED,

    /** The print ran past [ObserverRunner.PRINT_TIMEOUT_SECONDS]: stopped, and (it may have spent) not run again. */
    TIMED_OUT,

    /** The `runs` table can't be read or the claim can't be written: a start that isn't written down isn't a start, so nothing is printed. */
    RECORDS_UNAVAILABLE,

    /** The worker is shutting down: no new print starts. */
    STOPPING,

    /** The page still failed the page checks after its repairs: never published. */
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
        options: PrintOptions,
    ): PrintResult
}

/** What a print is asked with beyond its key and model. */
class PrintOptions(
    /** The schedule's `effort` param: Deep, or Standard by its absence. */
    val effort: WritingEffort = WritingEffort.STANDARD,
    /** The edition's number, recent headlines, standing paper and the reader's clock. */
    val context: EditionContext = EditionContext(),
    /** The model as its endpoint lists it (its output limit, its context); null when it isn't listed. */
    val listed: AgentModel? = null,
)

/**
 * The Assistant's papers are read on Brainstorm: citations open `brainstorm.world/e/<nevent or
 * naddr>`, bylines `brainstorm.world/p/<npub>`. The boundary accepts only these forms, so a paper
 * the Assistant prints never carries another app's links.
 */
val BRAINSTORM_LINKS = PaperLinkSet("https://brainstorm.world/e/", "https://brainstorm.world/p/")

/** The writing effort [task] asks for (NIP-FB `param` `effort`): Deep, or Standard by its absence. */
fun effortOf(task: ScheduledTask): WritingEffort = if (ObserverParams.isDeep(task)) WritingEffort.DEEP else WritingEffort.STANDARD

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
        options: PrintOptions,
    ): PrintResult =
        cancellingCalls(http) { tracked ->
            // The output limit is the listed model's; unlisted, the engine's defaults.
            val limits = options.listed?.takeIf { it.id == model } ?: AgentModel(model)
            var answered = 0
            try {
                FrontPagePress(
                    client,
                    searchRelay,
                    boundary,
                    { call, onEvent ->
                        tracked
                            .chatComplete(
                                key,
                                model,
                                call.messages,
                                outputLimitFor(limits, call),
                                options.effort,
                                onEvent,
                            ).also { answered++ }
                    },
                    links = BRAINSTORM_LINKS,
                ).print(owner, readerName, context = options.context)
            } catch (e: WriterException) {
                // A later call failed after an earlier one (the plan, the page) was answered and paid for: never "nothing spent".
                throw if (answered > 0) AfterAnswerException(e) else e
            }
        }
}

/** The writer failed on a later call of a print whose first call was answered (and paid for). */
class AfterAnswerException(
    val writer: WriterException,
) : Exception("after an answer: ${writer.failure}")

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
 * The Assistant's print service, for one owner at a time: [run] reads the owner's newest Observer
 * schedule, checks that it names their Assistant, decides, prints and publishes ([WakeService]
 * calls it on a wake). [republish] publishes the papers still waiting. See runner/README.md.
 */
class ObserverRunner(
    private val api: AssistantSigner,
    private val nostr: RunnerNostr,
    private val blossom: BlossomHttp,
    private val printer: PaperPrinter,
    /** `assistant_runner.runs`: the claim before spending, its end after. */
    private val runs: RunStore,
    /** The models an endpoint lists (the engine's `listAgentModels`): the default model, and a Routstr node's prices. */
    private val models: suspend (AgentKeyTag) -> List<AgentModel>,
    private val zoneOf: (String?) -> CronZone? = { it?.let(ZoneIdCronZone::of) },
    private val now: () -> Long = { TimeUtils.now() },
    /** The longest a print may take, the model's calls included ([PRINT_TIMEOUT_SECONDS]). */
    private val printTimeoutMs: Long = PRINT_TIMEOUT_SECONDS * 1000,
    /** False once the service is stopping: a run that hasn't started its print yet doesn't. */
    private val accepting: () -> Boolean = { true },
    /** Papers printed but not yet published ([PendingPaper]), in `assistant_runner.pending`. */
    private val pending: PendingStore = MemoryPendingStore(),
    /** Routstr keys' detection and balance, asked of each key's own node. */
    private val routstr: RoutstrFunds = RoutstrFunds(NoRoutstrNode),
    /** The longest the `/models` listing may take: it is asked before a print, and a stopping worker waits for it. */
    private val modelsTimeoutMs: Long = MODELS_TIMEOUT_MS,
) {
    /**
     * What this service already settled for an owner's occurrence, so a wake (the runner wakes
     * again every few minutes until a `runs` row exists, and a fixable outcome writes none) doesn't
     * read both sites and decrypt the keys again until the next one is due: [outcome]
     * holds for [scheduleId]'s [occurrence] until [until] (the occurrence's end for a paper that
     * exists or a run that ended, an hour for something the owner can fix, like a missing 10063).
     */
    private class Settled(
        val scheduleId: String,
        val occurrence: Long,
        val outcome: RunOutcome,
        val until: Long,
        /**
         * For an outcome that turned on the Assistant's keys ([KEY_DEPENDENT]), the id of the kind
         * 12000 it was decided with ("" for none): a new list from the owner unsettles it.
         */
        val keysId: String? = null,
    )

    private val settled = ConcurrentHashMap<HexKey, Settled>()

    /**
     * Each owner's newest schedule's decrypted tags, by its event id: an event never changes, so
     * neither does its plaintext. One per owner, so it holds no more than the owners it has seen.
     */
    private val decryptedTags = ConcurrentHashMap<HexKey, Pair<String, Array<Array<String>>>>()

    /**
     * One owner's whole path, as a wake asks: their Assistant, their newest schedule (read with a
     * quorum), the checks, the decision, the print and the publish. Returns the outcome, for the
     * log and the tests; throws only when cancelled.
     */
    suspend fun run(owner: HexKey): RunOutcome =
        guarded(owner) {
            val at = now()
            settled.values.removeIf { it.until <= at }
            val (read, pair) = front(owner)
            runChecked(read, pair)
        }

    /**
     * Publishes [owner]'s papers that are still waiting, never printing anything: only while their
     * newest schedule (read with a quorum) still names their Assistant, as [run] does. An owner who
     * no longer has an Assistant has theirs dropped.
     */
    suspend fun republish(owner: HexKey): RunOutcome =
        guarded(owner) {
            val (_, pair) =
                try {
                    front(owner)
                } catch (s: Stop) {
                    if (s.outcome == RunOutcome.NO_ASSISTANT) waiting(owner).forEach { dropPaper(it) }
                    throw s
                }
            val papers = waiting(owner)
            if (papers.isEmpty()) return@guarded RunOutcome.PRINTED
            val ownerRelays = nostr.writeRelays(owner)
            val assistantRelays = nostr.writeRelays(pair.assistant)
            val site = AssistantSiteIO(owner, pair.assistant, api, nostr, assistantRelays, ownerServers(owner, ownerRelays), blossom, now)
            publishPending(pair, papers, site)
        }

    /** [block], its [Stop] read as its outcome, and any other failure as [RunOutcome.ERROR]. */
    private suspend fun guarded(
        owner: HexKey,
        block: suspend () -> RunOutcome,
    ): RunOutcome =
        try {
            block()
        } catch (s: Stop) {
            s.outcome
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The class only: a message could carry what a provider or a relay echoed.
            Log.w(TAG) { "owner=$owner outcome=${RunOutcome.ERROR} error=${e.javaClass.simpleName}" }
            RunOutcome.ERROR
        }

    /**
     * What every path starts with: the owner's Assistant (its key must open), the owner's newest
     * schedule read by author with a quorum, and the runner checks on it. Nothing is decrypted.
     * [Stop]s with the outcome when any fails.
     */
    private suspend fun front(owner: HexKey): Pair<ScheduleRead, AssistantPair> {
        // Checked first: a SIGTERM while this wake waited starts nothing.
        if (!accepting()) stop(owner, RunOutcome.STOPPING)
        val assistant =
            try {
                api.assistantOf(owner)
            } catch (e: NoAssistantException) {
                stop(owner, RunOutcome.NO_ASSISTANT)
            } catch (e: AssistantKeyException) {
                stop(owner, RunOutcome.KEY_UNREADABLE, e.failure.name)
            }
        val pair = AssistantPair(owner, assistant)
        val read = nostr.schedule(owner) ?: stop(owner, RunOutcome.NO_SCHEDULE)
        val event = read.event
        val named = ScheduledTask(event.tags).runners
        // The owner took printing back (their newest version names no runner): nothing to do, said quietly.
        if (named.isEmpty()) stop(owner, RunOutcome.NOT_NAMED)
        // NIP-FB: with more than one `p`, nobody runs the task. Nothing is decrypted.
        if (named.size != 1) stop(owner, RunOutcome.RUNNER_CONFLICT, "${named.size} p tags")
        // Being named is not enough: the author must be the owner of THAT Assistant, and it must verify.
        val check = ScheduledTaskClaims.checkRunner(event, pair.assistant, pair.owner)
        if (check != ScheduledTaskClaims.RunnerCheck.OK) stop(owner, RunOutcome.REJECTED, check.name)
        // Too few of the owner's relays answered: a newer version (printing taken back) may be on the others.
        if (!read.quorum) stop(owner, RunOutcome.NO_QUORUM)
        return read to pair
    }

    /** Drops every paper past its time: the retry loop's first step. */
    suspend fun dropExpiredPending() {
        try {
            pending.dropExpired(now()).takeIf { it > 0 }?.let { Log.i(TAG) { "dropped $it pending papers past their time" } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG) { "could not prune the pending papers: ${e.javaClass.simpleName}" }
        }
    }

    /**
     * Quartz's runner decision, with this service's own run of the occurrence read from [runs]
     * (Quartz asks for it synchronously, so the due occurrence is found first, then its row read).
     */
    private suspend fun decide(
        task: ScheduledTask,
        pair: AssistantPair,
        zone: CronZone,
        at: Long,
        alreadyDone: (Long) -> Boolean = { false },
    ): ScheduledTaskClaims.RunnerDecision {
        val probe = ScheduledTaskClaims.decideAsRunner(task, ObserverSchedule.TASK, pair.assistant, zone, at)
        val due = probe.occurrence ?: return probe
        val own =
            try {
                runs.ownRun(TASK, pair.owner, due)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stop(pair.owner, RunOutcome.RECORDS_UNAVAILABLE, e.javaClass.simpleName)
            }
        return ScheduledTaskClaims.decideAsRunner(
            task,
            ObserverSchedule.TASK,
            pair.assistant,
            zone,
            at,
            ownRun = { if (it == due) own else ScheduledTaskClaims.OwnRun.NONE },
            alreadyDone = alreadyDone,
        )
    }

    /** Ends a claimed run with [outcome]. A failure is logged: the row stays `started`, which is never run again either. */
    private suspend fun endRun(
        owner: HexKey,
        occurrence: Long,
        outcome: RunOutcome,
    ) {
        try {
            runs.end(TASK, owner, occurrence, outcome.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG) { "owner=$owner could not end the run: ${e.javaClass.simpleName}" }
        }
    }

    /**
     * Forgets a claimed run that spent nothing, so a later wake may try again. A failure leaves
     * the row `started`, which is never run again: that costs a paper, never a second payment.
     */
    private suspend fun forgetRun(
        owner: HexKey,
        occurrence: Long,
    ) {
        try {
            runs.forget(TASK, owner, occurrence)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG) { "owner=$owner could not forget the run: ${e.javaClass.simpleName}" }
        }
    }

    /** [owner]'s papers waiting, oldest first; none when the store can't be read (they wait for the next try). */
    private suspend fun waiting(owner: HexKey): List<PendingPaper> =
        try {
            pending.list(TASK, owner).mapNotNull { PendingPaper.of(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG) { "owner=$owner could not read the pending papers: ${e.javaClass.simpleName}" }
            emptyList()
        }

    private suspend fun dropPaper(paper: PendingPaper) {
        try {
            pending.delete(TASK, paper.owner, paper.occurrence)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG) { "owner=${paper.owner} could not delete a pending paper: ${e.javaClass.simpleName}" }
        }
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

    private suspend fun runChecked(
        read: ScheduleRead,
        pair: AssistantPair,
    ): RunOutcome {
        val event = read.event
        val owner = pair.owner
        // Both NIP-65 lists are cached; the owner's 10063 is read only once something needs it.
        val ownerRelays = nostr.writeRelays(owner)
        val assistantRelays = nostr.writeRelays(pair.assistant)
        var serversRead: List<String>? = null

        suspend fun servers() = serversRead ?: ownerServers(owner, ownerRelays).also { serversRead = it }

        suspend fun assistantSite() = AssistantSiteIO(owner, pair.assistant, api, nostr, assistantRelays, servers(), blossom, now)
        // Papers already paid for go out first, before anything else is decided.
        waiting(owner).takeIf { it.isNotEmpty() }?.let { publishPending(pair, it, assistantSite()) }

        val task = ScheduledTask(event.tags, decryptTags(pair, event))
        if (!ObserverParams.understands(task)) return report(owner, RunOutcome.UNKNOWN_PARAMS)
        val zone = zoneOf(task.tz) ?: return report(owner, RunOutcome.UNKNOWN_ZONE)
        val at = now()

        val first = decide(task, pair, zone, at)
        if (!first.shouldRun) return report(owner, verdictOutcome(first.verdict))
        val occurrence = first.occurrence!!
        settled[owner]?.let { s ->
            if (s.scheduleId == event.id && s.occurrence == occurrence && at < s.until &&
                (s.keysId == null || s.keysId == keyListId(pair.assistant, assistantRelays))
            ) {
                return report(owner, s.outcome, "settled", quiet = true)
            }
        }
        val nextDue = task.expression()?.nextAfter(occurrence, zone) ?: (occurrence + DAY_SECONDS)

        // The id of the Assistant's kind 12000 the key was picked from, once it has been read.
        var keysId: String? = null

        fun settle(
            outcome: RunOutcome,
            detail: String? = null,
        ): RunOutcome {
            val until = if (outcome in FIXABLE) minOf(at + FIXABLE_RETRY_SECONDS, nextDue) else nextDue
            settled[owner] = Settled(event.id, occurrence, outcome, until, keysId?.takeIf { outcome in KEY_DEPENDENT })
            return report(owner, outcome, detail)
        }

        if (assistantRelays.isEmpty()) return settle(RunOutcome.NO_RELAYS)
        // Both sites, before anything is spent: a print by the owner, a device or the Assistant covers the morning.
        val servers = servers()
        val assistantSite = assistantSite()
        val readerSite = ReaderSiteIO(owner, nostr, ownerRelays, servers, blossom)
        val runnerRead =
            when (val site = editionsOn(assistantSite, pair.assistant)) {
                SiteEditions.Unreadable -> return report(owner, RunOutcome.SITE_UNREADABLE)
                is SiteEditions.Read -> site
            }
        val runnerEditions = runnerRead.editions
        // The owner's site unreadable: decide from the Assistant's own (and its records), as a device decides from its store.
        val readerRead = editionsOn(readerSite, owner) as? SiteEditions.Read
        val readerEditions = readerRead?.editions.orEmpty()
        val second = decide(task, pair, zone, at) { ObserverSite.alreadyPrinted(readerEditions, runnerEditions, it, zone) }
        if (!second.shouldRun) {
            val outcome = verdictOutcome(second.verdict)
            return if (outcome == RunOutcome.ALREADY_PRINTED) settle(outcome) else report(owner, outcome)
        }

        if (servers.isEmpty()) return settle(RunOutcome.NO_SERVERS)
        if (!hasLens(owner, ownerRelays)) return settle(RunOutcome.NO_LENS)

        val key =
            when (val keys = assistantKey(pair, task, event, assistantRelays)) {
                KeyRead.NoQuorum -> return report(owner, RunOutcome.NO_QUORUM, "kind 12000")
                is KeyRead.Read -> {
                    keysId = keys.listId
                    keys.key
                }
            } ?: return settle(RunOutcome.NO_KEY)
        // The `key` param names an endpoint: never another provider's key instead, model param or not.
        val choice = ObserverParams.keyChoice(task)
        if (choice != null && !choice.isAt(key)) return settle(RunOutcome.KEY_NOT_GIVEN)
        // SSRF: the worker is inside Brainstorm's network. The guarded client checks again at connect time.
        UrlGuard.refusal(key.url)?.let {
            return settle(
                if (it ==
                    UrlGuard.ONION
                ) {
                    RunOutcome.UNREACHABLE_ONION
                } else {
                    RunOutcome.REFUSED_URL
                },
                it,
            )
        }
        var listed: List<AgentModel>? = null

        suspend fun listed() = listed ?: listModels(key).also { listed = it }
        val model = ObserverParams.model(task) ?: rankModels(listed().map { it.id }).firstOrNull() ?: return settle(RunOutcome.NO_MODEL)
        val effort = effortOf(task)
        // A Routstr key's balance must cover the print before anything is spent; asked of the key's own node only.
        if (routstr.isRoutstr(key) { listed() } && routstr.tooLow(key, listed().firstOrNull { it.id == model }, effort)) {
            return settle(RunOutcome.INSUFFICIENT_BALANCE)
        }

        val name = readerName(owner, ownerRelays)
        val options =
            PrintOptions(
                effort = effort,
                context = editionContext(owner, occurrence, task, listOf(assistantSite to runnerRead, readerSite to readerRead)),
                listed = listed?.firstOrNull { it.id == model },
            )
        if (!accepting()) return report(owner, RunOutcome.STOPPING)
        // Right before spending: the owner's newest schedule, read again with a quorum, must still be this one.
        val again = nostr.schedule(owner)
        if (again == null || !again.quorum) return report(owner, RunOutcome.NO_QUORUM, "re-read")
        if (again.event.id != event.id) return report(owner, RunOutcome.SCHEDULE_CHANGED)
        // The claim, before anything is spent: only the caller that inserts the row runs the occurrence.
        val claimed =
            try {
                runs.claim(TASK, owner, occurrence, event.id)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not written down, so not started: printing now could pay for this morning twice.
                return report(owner, RunOutcome.RECORDS_UNAVAILABLE, e.javaClass.simpleName)
            }
        // Another replica (or an earlier wake) holds the row: it runs it, or ran it.
        if (!claimed) return report(owner, RunOutcome.ATTEMPTED, "claimed")
        return try {
            printClaimed(pair, occurrence, zone, key, model, name, options, assistantSite, ::settle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // It may have spent: ended, never run again on its own.
            endRun(owner, occurrence, RunOutcome.ERROR)
            throw e
        }
    }

    /** The print of a claimed occurrence, and its publish: every path ends or forgets the claim. */
    private suspend fun printClaimed(
        pair: AssistantPair,
        occurrence: Long,
        zone: CronZone,
        key: AgentKeyTag,
        model: String,
        name: String,
        options: PrintOptions,
        assistantSite: AssistantSiteIO,
        settle: (RunOutcome, String?) -> RunOutcome,
    ): RunOutcome {
        val owner = pair.owner
        val result =
            try {
                withTimeout(printTimeoutMs) { printer.print(key, model, owner, name, options) }
            } catch (e: TimeoutCancellationException) {
                endRun(owner, occurrence, RunOutcome.TIMED_OUT)
                return settle(RunOutcome.TIMED_OUT, null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: WriterException) {
                return writerFailed(e, answered = false, owner, occurrence, settle)
            } catch (e: AfterAnswerException) {
                return writerFailed(e.writer, answered = true, owner, occurrence, settle)
            }
        return when (result) {
            is PrintResult.NotReady -> {
                forgetRun(owner, occurrence)
                report(owner, RunOutcome.NOT_READY, result.verdict.name)
            }

            is PrintResult.Refused -> {
                endRun(owner, occurrence, RunOutcome.PAGE_REFUSED)
                settle(RunOutcome.PAGE_REFUSED, "${result.violations.size} problems")
            }

            is PrintResult.Printed -> {
                val paper = pendingFor(owner, occurrence, zone, result, model, name)
                // Kept before it is published: a service stopped mid-publish still has the paid paper.
                val kept =
                    try {
                        pending.put(paper.toPending())
                        true
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG) { "owner=$owner could not keep the paper before publishing it: ${e.javaClass.simpleName}" }
                        false
                    }
                // An older paper still waiting goes out with it, in the same site event.
                val papers = (waiting(owner).filter { it.occurrence != occurrence } + paper).sortedBy { it.occurrence }
                val outcome = publish(pair, papers, assistantSite)
                if (!kept && outcome != RunOutcome.PRINTED) Log.w(TAG) { "owner=$owner a paid paper was neither kept nor published" }
                endRun(owner, occurrence, outcome)
                settle(outcome, null)
            }
        }
    }

    /**
     * How a writer failure ends the run. Whatever reached the model is ended in the records (it
     * may have spent), except a 424 before any answer: the node's own provider was down and
     * nothing was charged, so the start is forgotten and the occurrence tried again hourly.
     */
    private suspend fun writerFailed(
        e: WriterException,
        answered: Boolean,
        owner: HexKey,
        occurrence: Long,
        settle: (RunOutcome, String?) -> RunOutcome,
    ): RunOutcome {
        // The failure's name and status only: what the provider said may echo the request.
        val detail = listOfNotNull(e.failure.name, e.status?.toString(), "after an answer".takeIf { answered }).joinToString(" ")
        if (e.failure == WriterFailure.UPSTREAM_DOWN && !answered) {
            forgetRun(owner, occurrence)
            return settle(RunOutcome.UPSTREAM_UNAVAILABLE, detail)
        }
        val outcome =
            when {
                e.failure == WriterFailure.SETTLEMENT_FAILED -> RunOutcome.CHARGED_NO_ANSWER
                e.failure == WriterFailure.LOW_BALANCE || e.status == 402 -> RunOutcome.INSUFFICIENT_BALANCE
                else -> RunOutcome.WRITER_FAILED
            }
        endRun(owner, occurrence, outcome)
        return settle(outcome, detail)
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
            until = now() + PendingPaper.RETRY_SECONDS,
        )
    }

    /**
     * Publishes [papers] (an owner's, oldest first, each already kept) to the Assistant's site in
     * one site event, and drops them once out. On any failure they stay kept, to be published
     * again later ([republish]), never printed again.
     */
    private suspend fun publish(
        pair: AssistantPair,
        papers: List<PendingPaper>,
        site: AssistantSiteIO,
    ): RunOutcome {
        val publisher = ObserverPublisher(site, pair.assistant, { papers.last().readerName }, now, forReader = pair.owner)
        val failure =
            try {
                when (
                    val published =
                        publisher.publish(
                            papers.map { ObserverPage(ObserverPublisher.entryFor(it.edition(), it.sha256), it.html) },
                        )
                ) {
                    is ObserverPublishResult.Published -> null
                    is ObserverPublishResult.Failed -> published.reason.name
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // `sign` refused, Blossom threw: the class only, never a message.
                e.javaClass.simpleName
            }
        if (failure == null) {
            papers.forEach { dropPaper(it) }
            return RunOutcome.PRINTED
        }
        Log.i(TAG) { "owner=${pair.owner} publish failed ($failure): ${papers.size} kept to publish again" }
        return RunOutcome.PUBLISH_PENDING
    }

    /** Paid papers that didn't go out, published again (never printed again); each dropped once out, or once its [PendingPaper.until] passes. */
    private suspend fun publishPending(
        pair: AssistantPair,
        papers: List<PendingPaper>,
        site: AssistantSiteIO,
    ): RunOutcome {
        val (live, expired) = papers.partition { now() <= it.until }
        expired.forEach {
            dropPaper(it)
            report(pair.owner, RunOutcome.PUBLISH_FAILED, "pending paper dropped")
        }
        if (live.isEmpty()) return RunOutcome.PUBLISH_FAILED
        val outcome = publish(pair, live, site)
        if (outcome == RunOutcome.PRINTED) report(pair.owner, RunOutcome.PRINTED, "${live.size} published late")
        return outcome
    }

    /** The schedule's private tags, decrypted by the Assistant from the whole signed event, once per event. */
    private suspend fun decryptTags(
        pair: AssistantPair,
        event: ScheduledTaskEvent,
    ): Array<Array<String>> {
        decryptedTags[pair.owner]?.takeIf { it.first == event.id }?.let { return it.second }
        val tags = parseTags(decrypt(pair, event, schedule = null)) ?: stop(pair.owner, RunOutcome.UNREADABLE)
        decryptedTags[pair.owner] = event.id to tags
        return tags
    }

    /**
     * [event]'s content, decrypted by the owner's Assistant. The plaintext stays in memory,
     * unlogged. The Assistant's own keys (12000) open only beside the [schedule] being printed.
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
        } catch (e: AssistantKeyException) {
            stop(pair.owner, RunOutcome.KEY_UNREADABLE, e.failure.name)
        } catch (e: RefusedException) {
            stop(pair.owner, RunOutcome.DECRYPT_REFUSED, e.reason)
        } catch (e: UndecryptableException) {
            stop(pair.owner, RunOutcome.UNREADABLE)
        }

    /** What [assistantKey] read: the key and the list it came from, or too few relays answered. */
    private sealed interface KeyRead {
        /** [key] is null when the list holds no usable key; [listId] is "" when there is no list. */
        class Read(
            val key: AgentKeyTag?,
            val listId: String,
        ) : KeyRead

        object NoQuorum : KeyRead
    }

    private fun keyListFilter(assistant: HexKey) = Filter(kinds = listOf(AgentKeyEvent.KIND), authors = listOf(assistant), limit = 4)

    /**
     * The Assistant's own NIP-FA list (kind 12000, by the Assistant), decrypted by the Assistant
     * beside [schedule], and the key the schedule picks. Read with the server's majority rule:
     * more than half of the Assistant's relays must answer, or a list the owner just replaced
     * (a key taken back) could still be the one read.
     */
    private suspend fun assistantKey(
        pair: AssistantPair,
        task: ScheduledTask,
        schedule: ScheduledTaskEvent,
        relays: Set<NormalizedRelayUrl>,
    ): KeyRead {
        val (list, quorum) = nostr.newestWithQuorum(pair.assistant, relays, keyListFilter(pair.assistant))
        if (!quorum) return KeyRead.NoQuorum
        if (list == null) return KeyRead.Read(null, "")
        val keys = parseTags(decrypt(pair, list, schedule))?.usableAgentKeys().orEmpty()
        return KeyRead.Read(ObserverParams.key(task, keys), list.id)
    }

    /** The id of the Assistant's newest kind 12000 ("" for none), unread: what a settled key outcome was decided with. */
    private suspend fun keyListId(
        assistant: HexKey,
        relays: Set<NormalizedRelayUrl>,
    ): String = nostr.newest(assistant, relays, keyListFilter(assistant))?.id ?: ""

    /**
     * What the edition knows beyond its posts (Amethyst's FrontPagePrinter.editionContext, for a
     * runner): its number, counting the days on the Assistant's site, on the owner's own and in
     * this worker's run records; the last front pages of both sites; the masthead, motto and
     * sections the newest page announces; and the schedule's own clock. Never fails a print: what
     * can't be read is left out.
     */
    private suspend fun editionContext(
        owner: HexKey,
        occurrence: Long,
        task: ScheduledTask,
        sites: List<Pair<ObserverSiteIO, SiteEditions.Read?>>,
    ): EditionContext {
        // Every producer names a site's edition by the UTC day of its printedAt, which for a
        // scheduled paper is its occurrence: the records' days are counted the same way.
        val today = ObserverSite.utcDay(occurrence)
        val read = sites.mapNotNull { (io, site) -> site?.let { io to it } }
        val editions = read.flatMap { it.second.editions }
        val ranDays =
            try {
                runs.occurrences(TASK, owner).map { ObserverSite.utcDay(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                emptyList()
            }
        val newest =
            read
                .flatMap { (io, site) -> site.editions.filter { it.day != today }.map { Triple(io, site.servers, it) } }
                .maxWithOrNull(
                    compareBy<Triple<ObserverSiteIO, List<String>, ObserverEdition>>({ it.third.day }, { it.third.printedAt ?: 0 }),
                )
        val page = newest?.let { (io, servers, edition) -> pageOf(io, servers, edition) }
        return EditionContext(
            paper = standingPaper(listOfNotNull(page)),
            number = editionNumber(editions.map { it.day } + ranDays, today),
            recent = recentHeadlines(editions.map { savedFromSite(it, byRunner = false) }, today),
            clock = EditionClock.of(task.tz) ?: EditionClock.UTC,
        )
    }

    /** [edition]'s page, by its hash, within [PAGE_READ_TIMEOUT_MS]; null when no server has it. */
    private suspend fun pageOf(
        io: ObserverSiteIO,
        servers: List<String>,
        edition: ObserverEdition,
    ): String? =
        try {
            withTimeoutOrNull(PAGE_READ_TIMEOUT_MS) { io.download(servers, edition.sha256.lowercase())?.decodeToString() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG) { "could not read back the newest page: ${e.javaClass.simpleName}" }
            null
        }

    /** The models [key]'s endpoint lists ([models]), within [modelsTimeoutMs]; empty when it can't say. */
    private suspend fun listModels(key: AgentKeyTag): List<AgentModel> =
        try {
            withTimeoutOrNull(modelsTimeoutMs) { models(key) }.orEmpty()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
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

        /** The task this service runs: the schedules' `task`, and its rows in `assistant_runner`. */
        const val TASK = ObserverSchedule.TASK

        /** Owners run at once ([WakeService]): one slow reader's server never holds up the rest. */
        const val PARALLELISM = 4

        private const val DAY_SECONDS = 86_400L

        /** Outcomes the owner can fix within the morning (a list published, a key added): looked at again after [FIXABLE_RETRY_SECONDS]. */
        private val FIXABLE =
            setOf(
                RunOutcome.NO_SERVERS,
                RunOutcome.NO_RELAYS,
                RunOutcome.NO_LENS,
                RunOutcome.NO_KEY,
                RunOutcome.NO_MODEL,
                // A balance topped up, a node's provider back: nothing was spent, so the morning may still print.
                RunOutcome.INSUFFICIENT_BALANCE,
                RunOutcome.UPSTREAM_UNAVAILABLE,
            )

        /** Outcomes decided by the Assistant's kind 12000: settled only while that list is the same event. */
        private val KEY_DEPENDENT =
            setOf(
                RunOutcome.NO_KEY,
                RunOutcome.KEY_NOT_GIVEN,
                RunOutcome.REFUSED_URL,
                RunOutcome.UNREACHABLE_ONION,
                RunOutcome.NO_MODEL,
                RunOutcome.INSUFFICIENT_BALANCE,
            )

        /** The `/models` listing before a print; a SIGTERM's grace waits for it. */
        const val MODELS_TIMEOUT_MS = 20_000L

        /** Reading back the newest page for its masthead, motto and sections: optional, so short. */
        const val PAGE_READ_TIMEOUT_MS = 20_000L

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
