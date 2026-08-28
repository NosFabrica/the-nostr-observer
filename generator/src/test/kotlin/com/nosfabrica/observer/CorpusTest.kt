package com.nosfabrica.observer

import com.nosfabrica.observer.corpus.ArtDesk
import com.nosfabrica.observer.corpus.Digest
import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.Pull
import com.nosfabrica.observer.nostr.Readiness
import com.nosfabrica.observer.nostr.Relays
import com.vitorpamplona.quartz.nip19Bech32.decodePublicKeyAsHexOrNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ArtDeskTest {
    @Test
    fun `declared mime beats the url extension in both directions`() {
        assertFalse(ArtDesk.isImage("https://x/thumb.jpg", "video/mp4"), "a .jpg that declares video is video")
        assertTrue(ArtDesk.isImage("https://x/no-extension", "image/webp"), "no extension, declared image")
    }

    @Test
    fun `falls back to the extension only when nothing is declared`() {
        assertTrue(ArtDesk.isImage("https://x/a.png", null))
        assertFalse(ArtDesk.isImage("https://x/a.mov", null))
        assertFalse(ArtDesk.isImage("https://x/whatever", null), "unknown stays out rather than guessing")
    }

    @Test
    fun `parses the decimal dimensions clients actually send`() {
        assertEquals(1080 to 1920, ArtDesk.parseDim("1080.0x1920.0"))
        assertEquals(640 to 480, ArtDesk.parseDim("640x480"))
        assertEquals(null to null, ArtDesk.parseDim("wide"))
        assertEquals(null to null, ArtDesk.parseDim(null))
    }

    @Test
    fun `a video contributes its poster frame, never its video url`() {
        // A newspaper prints a still from the film. The `imeta` names the video
        // in `url` and the poster in `image`; taking the wrong one puts an mp4
        // in an <img> and renders a broken box under a caption.
        val event =
            Fixtures.event(
                "v1",
                Fixtures.ALICE,
                "Late-night kitchen crafting",
                kind = 34236,
                tags =
                    listOf(
                        listOf(
                            "imeta",
                            "url https://media.example.com/clip.mp4",
                            "m video/mp4",
                            // NO extension, which is how every real one arrives.
                            "image https://media.example.com/7f4e797e1ef33ffbb26530d1235e7f6c",
                            "dim 1080x1920",
                        ),
                    ),
            )
        val art = ArtDesk.shortlist(Fixtures.corpus(listOf(event)))
        assertEquals(1, art.size)
        assertEquals("https://media.example.com/7f4e797e1ef33ffbb26530d1235e7f6c", art.single().url)
        assertTrue(art.single().portrait, "the poster describes the video's frame")
    }

    @Test
    fun `a poster is taken on kind alone, even with no mime declared`() {
        // A real kind 34235 carried a poster and no `m` at all. Deciding on the
        // mime would have dropped it; the event's kind is the fact that holds.
        val event =
            Fixtures.event(
                "v3",
                Fixtures.ALICE,
                "A documentary",
                kind = 34235,
                tags = listOf(listOf("imeta", "url https://media.example.com/film", "image https://img.example.com/still")),
            )
        assertEquals("https://img.example.com/still", ArtDesk.shortlist(Fixtures.corpus(listOf(event))).single().url)
    }

    @Test
    fun `art the sanitizer would strip is not offered`() {
        // The sanitizer allows only https in an `img src`, so an http poster
        // would leave a hole where a picture was promised.
        val event =
            Fixtures.event(
                "v4",
                Fixtures.ALICE,
                "A clip",
                kind = 34236,
                tags =
                    listOf(
                        listOf("imeta", "url http://media.example.com/clip.mp4", "m video/mp4", "image http://img.example.com/still"),
                    ),
            )
        assertTrue(ArtDesk.shortlist(Fixtures.corpus(listOf(event))).isEmpty())
    }

    @Test
    fun `a video with no poster contributes no art at all`() {
        // Measured 2026-08-18: about five videos in six carry no poster. They
        // become text stories rather than broken figures.
        val event =
            Fixtures.event(
                "v2",
                Fixtures.ALICE,
                "A clip",
                kind = 34236,
                tags = listOf(listOf("imeta", "url https://media.example.com/clip.mp4", "m video/mp4")),
            )
        assertTrue(ArtDesk.shortlist(Fixtures.corpus(listOf(event))).isEmpty())
    }

    @Test
    fun `shortlist takes images and leaves video behind`() {
        val list = ArtDesk.shortlist(Fixtures.corpus())
        assertEquals(1, list.size, "one still among two videos: ${list.map { it.url }}")
        assertEquals(Fixtures.ART_URL, list.single().url)
        assertTrue(list.single().portrait)
        assertEquals("art-1", list.single().id)
    }

    @Test
    fun `caption drops the url the client pasted into the body`() {
        val art = ArtDesk.shortlist(Fixtures.corpus()).single()
        assertFalse(art.caption.contains("http"), "caption was '${art.caption}'")
        assertTrue(art.caption.startsWith("Zimbabwe Black"))
    }
}

