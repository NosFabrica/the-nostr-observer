package com.nosfabrica.observer.nostr

import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Can this relay rank for this reader — and if not, which link of the chain is
 * missing?
 *
 * A port of vespa-relay's `shared/readiness.js`. Ported rather than rewritten,
 * because three of its properties are load-bearing and none of them are obvious:
 *
 *  1. THE FIRST UNMET LINK WINS. Every link below it reports [Status.WAITING],
 *     never a second failure. A column of red crosses says four things are wrong
 *     when one is, and sends the reader off to fix three things that are fine.
 *
 *  2. THE RANKED PROBE IS NOT REDUNDANT with the score count above it. Cards can
 *     be present and not yet PROJECTED — the trust projection is per service and
 *     a service new to the relay is derived by a reconcile that runs at startup.
 *     Only asking the authed and anonymous reads the same question catches that.
 *     Asking both is also what stops an empty corpus reading as a broken lens.
 *
 *  3. "YOUR OWN POSTS ARE BEHIND" IS AN ASIDE, NOT A LINK. Ranking is complete
 *     without it. Folding it into the chain tells a reader whose lens is
 *     perfectly healthy that their search is broken, and the fix for it is
 *     nothing at all.
 *
 * Measured 2026-08-17: of 244 authors on the prototype front page, 228 cleared
 * link 1 and 11 cleared link 2. That ratio is why this module exists at all.
 */
object Readiness {
    enum class Status { OK, PARTIAL, BROKEN, WAITING, ASIDE }

    enum class Tone { OK, PARTIAL, WORKING, BLOCKED }

    data class Link(
        val key: String,
        val status: Status,
        val detail: String? = null,
    )

    data class Verdict(
        val state: String,
        val tone: Tone,
        val percent: Double?,
        val chain: List<Link>,
    ) {
        val ranks: Boolean get() = state == "ready" || state == "posts-behind" || state == "importing"
    }

    /**
     * A count, as opposed to a reason there isn't one.
     *
     * Null means "not asked yet" and drives [checking]. It must never be read as
     * zero: "this relay holds none of that service's cards" and "we have not
     * looked" are different sentences with different next steps.
     */
    data class Counts(
        val here: Long?,
        val there: Long?,
    )

    data class Facts(
        /** Write relays from kind 10002. Null = not asked. `seen` distinguishes an empty list from no list. */
        val writeRelays: List<String>? = null,
        val relayListSeen: Boolean = false,
        /** Null = not asked; false = no kind 10040 at all. */
        val scoreListSeen: Boolean? = null,
        /** The `30382:rank` service and its relay hint, from the 10040. */
        val rankService: String? = null,
        val rankRelay: String? = null,
        val scores: Counts? = null,
        /** Rows the authenticated and anonymous reads returned for the same question. */
        val probeAuthed: Long? = null,
        val probeAnon: Long? = null,
        val posts: Counts? = null,
    )

    fun counted(v: Long?): Boolean = v != null && v >= 0

    /**
     * here/there as 0..1, or null when there is no honest denominator.
     *
     * Null is a supported answer and the caller must draw nothing rather than
     * estimate — NIP-45 COUNT is optional and widely unimplemented. Capped at 1
     * because we can hold MORE than an upstream serves (it deleted, we did not)
     * and "118%" reads as a bug.
     */
    fun fraction(
        here: Long?,
        there: Long?,
    ): Double? {
        if (!counted(here) || !counted(there) || there!! <= 0L) return null
        return min(1.0, here!!.toDouble() / there.toDouble())
    }

    /**
     * The last few per cent of an import buy a reader almost nothing — the cards
     * still to come are the tail of the service's own ranking, the accounts it
     * scored lowest — while a panel headed "99%" is a warning about a search that
     * is, for anything they will actually look at, complete. Compared against the
     * ROUNDED percentage so the rule and the sentence agree: no panel ever prints
     * 90% or more.
     */
    private fun shortOfEnough(pct: Double?): Boolean = pct != null && (pct * 100).roundToInt() < 90

