package com.nosfabrica.observer.frontpage

import com.nosfabrica.observer.frontpage.RoutstrJson.long
import com.nosfabrica.observer.frontpage.RoutstrJson.obj
import com.nosfabrica.observer.frontpage.RoutstrJson.str
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import com.vitorpamplona.quartz.nip19Bech32.decodePublicKeyAsHexOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlin.math.ceil

/*
 * What the engine takes from Amethyst's `commons/routstr` (RoutstrError.kt, RoutstrJson.kt and
 * RoutstrPricing.kt), copied as they are there: the error reading behind [writerExceptionFor], the
 * shapes the runner reads before it prints with a Routstr key (`/v1/info`, `sats_pricing`,
 * `/v1/balance/info`), and the sats an edition is estimated at. The purchase flows (invoices, Cashu) are the apps', never the worker's.
 *
 * What a Routstr node answers, read leniently: every field may be missing, a number may come as a
 * string, and an unknown shape reads as null rather than throwing. Shapes are the live nodes'
 * (routstr-core 0.5.1, checked 2026-11-13), which differ from the docs in places.
 */

/** `GET /v1/info`, which needs no key. A node is a Routstr node when it answers with [npub] and [mints]. */
data class RoutstrInfo(
    val name: String?,
    val description: String?,
    val version: String?,
    val npub: String?,
    val mints: List<String>?,
    val httpUrl: String?,
    val onionUrl: String?,
) {
    val isRoutstr: Boolean get() = npub != null && mints != null

    /** The node's key, from [npub] (an `npub1…` or a hex key); null when it gives none or a bad one. */
    val pubKeyHex: HexKey? get() = npub?.trim()?.let { decodePublicKeyAsHexOrNull(it) }
}

/** `sats_pricing`: OpenRouter's pricing fields, in sats per token (and per request). Null means "not said". */
data class SatsPricing(
    val prompt: Double?,
    val completion: Double?,
    val request: Double?,
    /** The most the node reserves for one request: the balance must cover it, or the node answers 402. */
    val maxCost: Double?,
) {
    val priced: Boolean get() = prompt != null && completion != null
}

/** `GET /v1/balance/info`: [balanceMsats] is what can be spent. The echoed `api_key` is never read. */
data class RoutstrBalance(
    val balanceMsats: Long,
    val reservedMsats: Long?,
    val totalRequests: Long?,
    val totalSpentMsats: Long?,
    val validityDate: Long?,
) {
    val balanceSats: Long get() = balanceMsats / 1000
}

object RoutstrJson {
    private val lenient = Json { ignoreUnknownKeys = true }

    fun parse(text: String): JsonElement? =
        try {
            lenient.parseToJsonElement(text)
        } catch (e: IllegalArgumentException) {
            null
        }

    internal fun JsonObject.str(key: String): String? =
        (get(key) as? JsonPrimitive)
            ?.takeIf { it !is JsonNull }
            ?.content
            ?.trim()
            ?.ifEmpty { null }

    internal fun JsonObject.num(key: String): Double? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return (primitive.doubleOrNull ?: primitive.content.trim().toDoubleOrNull())?.takeIf { it.isFinite() }
    }

    internal fun JsonObject.long(key: String): Long? {
        val primitive = get(key) as? JsonPrimitive ?: return null
        if (primitive is JsonNull) return null
        return primitive.longOrNull ?: num(key)?.toLong()
    }

    internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject

    private fun JsonObject.strings(key: String): List<String>? =
        (get(key) as? JsonArray)?.mapNotNull {
            (it as? JsonPrimitive)
                ?.takeIf { p -> p.isString }
                ?.content
                ?.trim()
                ?.ifEmpty { null }
        }

    fun info(body: String): RoutstrInfo? {
        val json = parse(body) as? JsonObject ?: return null
        return RoutstrInfo(
            name = json.str("name"),
            description = json.str("description"),
            version = json.str("version"),
            npub = json.str("npub"),
            mints = json.strings("mints"),
            httpUrl = json.str("http_url"),
            onionUrl = json.str("onion_url"),
        )
    }

    fun satsPricing(raw: JsonElement?): SatsPricing? {
        val json = raw as? JsonObject ?: return null

        fun price(key: String) = json.num(key)?.takeIf { it >= 0 }
        return SatsPricing(price("prompt"), price("completion"), price("request"), price("max_cost"))
    }

    fun balance(body: String): RoutstrBalance? {
        val json = parse(body) as? JsonObject ?: return null
        val balance = json.long("balance") ?: return null
        return RoutstrBalance(
            balanceMsats = balance,
            reservedMsats = json.long("reserved"),
            totalRequests = json.long("total_requests"),
            totalSpentMsats = json.long("total_spent"),
            validityDate = json.long("validity_date"),
        )
    }
}