class DigestTest {
    @Test
    fun `caps one author before they own a desk`() {
        val flood = (1..40).map { Fixtures.event("f$it", Fixtures.MALLORY, "filing number $it") }
        val rendered = Digest().render(Fixtures.corpus(flood), emptyList())
        assertEquals(20, rendered.kept, "notes cap is per author")
        assertEquals(20, rendered.dropped)
    }

    @Test
    fun `collapses the same post filed to several hosts`() {
        val text = "I always thought lions were the kings of the jungle and tigers were just oversized cats."
        val dupes = (1..4).map { Fixtures.event("d$it", Fixtures.ALICE, "$text https://host$it.example/x.jpg") }
        val rendered = Digest().render(Fixtures.corpus(dupes), emptyList())
        assertEquals(1, rendered.kept, "four uploads of one confession is one post")
    }

    @Test
    fun `renders bylines art ids and the corpus text`() {
        val rendered = Digest().render(Fixtures.corpus(), Fixtures.art())
        assertTrue(rendered.text.contains("Alice"))
        assertTrue(rendered.text.contains("ART: art-1"))
        assertTrue(rendered.text.contains("wow is it expensive"))
        assertTrue(rendered.approxTokens > 0)
    }
}

/**
 * NIP-19 is quartz's, not ours — an earlier version of this project shipped
 * fifty lines of hand-rolled bech32 for one function. These stay because the
 * CLI's front door depends on the behaviour, not because the decoder is ours to
 * test: a typo must not silently resolve to somebody else, and an nsec pasted
 * into the reader field must never become a filter.
 */
class PubkeyInputTest {
    @Test
    fun `decodes real npubs to the pubkeys they belong to`() {
        assertEquals(
            "fb89e58f838b7d716a88300ea1f2539fff78766aa1121ec10968b6b10a498f28",
            decodePublicKeyAsHexOrNull("npub1lwy7trur3d7hz65gxq82rujnnllhsan25yfpasgfdzmtzzjf3u5q0v4zv0"),
        )
        assertEquals(
            "30e8cbf1427c137fa60674a639431c19a9d6f4c07fd2959df83158e674fccbaa",
            decodePublicKeyAsHexOrNull("npub1xr5vhu2z0sfhlfsxwjnrjscurx5adaxq0lfft80cx9vwva8uew4qk293g6"),
        )
    }

    @Test
    fun `passes hex straight through`() {
        assertEquals(Fixtures.OBSERVER, decodePublicKeyAsHexOrNull(Fixtures.OBSERVER))
    }

    @Test
    fun `rejects a typo rather than returning the wrong reader`() {
        assertNull(decodePublicKeyAsHexOrNull("npub1lwy7trur3d7hz65gxq82rujnnllhsan25yfpasgfdzmtzzjf3u5q0v4zv1"))
    }

