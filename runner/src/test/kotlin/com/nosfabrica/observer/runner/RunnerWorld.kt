package com.nosfabrica.observer.runner

import com.nosfabrica.observer.frontpage.AgentModel
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTask
import com.vitorpamplona.quartz.nipFBScheduledTasks.ScheduledTaskEvent
import kotlinx.coroutines.Dispatchers
import java.time.Instant

/**
 * One owner with an Assistant (and a stranger with theirs), their relays and lists, a schedule
 * naming the Assistant, and a worker over fakes: what every runner test starts from.
 */
abstract class RunnerWorld {
    protected val owner = newSigner()
    protected val assistant = newSigner()
    protected val stranger = newSigner()
    protected val strangersAssistant = newSigner()

    protected val index = relay("wss://index.example")
    protected val ownerRelay = relay("wss://owner.example")
    protected val assistantRelay = relay("wss://assistant.example")

    // 06:30 UTC on 12 November 2026: London's 06:00 run is due, and today is 2026-11-12.
    protected val now = Instant.parse("2026-11-12T06:30:00Z").epochSecond
    protected val day = "2026-11-12"

    /** That morning's occurrence. */
    protected val six = Instant.parse("2026-11-12T06:00:00Z").epochSecond

    /** The worker's clock: [now] unless a test moves it. */
    protected var clock = now

    protected val openRouter = AgentKeyTag("sk-or-v1-first-key-0001", "https://openrouter.ai/api/v1")
    protected val secondOpenRouter = AgentKeyTag("sk-or-v1-second-key-a3f9", "https://openrouter.ai/api/v1")
    protected val anthropic = AgentKeyTag("sk-ant-api03-third-key-77zz", "https://api.anthropic.com/v1")

    protected val relays = FakeRelays()
    protected val blossom = FakeBlossom()
    protected val printer = FakePrinter()
    protected val records = FileRunRecords(null)
    protected val api = FakeInternalApi(mapOf(owner.pubKey to assistant, stranger.pubKey to strangersAssistant))
    protected var listedModels = listed("openai/gpt-4o-mini", "anthropic/claude-sonnet-4.5", "text-embedding-3")

    protected val runner =
        ObserverRunner(
            api = api,
            nostr = RunnerNostr(relays, setOf(index), null),
            blossom = BlossomHttp(blossom.client),
            printer = printer,
            records = records,
            models = { listedModels },
            now = { clock },
            diskDispatcher = Dispatchers.Unconfined,
        )

    protected suspend fun world(keys: List<AgentKeyTag> = listOf(openRouter, secondOpenRouter, anthropic)) {
        relays.put(
            index,
            owner.signed(10002, arrayOf(arrayOf("r", ownerRelay.url, "write")), createdAt = 1),
            assistant.signed(10002, arrayOf(arrayOf("r", assistantRelay.url)), createdAt = 1),
            stranger.signed(10002, arrayOf(arrayOf("r", ownerRelay.url, "write")), createdAt = 1),
        )
        relays.put(
            ownerRelay,
            owner.signed(
                10063,
                arrayOf(arrayOf("server", "https://blossom.owner.example"), arrayOf("server", "https://media.owner.example")),
                createdAt = 1,
            ),
            owner.signed(10040, arrayOf(arrayOf("30382:rank", "b".repeat(64), "wss://nip85.example")), createdAt = 1),
            owner.signed(0, emptyArray(), """{"name":"Vitor"}""", createdAt = 1),
        )
        // The Assistant's own Blossom list must never be used: uploads go to the owner's servers.
        relays.put(
            assistantRelay,
            assistant.signed(10063, arrayOf(arrayOf("server", "https://assistant-own.example")), createdAt = 1),
            agentKeyList(assistant, keys, createdAt = 1),
        )
    }

    protected fun task(
        model: String? = "anthropic/claude-sonnet-4.5",
        key: AgentKeyTag? = secondOpenRouter,
    ): ScheduledTask {
        var t =
            ScheduledTask
                .create("observer", "observer", "0 6 * * *", "Europe/London", setAt = 0)
                .withRunner(assistant.pubKey, 0)
        if (model != null) t = t.withParam("model", listOf(model))
        if (key != null) t = t.withParam("key", listOf(key.url, key.key.takeLast(4)))
        return t
    }

    protected suspend fun schedule(
        task: ScheduledTask = task(),
        by: NostrSignerInternal = owner,
        onIndex: Boolean = true,
        createdAt: Long = now - 3_600,
    ): Event {
        val template = ScheduledTaskEvent.update(task, by, "observer", createdAt = createdAt)
        val event = typed(by.sign(template))
        relays.put(ownerRelay, event)
        // Brainstorm's index relay (the search relay) mirrors it: the worker finds schedules there first.
        if (onIndex) relays.put(index, event)
        return event
    }

    protected fun runnerWith(
        printer: PaperPrinter = this.printer,
        records: RunRecords = this.records,
        accepting: () -> Boolean = { true },
        access: RelayAccess = relays,
        api: InternalApi = this.api,
        pending: PendingPapers = PendingPapers(null),
        routstr: RoutstrFunds = RoutstrFunds(NoRoutstrNode),
        parallelism: Int = ObserverRunner.PARALLELISM,
        models: suspend (AgentKeyTag) -> List<AgentModel> = { listedModels },
        nostr: RunnerNostr = RunnerNostr(access, setOf(index), null, now = { clock }),
    ) = ObserverRunner(
        api = api,
        nostr = nostr,
        blossom = BlossomHttp(blossom.client),
        printer = printer,
        records = records,
        models = models,
        now = { clock },
        parallelism = parallelism,
        accepting = accepting,
        pending = pending,
        routstr = routstr,
        // On the test's own thread: a write on a real dispatcher lets virtual time run past every timeout.
        diskDispatcher = Dispatchers.Unconfined,
    )
}
