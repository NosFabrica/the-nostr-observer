package com.nosfabrica.observer.service

import com.nosfabrica.observer.frontpage.AgentModel
import com.nosfabrica.observer.frontpage.AgentProviderPresets
import com.nosfabrica.observer.frontpage.DEFAULT_DIGEST_BUDGET
import com.nosfabrica.observer.frontpage.IRoleBasedHttpClientBuilder
import com.nosfabrica.observer.frontpage.RoutstrInfo
import com.nosfabrica.observer.frontpage.RoutstrJson
import com.nosfabrica.observer.frontpage.RoutstrPricing
import com.nosfabrica.observer.frontpage.WritingEffort
import com.vitorpamplona.quartz.nipFAAgentKeys.tags.AgentKeyTag
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The service's calls to a Routstr node, always at a key's OWN URL (its NIP-FA `key` tag): never
 * an endpoint a kind 38421 announcement names, which anyone can publish for any host. Tests fake it.
 */
interface RoutstrNode {
    /**
     * `GET {root}/info`, which takes no key. Null when the node answered with anything but a JSON
     * object; throws [IOException] when it didn't answer at all.
     */
    suspend fun info(key: AgentKeyTag): RoutstrInfo?

    /**
     * `GET {root}/balance/info` with the key: the spendable balance in msats. Null when the node
     * didn't say (an error, another shape, no answer): the print then goes ahead, and a 402 ends it.
     */
    suspend fun balanceMsats(key: AgentKeyTag): Long?
}

/** No node is ever asked: for a service that knows no Routstr node, and tests that don't need one. */
object NoRoutstrNode : RoutstrNode {
    override suspend fun info(key: AgentKeyTag): RoutstrInfo? = null

    override suspend fun balanceMsats(key: AgentKeyTag): Long? = null
}

/** [RoutstrNode] over the guarded money client: the same SSRF rules as the print's own calls. */
class HttpRoutstrNode(
    private val http: IRoleBasedHttpClientBuilder,
) : RoutstrNode {
    private suspend fun get(
        key: AgentKeyTag,
        path: String,
        withKey: Boolean,
    ): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val url = key.apiRoot() + path
            val request =
                Request
                    .Builder()
                    .url(url)
                    .get()
                    .apply { if (withKey && key.hasKey()) header("Authorization", "Bearer ${key.key}") }
                    .build()
            // A blocking call a coroutine can't interrupt: its own call timeout bounds it.
            http
                .okHttpClientForMoney(url)
                .newBuilder()
                .callTimeout(RoutstrFunds.CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { it.code to it.body.string() }
        }

    override suspend fun info(key: AgentKeyTag): RoutstrInfo? {
        val (code, body) = get(key, "/info", withKey = false)
        // A node that is down for a moment hasn't said what it is.
        if (code >= 500) throw IOException("HTTP $code")
        return if (code in 200..299) RoutstrJson.info(body) else null
    }

    override suspend fun balanceMsats(key: AgentKeyTag): Long? =
        try {
            val (code, body) = get(key, "/balance/info", withKey = true)
            // The answer may echo the key: only `balance` is read, and nothing of it is logged.
            if (code in 200..299) RoutstrJson.balance(body)?.balanceMsats else null
        } catch (e: IOException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }
}

/**
 * Whether a key is a Routstr key, and whether its balance covers a print. A key is a Routstr key
 * only when its own URL said so ([isRoutstr]), the rule Amethyst's RoutstrRouting follows: its
 * `/v1/models` lists `sats_pricing`, or its `/v1/info` answers with an npub and mints. The answer
 * is kept per endpoint for the life of the service; no answer at all is not an answer, and is asked
 * again next time.
 */
class RoutstrFunds(
    private val node: RoutstrNode,
    /** How long one call to a node may take. */
    private val timeoutMs: Long = CALL_TIMEOUT_MS,
) {
    private val known = ConcurrentHashMap<String, Boolean>()

    /**
     * Whether to ask [key]'s endpoint at all: a key at an https `/v1` endpoint that isn't another
     * provider's preset (OpenRouter's URL ends with `/v1` too, and is never a Routstr node).
     */
    fun mayBeRoutstr(key: AgentKeyTag): Boolean {
        if (!key.hasKey() || UrlGuard.refusal(key.url) != null) return false
        if (AgentProviderPresets.forUrl(key.url)?.let { it.id != ROUTSTR_PRESET } == true) return false
        return key.apiRoot().endsWith("/v1")
    }

    /** Whether [key] is at a Routstr node: [models] (its own `/models`) priced in sats, else its `/info`. */
    suspend fun isRoutstr(
        key: AgentKeyTag,
        models: suspend () -> List<AgentModel>,
    ): Boolean {
        if (!mayBeRoutstr(key)) return false
        val root = key.apiRoot().lowercase()
        known[root]?.let { return it }
        if (models().any { it.sats != null }) return true.also { known[root] = it }
        val info =
            try {
                withTimeoutOrNull(timeoutMs) { Answer(node.info(key)) } ?: return false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No answer says nothing about what it is: asked again next time.
                return false
            }
        return (info.value?.isRoutstr == true).also { known[root] = it }
    }

    private class Answer(
        val value: RoutstrInfo?,
    )

    /**
     * Whether [key]'s balance is too low to print with [model] at [effort]: under [requiredMsats].
     * False when the node didn't say what the balance is: then the print goes ahead, and a 402
     * ends it without a charge.
     */
    suspend fun tooLow(
        key: AgentKeyTag,
        model: AgentModel?,
        effort: WritingEffort = WritingEffort.STANDARD,
    ): Boolean {
        val balance =
            try {
                withTimeoutOrNull(timeoutMs) { node.balanceMsats(key) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return false
        return balance < requiredMsats(model, effort)
    }

    companion object {
        const val ROUTSTR_PRESET = "routstr"

        /** A node's answer to `/info` or `/balance/info`; neither is ever slow on a live node. */
        const val CALL_TIMEOUT_MS = 20_000L

        /**
         * A print is a plan, the page and up to three repairs, one request at a time. The node
         * reserves `max_cost` while each is in flight and then charges what it cost, so the
         * balance must hold one reserve plus what the calls before it spent.
         */
        const val RESERVES_PER_PRINT = 2

        /**
         * What a print may need, in msats: [RESERVES_PER_PRINT] times `max_cost` (sats), and never
         * less than one `max_cost` plus Amethyst's estimate of the edition in sats
         * ([RoutstrPricing.editionSats]: the plan and the page, Deep's reasoning, over a digest of
         * the whole [DEFAULT_DIGEST_BUDGET], since the service keeps no last digest to size it by).
         * 1 msat when the node says neither.
         */
        fun requiredMsats(
            model: AgentModel?,
            effort: WritingEffort = WritingEffort.STANDARD,
        ): Long {
            val maxCost = RoutstrPricing.maxCostSats(model?.sats)
            val estimate = RoutstrPricing.editionSats(model?.sats, DEFAULT_DIGEST_BUDGET, effort = effort)
            if (maxCost == null && estimate == null) return 1
            val reserve = maxCost ?: 0
            return maxOf(RESERVES_PER_PRINT * reserve, reserve + (estimate ?: 0)) * 1000
        }
    }
}