    /**
     * The one place quartz is not enough, and it is a sharp one.
     *
     * `decodePublicKeyAsHexOrNull` decodes any 32-byte bech32 payload despite
     * its name: measured, a valid nsec comes back as the hex of the SECRET key
     * rather than as null. A reader who pastes their nsec into the front door
     * would have their private key placed in a relay filter and sent over the
     * wire. Main guards on the prefix before ever calling it.
     */
    @Test
    fun `decoding an nsec yields key material, so the CLI must refuse it first`() {
        val nsec = "nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"
        assertNotNull(decodePublicKeyAsHexOrNull(nsec), "quartz decodes it — that is exactly the problem")
        assertTrue(nsec.startsWith("nsec1"), "which is why the guard is a prefix check, before decoding")
    }
}

class ReadinessTest {
    private val service = "7d7ffd720b90".padEnd(64, '0')

    @Test
    fun `nothing asked yet is checking, not broken`() {
        assertEquals("checking", Readiness.assess(Readiness.Facts()).state)
    }

    @Test
    fun `no relay list is the permanent failure and everything below it waits`() {
        val v = Readiness.assess(Readiness.Facts(writeRelays = emptyList(), relayListSeen = false))
        assertEquals("no-relay-list", v.state)
        assertEquals(Readiness.Tone.BLOCKED, v.tone)
        // The whole point of the port: one broken link, not four.
        assertEquals(1, v.chain.count { it.status == Readiness.Status.BROKEN })
        assertEquals(3, v.chain.count { it.status == Readiness.Status.WAITING })
    }

    @Test
    fun `a list we cannot use says something different from having no list`() {
        val v = Readiness.assess(Readiness.Facts(writeRelays = emptyList(), relayListSeen = true))
        assertEquals("no-usable-relays", v.state)
    }

    @Test
    fun `a followers-only 10040 is a broken link, not a missing one`() {
        val v =
            Readiness.assess(
                Readiness.Facts(writeRelays = listOf("wss://a"), scoreListSeen = true, rankService = null),
            )
        assertEquals("no-rank-service", v.state)
    }

    @Test
    fun `zero cards here is blocked whatever the upstream says`() {
        val v =
            Readiness.assess(
                Readiness.Facts(
                    writeRelays = listOf("wss://a"),
                    scoreListSeen = true,
                    rankService = service,
                    scores = Readiness.Counts(0, 240_000),
                ),
            )
        assertEquals("no-scores-yet", v.state)
        assertEquals(0.0, v.percent)
        assertFalse(v.ranks)
    }

    @Test
    fun `cards present but unprojected is its own state`() {
        val v =
            Readiness.assess(
                Readiness.Facts(
                    writeRelays = listOf("wss://a"),
                    scoreListSeen = true,
                    rankService = service,
                    scores = Readiness.Counts(240_000, 240_000),
                    probeAnon = 12,
                    probeAuthed = 0,
                ),
            )
        assertEquals("projection-pending", v.state, "this is what the count above cannot see")
    }

    @Test
    fun `an empty corpus is not read as a broken lens`() {
        val v =
            Readiness.assess(
                Readiness.Facts(
                    writeRelays = listOf("wss://a"),
                    scoreListSeen = true,
                    rankService = service,
                    scores = Readiness.Counts(240_000, 240_000),
                    probeAnon = 0,
                    probeAuthed = 0,
                ),
            )
        assertEquals("ready", v.state, "both sockets empty means a quiet window, not a failure")
    }

    @Test
    fun `the last few per cent of an import count as done`() {
        fun at(here: Long) =
            Readiness
                .assess(
                    Readiness.Facts(
                        writeRelays = listOf("wss://a"),
                        scoreListSeen = true,
                        rankService = service,
                        scores = Readiness.Counts(here, 1000),
                        probeAnon = 5,
                        probeAuthed = 5,
                    ),
                ).state
        assertEquals("importing", at(700))
        // No panel ever prints 90% or more.
        assertEquals("ready", at(900))
        assertEquals("ready", at(1000))
    }