    fun assess(f: Facts): Verdict {
        val chain = mutableListOf<Link>()

        fun checking() = Verdict("checking", Tone.WORKING, null, chain.toList())

        fun waitingBelow(vararg keys: String) = keys.forEach { chain.add(Link(it, Status.WAITING)) }

        // --- link 1: do we know where you post? -----------------------------
        val writes = f.writeRelays ?: return checking()
        if (writes.isEmpty()) {
            // Two different facts, and telling a reader the wrong one sends them
            // to fix something that is not broken. NO list is permanent — nothing
            // will ever discover them. A list we cannot USE is their list being
            // unreachable, a different sentence with the same next step.
            chain.add(Link("relayList", Status.BROKEN, "declared=${writes.size}"))
            waitingBelow("scoreList", "scores", "ranked")
            val state = if (f.relayListSeen) "no-usable-relays" else "no-relay-list"
            return Verdict(state, Tone.BLOCKED, null, chain.toList())
        }
        chain.add(Link("relayList", Status.OK, "${writes.size} write relays"))

        // --- link 2: do you name a service whose scores rank? ---------------
        val seen = f.scoreListSeen ?: return checking()
        if (!seen) {
            chain.add(Link("scoreList", Status.BROKEN, "absent"))
            waitingBelow("scores", "ranked")
            return Verdict("no-score-list", Tone.BLOCKED, null, chain.toList())
        }
        if (f.rankService.isNullOrBlank()) {
            // A 10040 declaring only `30382:followers` can ORDER a list but cannot
            // RANK one, and a private (NIP-44) or hintless entry resolves to
            // nothing. All three are a broken link rather than a missing one.
            chain.add(Link("scoreList", Status.BROKEN, "no rank dimension"))
            waitingBelow("scores", "ranked")
            return Verdict("no-rank-service", Tone.BLOCKED, null, chain.toList())
        }
        chain.add(Link("scoreList", Status.OK, "${f.rankService.take(8)} @ ${f.rankRelay ?: "no hint"}"))

        // --- link 3: have the scores arrived? -------------------------------
        val scores = f.scores ?: return checking()
        if (scores.here == null) return checking()
        val pct = fraction(scores.here, scores.there)
        if (scores.here == 0L) {
            // Zero here IS a claim: this relay answered, and holds none of that
            // service's cards. Ranked search returns nothing, so this is blocked
            // and not partial, whatever the upstream says.
            chain.add(Link("scores", Status.BROKEN, "0 here"))
            waitingBelow("ranked")
            return Verdict("no-scores-yet", Tone.BLOCKED, 0.0, chain.toList())
        }
        val short = shortOfEnough(pct)
        chain.add(Link("scores", if (short) Status.PARTIAL else Status.OK, "${scores.here} here"))

        // --- link 4: does a ranked read actually come back? -----------------
        val anon = f.probeAnon ?: return checking()
        val authed = f.probeAuthed ?: return checking()
        if (anon > 0 && authed == 0L) {
            chain.add(Link("ranked", Status.BROKEN, "authed=0 anon=$anon"))
            return Verdict("projection-pending", Tone.BLOCKED, pct, chain.toList())
        }
        chain.add(Link("ranked", if (short) Status.PARTIAL else Status.OK, "authed=$authed"))

        if (short) return Verdict("importing", Tone.PARTIAL, pct, chain.toList())
        // Importing with no denominator: we hold cards and cannot say what
        // fraction that is. Still worth saying — "3,197 here" answers "is
        // anything happening" — but it is not a bar.
        if (pct == null && !counted(scores.there)) {
            return Verdict("importing", Tone.PARTIAL, null, chain.toList())
        }

        // --- your own posts: downstream, and NOT in the chain ---------------
        val posts = f.posts ?: return Verdict("ready", Tone.OK, null, chain.toList())
        val postPct = fraction(posts.here, posts.there)
        chain.add(Link("posts", Status.ASIDE, "${posts.here} here"))
        if (postPct != null && postPct < 1.0) {
            return Verdict("posts-behind", Tone.WORKING, postPct, chain.toList())
        }
        return Verdict("ready", Tone.OK, null, chain.toList())
    }

    /** What to tell the reader. The decision above holds the ordering; this holds only words. */
    fun explain(v: Verdict): String =
        when (v.state) {
            "checking" -> {
                "Checking whether this relay can rank for you."
            }

            "no-relay-list" -> {
                "You have no relay list (kind 10002) anywhere we can see, so nothing about you is ever fetched. " +
                    "Publish one from your usual client — this is the only link that cannot fix itself."
            }

            "no-usable-relays" -> {
                "Your relay list names no relay we can reach. Check the URLs in it."
            }

            "no-score-list" -> {
                "You have not named a scoring service yet (kind 10040). That is the one thing standing between " +
                    "you and a paper, and we can set it up for you."
            }

            "no-rank-service" -> {
                "Your kind 10040 names no public `30382:rank` service with a relay hint — a followers-only, " +
                    "private or hintless entry cannot rank. We can republish it."
            }

            "no-scores-yet" -> {
                "Your scoring service has been named but none of its cards have reached this relay yet. " +
                    "Nothing for you to do; this is us waiting."
            }

            "projection-pending" -> {
                "Your scores are here but not yet projected. This clears on its own."
            }

            "importing" -> {
                "Importing your provider's scores" + (v.percent?.let { " — ${(it * 100).roundToInt()}%" } ?: "") + "."
            }

            "posts-behind" -> {
                "Ready. (Your own posts are still catching up, which changes nothing about ranking.)"
            }

            "ready" -> {
                "Ready."
            }

            else -> {
                v.state
            }
        }
}
