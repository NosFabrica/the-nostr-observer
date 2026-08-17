package com.nosfabrica.observer.nostr

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Minting a lens: the part we can build, and the part we cannot yet.
 *
 * Only ~302 pubkeys on the whole network have a usable `kind 10040`, so for
 * almost every reader this is the step between them and a paper. It has two
 * halves and they belong to different people:
 *
 *  1. A SCORING SERVICE computes that reader's personal web of trust and starts
 *     publishing `kind 30382` cards about the people in it. One provider
 *     identity per observer — measured 2026-08-17, 276 distinct provider keys
 *     across 302 lenses — so this is real compute, not a signature, and it is
 *     the operator's to do. [Provisioner] is the seam.
 *  2. THE READER signs a `kind 10040` naming that service, so the store knows
 *     whose scores they trust. That is [template] below, and it needs a signer,
 *     which arrives in Phase 3.
 *
 * Neither `nip85.nosfabrica.com` nor `scores.brainstorm.world` exposes an HTTP
 * API for step 1 — checked 2026-08-17, both answer NIP-11 as plain strfry
 * relays and nothing else. So there is deliberately no HTTP client here: an
 * invented endpoint would compile, pass a mocked test, and fail the first time
 * anybody ran it.
 */
object LensRequest {
    /** The dimensions a lens is asked for. `rank` is the only one that ranks. */
    val DIMENSIONS = listOf("30382:rank", "30382:followers")

    /**
     * The unsigned `kind 10040` for a reader to sign.
     *
     * Every entry is public and carries a relay hint, because those are the two
     * conditions the store requires: a private (NIP-44) entry, a hintless one and
     * a followers-only list all resolve to nothing and leave ranked search empty
     * while looking, from the outside, exactly like success.
     */
    fun template(
        service: String,
        relay: String,
        createdAt: Long,
    ): String {
        require(service.length == 64) { "a service pubkey is 64 hex characters, got ${service.length}" }
        require(relay.startsWith("wss://") || relay.startsWith("ws://")) { "a relay hint must be a websocket URL" }
        return buildJsonObject {
            put("kind", 10040)
            put("created_at", createdAt)
            put("content", "")
            put(
                "tags",
                buildJsonArray {
                    DIMENSIONS.forEach { dimension ->
                        add(
                            buildJsonArray {
                                add(dimension)
                                add(service)
                                add(relay)
                            },
                        )
                    }
                    add(
                        buildJsonArray {
                            add("client")
                            add("the-nostr-observer")
                        },
                    )
                },
            )
        }.toString()
    }

    /**
     * Asking an operator to score a reader they have never scored.
     *
     * An interface rather than an implementation on purpose. When the scoring
     * service grows a way to be asked, this is the one place that changes; until
     * then [Manual] is the honest implementation, and it is honest because it
     * tells the reader a person is involved rather than pretending to queue
     * something.
     */
    interface Provisioner {
        /** The service pubkey and relay a new observer should name, or null if it cannot be arranged. */
        suspend fun mint(observer: String): Assignment?

        data class Assignment(
            val service: String,
            val relay: String,
            val readyEstimate: String,
        )
    }

    /** No API exists yet, so say so rather than fake a queue. */
    object Manual : Provisioner {
        override suspend fun mint(observer: String): Provisioner.Assignment? = null

        const val EXPLANATION: String =
            "Minting a lens needs the scoring service to compute this reader's web of trust and publish " +
                "kind 30382 cards for it. Neither nip85.nosfabrica.com nor scores.brainstorm.world exposes " +
                "an API for that yet, so it is an operator step. Meanwhile the reader gets a provisional " +
                "edition built from their own follow list."
    }
}