    @Test
    fun `own posts lagging is an aside and still ranks`() {
        val v =
            Readiness.assess(
                Readiness.Facts(
                    writeRelays = listOf("wss://a"),
                    scoreListSeen = true,
                    rankService = service,
                    scores = Readiness.Counts(1000, 1000),
                    probeAnon = 5,
                    probeAuthed = 5,
                    posts = Readiness.Counts(3, 10),
                ),
            )
        assertEquals("posts-behind", v.state)
        assertTrue(v.ranks, "ranking is complete without your own posts")
    }

    @Test
    fun `fraction refuses to guess without a denominator`() {
        assertNull(Readiness.fraction(5, null))
        assertNull(Readiness.fraction(5, 0))
        assertEquals(1.0, Readiness.fraction(120, 100), "we can hold more than an upstream serves")
    }
}

/**
 * A highlight is somebody else's sentence.
 *
 * The digest used to render a `kind 9802` exactly like a post: byline of the
 * highlighter, no source, no author. A model reading that writes `Gigi wrote
 * "..."` when Gigi only marked the passage — a real quote under the wrong name,
 * published with the reader's signature on it. The validator cannot catch it,
 * because the text IS verbatim in a source event; text fidelity and correct
 * attribution are different properties and only one was being checked.
 */
class HighlightTest {
    private fun render(vararg tags: List<String>): String {
        val highlight =
            Fixtures.event("h1", Fixtures.ALICE, "human code review has very nearly run its course", kind = 9802, tags = tags.toList())
        return Digest().render(Fixtures.corpus(listOf(highlight), Desk.HIGHLIGHTS), emptyList()).text
    }

    @Test
    fun `the byline says who highlighted it, not who wrote it`() {
        val text = render(listOf("p", Fixtures.MALLORY), listOf("r", "https://example.com/essay"))
        assertTrue(text.contains("HIGHLIGHTED BY Alice"), text.take(300))
        assertTrue(text.contains("NOT the highlighter's words"), text.take(300))
    }

    @Test
    fun `the original author is named and resolved to a name`() {
        val text = render(listOf("p", Fixtures.MALLORY))
        assertTrue(text.contains("AUTHOR: Mallory"), text.take(300))
    }

    @Test
    fun `an unnamed author says so rather than staying silent`() {
        // Silence is what invites the writer to fall back on the byline, which
        // is the highlighter. Measured: only 11 highlights in 31 carry a `p`.
        val text = render(listOf("r", "https://example.com/essay"))
        assertTrue(text.contains("AUTHOR: not named"), text.take(300))
        assertTrue(text.contains("SOURCE: https://example.com/essay"), text.take(300))
    }

    @Test
    fun `context is offered as background and marked unquotable`() {
        // Only the excerpt is verbatim-checked. A writer quoting the context
        // would lose the whole edition at the validator.
        val text = render(listOf("context", "The surrounding passage of the essay."))
        assertTrue(text.contains("do not quote as the excerpt"), text.take(300))
        assertTrue(text.contains("The surrounding passage"), text.take(300))
    }
}

/**
 * The second chain: can you host your paper?
 *
 * It fails independently of the lens, and it is asked at PRE-FLIGHT. The
 * kind 10063 check used to happen at publish time — after an edition had been
 * generated and paid for — so a reader learned they had nowhere to put their
 * paper at the single most expensive moment to find out.
 */
class StorageChainTest {
    @Test
    fun `not asked yet is checking, never a refusal`() {
        assertEquals("checking", Readiness.storage(Readiness.Storage()).state)
    }

    @Test
    fun `no server list is blocked, and says so in words`() {
        val v = Readiness.storage(Readiness.Storage(serverListSeen = false))
        assertEquals("no-blossom-server", v.state)
        assertEquals(Readiness.Status.BROKEN, v.chain.first { it.key == "blossomServers" }.status)
        assertTrue(Readiness.explainStorage(v).contains("still read today's paper"))
    }

    @Test
    fun `a list naming nothing usable reads differently from no list`() {
        // Same distinction the relay list draws. One is "you never published
        // one"; the other is "the one you published names nothing we can use".
        val absent = Readiness.storage(Readiness.Storage(serverListSeen = false))
        val useless = Readiness.storage(Readiness.Storage(serverListSeen = true))
        assertEquals("absent", absent.chain.first().detail)
        assertEquals("list names no usable server", useless.chain.first().detail)
    }