/** What went wrong with a Routstr call, for the screen to put in words (commons has no strings). */
enum class RoutstrFailure {
    /** 402 `insufficient_balance`: the key works, the balance doesn't cover the request. */
    INSUFFICIENT_BALANCE,

    /** 401 `key_not_found`: a Routstr key works only on the node that issued it. */
    KEY_NOT_FOUND,

    /** 401 `invalid_api_key`, or any other 401/403. */
    INVALID_KEY,

    /** 401 `key_expired`. */
    KEY_EXPIRED,

    /** 424 `UPSTREAM_UNAVAILABLE`: the node's own provider is down. Nothing was spent. */
    UPSTREAM_UNAVAILABLE,

    /** 429, the node's or its upstream's. */
    RATE_LIMITED,

    /** 503 `payment_settlement_failed`: the node charged and withheld the answer. Never resent on its own. */
    PAYMENT_SETTLEMENT_FAILED,

    /** A Cashu token the node wouldn't take: spent, or from a mint it doesn't accept. */
    TOKEN_REJECTED,

    /** The invoice or the key isn't there (404). */
    NOT_FOUND,

    /** The answer wasn't what a Routstr node sends. */
    UNEXPECTED,

    /** No answer: offline, a wrong URL, or Tor isn't running. */
    UNREACHABLE,

    /** This platform can't make the call. */
    UNSUPPORTED,

    /** Any other HTTP status. */
    HTTP,
}

/** A Routstr error, read from either of the shapes nodes send: `{"error": {…}}` or `{"detail": {"error": {…}}}`. */
data class RoutstrError(
    val status: Int?,
    val failure: RoutstrFailure,
    val code: String? = null,
    val type: String? = null,
    /** What the node said, trimmed: never shown for a key error, which may echo the key. */
    val message: String? = null,
    val requiredMsats: Long? = null,
    val availableMsats: Long? = null,
    val shortfallMsats: Long? = null,
) {
    /** The sats to top up for a 402, rounded up: a fraction of a sat still has to be paid. */
    val shortfallSats: Long? get() = shortfallMsats?.let { (it + 999) / 1000 } ?: requiredMsats?.let { req -> availableMsats?.let { (req - it + 999) / 1000 } }
}

class RoutstrException(
    val error: RoutstrError,
) : Exception("Routstr: ${error.failure} ${error.status ?: ""} ${error.code.orEmpty()}") {
    val failure: RoutstrFailure get() = error.failure
}

