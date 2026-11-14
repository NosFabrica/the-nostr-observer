package com.nosfabrica.observer.service

import com.nosfabrica.observer.service.keys.isPubKeyHex
import com.vitorpamplona.quartz.nip01Core.core.HexKey
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The trigger contract, v1 (assistant-runner's `contract/v1/`, whose vectors are copied into this
 * module's test resources): the runner's wake, `POST {service}/v1/run` with
 * `{"v": 1, "task", "owner", "occurrence"}`. This service's own types: the runner writes the same
 * JSON with code of its own, and the two share the vectors, never the code.
 *
 * [occurrence] is a hint: the service decides the occurrence itself from the schedule it reads.
 */
class WakeRequest(
    val task: String,
    val owner: HexKey,
    val occurrence: Long,
) {
    init {
        require(isTaskName(task)) { TASK_RULE }
        require(isPubKeyHex(owner)) { OWNER_RULE }
        require(occurrence >= 0) { OCCURRENCE_RULE }
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("v", VERSION)
            put("task", task)
            put("owner", owner)
            put("occurrence", occurrence)
        }

    override fun toString() = toJson().toString()

    override fun equals(other: Any?) = other is WakeRequest && other.task == task && other.owner == owner && other.occurrence == occurrence

    override fun hashCode() = (task.hashCode() * 31 + owner.hashCode()) * 31 + occurrence.hashCode()

    /** What a service reads from a request body: a [Valid] wake, or why it answers `400`. */
    sealed interface Parsed {
        class Valid(
            val request: WakeRequest,
        ) : Parsed

        class Invalid(
            val why: String,
        ) : Parsed
    }

    companion object {
        const val VERSION = 1

        /** The path a service serves the wake on, under its base URL. */
        const val PATH = "/v1/run"

        const val NOT_AN_OBJECT = "not a JSON object"
        const val VERSION_RULE = "unknown version"
        const val TASK_RULE = "task must be a task name"
        const val OWNER_RULE = "owner must be 64 lowercase hex"
        const val OCCURRENCE_RULE = "occurrence must be unix seconds"

        private val TASK_NAME = Regex("[a-z0-9][a-z0-9_-]{0,63}")

        /** A task's name as the contract carries it: lowercase letters, digits, `_` and `-`, at most 64. */
        fun isTaskName(s: String?) = s != null && TASK_NAME.matches(s)

        /**
         * A request body as a service reads it. Fields this version doesn't know are ignored (the
         * vectors' own `why` among them); a field it knows with the wrong type is invalid.
         */
        fun parse(body: String): Parsed {
            val json = parseObject(body) ?: return Parsed.Invalid(NOT_AN_OBJECT)
            val v = json["v"].integer()
            if (v != VERSION.toLong()) return Parsed.Invalid(VERSION_RULE)
            val task = json["task"].string()
            if (!isTaskName(task)) return Parsed.Invalid(TASK_RULE)
            val owner = json["owner"].string()
            if (!isPubKeyHex(owner)) return Parsed.Invalid(OWNER_RULE)
            val occurrence = json["occurrence"].integer()
            if (occurrence == null || occurrence < 0) return Parsed.Invalid(OCCURRENCE_RULE)
            return Parsed.Valid(WakeRequest(task!!, owner!!, occurrence))
        }
    }
}

/**
 * A service's `200` body: settled, nothing to do, with its own outcome name (`NOT_DUE`,
 * `ATTEMPTED`, `NOT_NAMED`, …). The name is the service's enum, so it is safe to log; anything
 * that isn't shaped like one is not an answer.
 */
class SettledAnswer(
    val outcome: String,
) {
    init {
        require(OUTCOME.matches(outcome)) { "an outcome is an enum name" }
    }

    fun toJson(): JsonObject = buildJsonObject { put("outcome", outcome) }

    override fun toString() = toJson().toString()

    companion object {
        private val OUTCOME = Regex("[A-Z][A-Z0-9_]{0,63}")

        fun parse(body: String): SettledAnswer? =
            parseObject(body)
                ?.get("outcome")
                .string()
                ?.takeIf(OUTCOME::matches)
                ?.let(::SettledAnswer)
    }
}

private fun parseObject(body: String): JsonObject? =
    try {
        Json.parseToJsonElement(body) as? JsonObject
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

private fun JsonElement?.string(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON integer: not a string, not a fraction, not a boolean. */
private fun JsonElement?.integer(): Long? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