    @Test
    fun `a server makes it publishable, with consent still unasked`() {
        val v = Readiness.storage(Readiness.Storage(serverListSeen = true, servers = listOf("https://b.example.com")))
        assertEquals("can-publish", v.state)
        // Consent cannot be pre-flighted: asking means a signer prompt for an
        // upload the reader has not requested.
        assertEquals(Readiness.Status.WAITING, v.chain.first { it.key == "uploadConsent" }.status)
    }

    @Test
    fun `having published before is the only evidence of consent we get`() {
        val v =
            Readiness.storage(
                Readiness.Storage(serverListSeen = true, servers = listOf("https://b.example.com"), publishedBefore = true),
            )
        assertEquals(Readiness.Status.OK, v.chain.first { it.key == "uploadConsent" }.status)
    }

    @Test
    fun `a broken lens does not break storage, or the reverse`() {
        // The whole reason there are two chains. A reader with a perfect lens
        // and no media server can READ their paper; one with a server and no
        // lens has nothing to put on it.
        val storageFine = Readiness.storage(Readiness.Storage(serverListSeen = true, servers = listOf("https://b.example.com")))
        val lensBroken = Readiness.assess(Readiness.Facts(writeRelays = emptyList(), relayListSeen = false))
        assertEquals("can-publish", storageFine.state)
        assertEquals("no-relay-list", lensBroken.state)
        assertTrue(lensBroken.chain.none { it.key.startsWith("blossom") }, "the lens chain must not mention storage")
    }
}

/**
 * The budget is shared, not raced for.
 *
 * One pass in desk order spent it first-come, so a verbose desk near the front
 * silently emptied whichever desk was declared LAST in the enum. Observed
 * between two consecutive live runs: app releases went from 3 of 3 to 1 of 3
 * for no reason but upstream verbosity, with the enum's declaration order
 * quietly acting as an editorial priority.
 */
class BudgetTest {
    private fun corpusOf(vararg desks: Pair<Desk, List<com.vitorpamplona.quartz.nip01Core.core.Event>>) =
        Corpus(
            observer = Fixtures.OBSERVER,
            since = 1_786_800_000,
            until = 1_786_900_000,
            ranked = desks.toMap(),
            control = emptyList(),
            dayNotes = null,
            profiles = emptyMap(),
        )

    /**
     * Distinct authors and distinct text, or the digest's own pruning eats the
     * fixture before the budget ever sees it: identical content collapses as
     * duplicates, and one author is capped per desk. The first version of these
     * tests measured that instead, and two of them passed vacuously.
     */
    private fun bulk(
        prefix: String,
        n: Int,
        chars: Int,
    ) = (1..n).map { i ->
        Fixtures.event("$prefix$i", "%064x".format(i), "$prefix story $i " + "word$i ".repeat(chars / 8))
    }

    @Test
    fun `a greedy front desk cannot empty the last one`() {
        // Sized so the crumb left over from a first-come pass cannot hold even
        // one app release. An earlier version of this test used small app
        // blocks, which still squeezed into the remainder -- so it passed under
        // the very rule it was written to catch.
        val corpus = corpusOf(Desk.NOTES to bulk("n", 60, 4_000), Desk.APPS to bulk("a", 3, 4_000))
        val rendered = Digest(budgetChars = 20_000).render(corpus, emptyList())
        assertTrue(rendered.text.contains("APP RELEASES"), "the last desk lost everything to the first")
        assertEquals(3, Regex("""APP RELEASES \((\d+) of""").find(rendered.text)!!.groupValues[1].toInt())
    }

    @Test
    fun `a desk that wants less than its share does not hoard it`() {
        // Fair share is a floor, not a quota: what one desk leaves goes to the
        // others rather than being wasted.
        val corpus = corpusOf(Desk.NOTES to bulk("n", 40, 500), Desk.APPS to bulk("a", 1, 100))
        val rendered = Digest(budgetChars = 40_000).render(corpus, emptyList())
        assertEquals(41, rendered.kept, "everything fits, so everything is kept")
    }