object RoutstrErrors {
    /** [status] and its [body] as a [RoutstrError]; the codes win over the status where they disagree. */
    fun parse(
        status: Int,
        body: String,
    ): RoutstrError {
        val root = RoutstrJson.parse(body) as? JsonObject
        val detail = root?.get("detail")
        // `{"detail": {"error": {...}}}`, `{"detail": {...}}`, `{"error": {...}}`, `{"error": "text"}`, `{"detail": "text"}`.
        val inner =
            (detail as? JsonObject)?.obj("error")
                ?: root?.obj("error")
                ?: (detail as? JsonObject)
        val code = inner?.str("code") ?: (detail as? JsonObject)?.str("code") ?: root?.str("code")
        val type = inner?.str("type") ?: root?.str("type")
        val message =
            inner?.str("message")
                ?: (root?.get("error") as? JsonPrimitive)?.content
                ?: (detail as? JsonPrimitive)?.content
        val details = inner?.obj("details") ?: inner
        val required = details?.long("required_msats") ?: inner?.long("required_msats")
        val available = details?.long("available_msats") ?: inner?.long("available_msats")
        val shortfall = details?.long("shortfall_msats") ?: inner?.long("shortfall_msats")
        return RoutstrError(status, failureOf(status, code, type, message), code, type, message?.take(200), required, available, shortfall)
    }

    fun failureOf(
        status: Int,
        code: String?,
        type: String?,
        message: String?,
    ): RoutstrFailure {
        val c = code?.lowercase().orEmpty()
        val t = type?.lowercase().orEmpty()
        val m = message?.lowercase().orEmpty()
        return when {
            c == "insufficient_balance" || t == "insufficient_balance" || status == 402 -> RoutstrFailure.INSUFFICIENT_BALANCE
            c == "key_not_found" -> RoutstrFailure.KEY_NOT_FOUND
            c == "key_expired" -> RoutstrFailure.KEY_EXPIRED
            c == "invalid_api_key" -> RoutstrFailure.INVALID_KEY
            c == "payment_settlement_failed" || t == "payment_settlement_failed" -> RoutstrFailure.PAYMENT_SETTLEMENT_FAILED
            c == "upstream_unavailable" || status == 424 -> RoutstrFailure.UPSTREAM_UNAVAILABLE
            c == "upstream_rate_limit" || status == 429 -> RoutstrFailure.RATE_LIMITED
            status == 401 || status == 403 -> RoutstrFailure.INVALID_KEY
            "token" in c || "cashu" in c || (status in 400..422 && ("token" in m || "proof" in m || "mint" in m)) -> RoutstrFailure.TOKEN_REJECTED
            status == 404 -> RoutstrFailure.NOT_FOUND
            else -> RoutstrFailure.HTTP
        }
    }
}

/** Routstr prices in sats, with the same token estimate the USD estimate uses. */
object RoutstrPricing {
    /**
     * One Observer edition with [sats]: `prompt_tokens * prompt + completion_tokens * completion
     * + request` for each of its two calls, rounded up to a whole sat. The tokens are the USD
     * estimate's ([editionTokens]: a digest of [digestChars], the plan and the page, Deep's
     * reasoning). Null when the node gives no prices.
     */
    fun editionSats(
        sats: SatsPricing?,
        digestChars: Int,
        briefChars: Int = systemPrompt().length,
        effort: WritingEffort = WritingEffort.STANDARD,
    ): Long? {
        val prompt = sats?.prompt ?: return null
        val completion = sats.completion ?: return null
        val tokens = editionTokens(digestChars, briefChars, effort)
        val cost = tokens.input * prompt + tokens.output * completion + 2 * (sats.request ?: 0.0)
        if (!cost.isFinite() || cost < 0) return null
        return ceil(cost).toLong()
    }

    /** [SatsPricing.maxCost] rounded up to a whole sat: what the node reserves before it answers. */
    fun maxCostSats(sats: SatsPricing?): Long? = sats?.maxCost?.takeIf { it.isFinite() && it >= 0 }?.let { ceil(it).toLong() }

    /**
     * Whether [balanceMsats] may not cover one edition: under the node's reserve for one request
     * ([SatsPricing.maxCost]), or under the edition's estimate. False when neither is known.
     */
    fun isLow(
        balanceMsats: Long?,
        sats: SatsPricing?,
        estimateSats: Long?,
    ): Boolean {
        val balance = balanceMsats ?: return false
        val maxCost = maxCostSats(sats)
        if (maxCost != null && balance < maxCost * 1000) return true
        if (estimateSats != null && balance < estimateSats * 1000) return true
        return false
    }
}
