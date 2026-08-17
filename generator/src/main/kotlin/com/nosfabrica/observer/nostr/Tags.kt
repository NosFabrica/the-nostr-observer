package com.nosfabrica.observer.nostr

import com.vitorpamplona.quartz.nip01Core.core.Event

/**
 * The few tag reads this project does that quartz has no named helper for.
 *
 * Anything with a NIP behind it goes through quartz's own accessor instead —
 * `AdvertisedRelayListEvent.writeRelays()` for NIP-65, `tags.serviceProviders()`
 * for NIP-85, `MetadataEvent.contactMetaData()` for kind 0. These are only the
 * generic leftovers.
 */
fun Event.values(name: String): List<List<String>> = tags.filter { it.firstOrNull() == name }.map { it.drop(1) }

fun Event.value(name: String): String? = tags.firstOrNull { it.firstOrNull() == name }?.getOrNull(1)

/** Hashtags, lowercased and deduplicated — `t` tags are inconsistently cased in the wild. */
fun Event.hashtags(): List<String> = values("t").mapNotNull { it.firstOrNull()?.lowercase() }.distinct()

/** The client that published this, without the NIP-89 address suffix. */
fun Event.client(): String? = value("client")?.substringBefore(':')?.trim()?.takeIf { it.isNotEmpty() }