    @Test
    fun `the header counts what was printed, not what was pruned`() {
        val corpus = corpusOf(Desk.NOTES to bulk("n", 30, 4_000))
        val text = Digest(budgetChars = 20_000).render(corpus, emptyList()).text
        val header = Regex("""NOTES \((\d+) of (\d+)\)""").find(text)!!
        val printed = header.groupValues[1].toInt()
        assertTrue(printed < 30, "the budget must have cut this short")
        assertEquals(printed, Regex("""^--- """, RegexOption.MULTILINE).findAll(text).count())
    }
}

/**
 * The reader is not the news.
 *
 * A paper is what other people did today. The observer's own posts rank highly
 * through their own lens almost by construction, so without this they crowd the
 * front page with things the reader already knows they wrote.
 */
class BelongsTest {
    private val pull = Pull(Relays(), "wss://example.invalid")

    @Test
    fun `the observer's own events are not a story`() {
        val mine = Fixtures.event("m1", Fixtures.OBSERVER, "my own note")
        val theirs = Fixtures.event("t1", Fixtures.ALICE, "somebody else's note")
        assertEquals(listOf(theirs), pull.belongs(Desk.NOTES, Fixtures.OBSERVER, listOf(mine, theirs)))
    }

    @Test
    fun `a stream that has already ended is not on air`() {
        // A kind 30311 is replaceable, so the record of a finished stream sits
        // in the window looking exactly like a running one.
        fun stream(
            id: String,
            status: String,
        ) = Fixtures.event(id, Fixtures.ALICE, "", kind = 30311, tags = listOf(listOf("status", status)))
        val live = stream("s1", "live")
        val kept = pull.belongs(Desk.LIVE, Fixtures.OBSERVER, listOf(live, stream("s2", "ended"), stream("s3", "planned")))
        assertEquals(listOf(live), kept)
    }

    @Test
    fun `both rules apply at once`() {
        val mineLive =
            Fixtures.event("s4", Fixtures.OBSERVER, "", kind = 30311, tags = listOf(listOf("status", "live")))
        assertTrue(pull.belongs(Desk.LIVE, Fixtures.OBSERVER, listOf(mineLive)).isEmpty(), "my own stream is still mine")
    }
}

/**
 * The two desks whose whole point is a fact stored in a tag.
 *
 * A calendar entry is a date and a classified is a price. Both were rendered
 * with their title, their location and their body, and neither with the thing a
 * reader would act on — so two real editions printed zero of twenty-eight
 * calendar events between them, and described the one listing that made it as
 * "super rare vintage" with no number attached.
 *
 * These tests all fail against that renderer, which is the only reason to
 * believe they test anything.
 */
class ListingTest {
    private fun render(
        desk: Desk,
        event: com.vitorpamplona.quartz.nip01Core.core.Event,
    ) = Digest().render(Fixtures.corpus(listOf(event), desk), emptyList()).text

    @Test
    fun `a timed calendar entry says when, in the organiser's timezone`() {
        // 1792105200 is 19:00 in New York and 23:00 UTC. Printing ours turns a
        // seven-o'clock meetup into an eleven-o'clock one, and anything after
        // eight moves to the following day outright.
        val meetup =
            Fixtures.event(
                "c1",
                Fixtures.ALICE,
                "Come along",
                kind = 31923,
                tags =
                    listOf(
                        listOf("title", "Jersey City Bitcoin"),
                        listOf("start", "1792105200"),
                        listOf("start_tzid", "America/New_York"),
                        listOf("end", "1792112400"),
                    ),
            )
        val text = render(Desk.CALENDAR, meetup)
        assertTrue(text.contains("WHEN: Thu 2026-10-15 19:00 America/New_York"), text)
        assertTrue(text.contains("until Thu 2026-10-15 21:00"), "an end time is half of an evening: $text")
    }

