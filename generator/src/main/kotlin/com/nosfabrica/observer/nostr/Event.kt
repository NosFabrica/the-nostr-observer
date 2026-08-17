package com.nosfabrica.observer.nostr

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * A Nostr event, as the relay hands it over.
 *
 * Deliberately NOT a `@Serializable` class. Events are loosely typed by design —
 * tags are arrays of arbitrary strings whose meaning depends on the kind — and a
 * generated deserializer would either reject the shapes we have not enumerated
 * or force every field through `JsonElement` anyway. Reading the few fields we
 * care about off a [JsonObject] costs less and never throws on an event kind
 * nobody has thought about yet.
 *
 * The whole corpus reaching the generator is UNTRUSTED: every field below was
 * written by someone who is not the reader. Nothing here validates or escapes —
 * that happens at the boundary, in `safe/`.
 */
data class NostrEvent(
    val id: String,
    val pubkey: String,
    val createdAt: Long,
    val kind: Int,
    val tags: List<List<String>>,
    val content: String,
) {
    /** Every tag with this name, values only (the name itself dropped). */
    fun tags(name: String): List<List<String>> = tags.filter { it.firstOrNull() == name }.map { it.drop(1) }

    /** The first value of the first tag with this name, or null. */
    fun tag(name: String): String? = tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

    /** Hashtags, lowercased and deduplicated — `t` tags are inconsistently cased in the wild. */
    fun hashtags(): List<String> = tags("t").mapNotNull { it.firstOrNull()?.lowercase() }.distinct()

    /** The client that published this, without the NIP-89 address suffix. */
    fun client(): String? = tag("client")?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        fun from(json: JsonObject): NostrEvent? {
            // A malformed event is a fact about a stranger's relay, not an error
            // on our side, so it is dropped rather than thrown. One bad event
            // must never cost the reader their whole edition.
            return try {
                NostrEvent(
                    id = json["id"]!!.jsonPrimitive.content,
                    pubkey = json["pubkey"]!!.jsonPrimitive.content,
                    createdAt = json["created_at"]!!.jsonPrimitive.long,
                    kind = json["kind"]!!.jsonPrimitive.int,
                    tags =
                        json["tags"]?.jsonArray.orEmpty().map { row ->
                            (row as? JsonArray).orEmpty().map { (it as? JsonPrimitive)?.contentOrNull ?: "" }
                        },
                    content = json["content"]?.jsonPrimitive?.contentOrNull ?: "",
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}

/** A kind-0 profile, flattened to the handful of fields a byline needs. */
data class Profile(
    val pubkey: String,
    val createdAt: Long,
    val name: String?,
    val displayName: String?,
    val nip05: String?,
    val about: String?,
) {
    /** What to print in a byline. Falls back to a short hex so nobody is nameless. */
    fun byline(): String =
        displayName?.takeIf { it.isNotBlank() }
            ?: name?.takeIf { it.isNotBlank() }
            ?: pubkey.take(8)

    companion object {
        fun from(event: NostrEvent): Profile? {
            if (event.kind != 0) return null
            // Profile content is a JSON string INSIDE a JSON string, written by
            // whatever client the user happened to use. Plenty of them are not
            // valid JSON at all; those people still get a byline from the hex.
            val obj = runCatching { Json.parseToJsonElement(event.content).jsonObject }.getOrNull()

            fun str(key: String) =
                obj
                    ?.get(key)
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.takeIf { it.isNotBlank() }
            return Profile(
                pubkey = event.pubkey,
                createdAt = event.createdAt,
                name = str("name"),
                displayName = str("display_name") ?: str("displayName"),
                nip05 = str("nip05"),
                about = str("about"),
            )
        }
    }
}

private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()

internal val Json =
    kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