    @Test
    fun `an all-day entry keeps its date instead of vanishing`() {
        // Kind 31922 writes a bare `YYYY-MM-DD`. Reading only unix seconds
        // would drop every one of them while the timed half kept working.
        val allDay =
            Fixtures.event(
                "c2",
                Fixtures.ALICE,
                "All weekend",
                kind = 31922,
                tags = listOf(listOf("title", "Nostrasia"), listOf("start", "2026-11-01")),
            )
        val text = render(Desk.CALENDAR, allDay)
        assertTrue(text.contains("WHEN: 2026-11-01 (all day)"), text)
    }

    @Test
    fun `a dateless entry says so, so the posting time cannot stand in for it`() {
        val undated = Fixtures.event("c3", Fixtures.ALICE, "soon", kind = 31923, tags = listOf(listOf("title", "TBD")))
        val text = render(Desk.CALENDAR, undated)
        assertTrue(text.contains("WHEN: not stated"), text)
    }

    @Test
    fun `a live stream carries a watch url`() {
        val stream =
            Fixtures.event(
                Fixtures.STREAM_ID,
                Fixtures.ALICE,
                "",
                kind = 30311,
                tags =
                    listOf(
                        listOf("d", Fixtures.STREAM_D),
                        listOf("title", "NoGood Radio"),
                        listOf("status", "live"),
                        listOf("starts", "1786900000"),
                    ),
            )
        val text = render(Desk.LIVE, stream)
        assertTrue(text.contains("watch: https://zap.stream/stream/${Fixtures.STREAM_ID}"), text)
    }

    @Test
    fun `a live stream without a d tag has no watch url`() {
        val bare =
            Fixtures.event(
                Fixtures.STREAM_ID,
                Fixtures.ALICE,
                "",
                kind = 30311,
                tags = listOf(listOf("title", "NoGood Radio"), listOf("status", "live")),
            )
        val text = render(Desk.LIVE, bare)
        assertFalse(text.contains("watch:"), text)
    }

    @Test
    fun `a classified carries its price`() {
        val listing =
            Fixtures.event(
                Fixtures.LISTING_ID,
                Fixtures.ALICE,
                "Two modules, boxed.",
                kind = 30402,
                tags =
                    listOf(
                        listOf("d", Fixtures.LISTING_D),
                        listOf("title", "Super rare Micron memory modules"),
                        listOf("price", "210000", "SATS"),
                        listOf("status", "active"),
                        listOf("condition", "used"),
                    ),
            )
        val text = render(Desk.CLASSIFIEDS, listing)
        assertTrue(text.contains("PRICE: 210000 SATS"), text)
        assertTrue(text.contains("STATUS: active"), text)
        assertTrue(text.contains("CONDITION: used"), text)
        assertTrue(text.contains("listing: https://shopstr.store/listing/${Fixtures.LISTING_ID}"), text)
    }

    @Test
    fun `a classified without a d tag has no listing url`() {
        val bare =
            Fixtures.event(
                Fixtures.LISTING_ID,
                Fixtures.ALICE,
                "Two modules, boxed.",
                kind = 30402,
                tags =
                    listOf(
                        listOf("title", "Super rare Micron memory modules"),
                        listOf("price", "210000", "SATS"),
                    ),
            )
        val text = render(Desk.CLASSIFIEDS, bare)
        assertFalse(text.contains("listing:"), text)
    }

    @Test
    fun `a rental says what the price is per`() {
        val rental =
            Fixtures.event(
                "s2",
                Fixtures.ALICE,
                "Desk space",
                kind = 30402,
                tags = listOf(listOf("title", "Hot desk"), listOf("price", "50", "EUR", "day")),
            )
        assertTrue(render(Desk.CLASSIFIEDS, rental).contains("PRICE: 50 EUR per day"))
    }

    @Test
    fun `a note is not given a date it does not have`() {
        // The renderers are dispatched by desk. If that dispatch ever widened,
        // an ordinary post carrying a stray `start` tag would grow a diary line.
        val note = Fixtures.event("n1", Fixtures.ALICE, "hello", tags = listOf(listOf("start", "1792105200")))
        assertFalse(render(Desk.NOTES, note).contains("WHEN:"))
    }
}
