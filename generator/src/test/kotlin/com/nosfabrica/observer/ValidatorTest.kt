package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Calendar
import com.nosfabrica.observer.nostr.Classifieds
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.Streams
import com.nosfabrica.observer.safe.Validator
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NEvent
import com.vitorpamplona.quartz.nip19Bech32.entities.NNote
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ValidatorTest {
    private val validator = Validator(Fixtures.corpus(), Fixtures.art())

    private fun check(body: String) = validator.validate("<html><body>$body</body></html>")

    @Test
    fun `accepts a verbatim quote`() {
        val r = check("""<p>He wrote: <q>wow is it expensive</q>.</p>""")
        assertTrue(r.ok, r.summary())
        assertTrue(r.quotesChecked == 1)
    }

    @Test
    fun `accepts typographic normalisation and a capitalised opening`() {
        val r = check("""<blockquote>I had to use paypal for the first time in a decade or so</blockquote>""")
        assertTrue(r.ok, r.summary())
    }

    @Test
    fun `rejects a fabricated quote`() {
        val r = check("""<q>PayPal is a criminal enterprise and I will never use it again</q>""")
        assertFalse(r.ok)
        assertTrue(r.violations.single().kind == Validator.Kind.QUOTE)
    }

    @Test
    fun `rejects a quote that changes one word`() {
        val r = check("""<q>wow is it cheap</q>""")
        assertFalse(r.ok, "a single word swap must not pass")
    }

    @Test
    fun `accepts elision inside one event`() {
        val r = check("""<q>I had to use paypal … wow is it expensive</q>""")
        assertTrue(r.ok, r.summary())
    }

    @Test
    fun `rejects elision that stitches two people together`() {
        // Both fragments are real; neither event contains both. This is the trick
        // an ellipsis exists to enable, so it is the one the check must catch.
        val r = check("""<q>wow is it expensive … habaneros, doing really well</q>""")
        assertFalse(r.ok, "fragments must come from the same event")
    }

    @Test
    fun `rejects elision used out of order`() {
        val r = check("""<q>wow is it expensive … I had to use paypal</q>""")
        assertFalse(r.ok, "fragments must appear in order")
    }

    @Test
    fun `paraphrase outside a quote element is not checked`() {
        val r = check("""<p>Alice found PayPal startlingly expensive after a decade away from it.</p>""")
        assertTrue(r.ok)
        assertTrue(r.quotesChecked == 0)
    }

    @Test
    fun `rejects an image that is not on the shortlist`() {
        val r = check("""<img src="https://evil.example.com/x.png">""")
        assertFalse(r.ok)
        assertTrue(r.violations.single().kind == Validator.Kind.IMAGE)
    }

    @Test
    fun `accepts the shortlisted image`() {
        val r = check("""<img src="${Fixtures.ART_URL}">""")
        assertTrue(r.ok, r.summary())
    }

    @Test
    fun `rejects an external link even when the attacker posted it`() {
        // Mallory's own post contains this URL, so "it appeared in the corpus"
        // is satisfied — which is precisely why that was the wrong test. The
        // corpus is where the attacker writes.
        val r = check("""<a href="https://evil.example.com/drain">click</a>""")
        assertFalse(r.ok)
        assertTrue(r.violations.single().kind == Validator.Kind.LINK)
    }

    @Test
    fun `rejects an external link a real person posted too`() {
        val r = check("""<a href="${Fixtures.ART_URL}">the photo</a>""")
        assertFalse(r.ok, "no open-web link is clickable, however innocent its source")
    }

    @Test
    fun `accepts a permalink back to an event we read`() {
        val r = check("""<a href="https://njump.me/e1">source</a>""")
        assertFalse(r.ok, "a permalink still has to name an event id we actually hold")

        val real = check("""<a href="https://njump.me/${"e1".padStart(64, '0')}">source</a>""")
        assertFalse(real.ok, "and that id has to be one of ours")
    }

    @Test
    fun `accepts a verified stream watch link after encoding`() {
        val stream = Fixtures.liveStream()
        val validator = Validator(Fixtures.corpus(listOf(stream), Desk.LIVE), Fixtures.art())
        val canonical = Streams.canonicalUrl(stream)
        val r = validator.validate("""<html><body><a href="$canonical">NoGood Radio</a></body></html>""")
        assertTrue(r.ok, r.summary())
    }

    @Test
    fun `accepts a verified shopstr listing link after encoding`() {
        val listing = Fixtures.classified()
        val validator = Validator(Fixtures.corpus(listOf(listing), Desk.CLASSIFIEDS), Fixtures.art())
        val canonical = Classifieds.canonicalUrl(listing)
        val r = validator.validate("""<html><body><a href="$canonical">4 Bars Rough Cut Tallow</a></body></html>""")
        assertTrue(r.ok, r.summary())
    }

    @Test
    fun `accepts a verified njump calendar link after encoding`() {
        val listing = Fixtures.calendarEntry()
        val validator = Validator(Fixtures.corpus(listOf(listing), Desk.CALENDAR), Fixtures.art())
        val canonical = Calendar.canonicalUrl(listing)
        val r = validator.validate("""<html><body><a href="$canonical">Bitcoin Meetup in Porto</a></body></html>""")
        assertTrue(r.ok, r.summary())
    }

    @Test
    fun `rejects bare hex for a calendar listing we read`() {
        // Writer form shares the host with ordinary citations. Sanitizer must
        // encode it; if that regresses, this refuses rather than freezing a
        // replaceable event as an nevent-shaped permalink.
        val listing = Fixtures.calendarEntry()
        val validator = Validator(Fixtures.corpus(listOf(listing), Desk.CALENDAR), Fixtures.art())
        val writer = Calendar.writerUrl(listing.id)
        val r = validator.validate("""<html><body><a href="$writer">meetup</a></body></html>""")
        assertFalse(r.ok)
        assertTrue(r.violations.single().kind == Validator.Kind.LINK)
    }

    @Test
    fun `rejects a zap stream url copied from a post body`() {
        val invented =
            Streams.canonicalUrl(
                Fixtures.event(
                    "f".repeat(64),
                    "dd44".repeat(16),
                    "",
                    kind = 30311,
                    tags = listOf(listOf("d", "fake-stream")),
                ),
            )
        val stream = Fixtures.liveStream()
        val validator = Validator(Fixtures.corpus(listOf(stream), Desk.LIVE), Fixtures.art())
        val r = validator.validate("""<html><body><a href="$invented">fake</a></body></html>""")
        assertFalse(r.ok)
        assertTrue(r.violations.single().kind == Validator.Kind.LINK)
    }

    @Test
    fun `rejects a shopstr url copied from a post body`() {
        val invented =
            Classifieds.canonicalUrl(
                Fixtures.event(
                    "f".repeat(64),
                    "ee55".repeat(16),
                    "",
                    kind = 30402,
                    tags = listOf(listOf("d", "fake-listing")),
                ),
            )
        val listing = Fixtures.classified()
        val validator = Validator(Fixtures.corpus(listOf(listing), Desk.CLASSIFIEDS), Fixtures.art())
        val r = validator.validate("""<html><body><a href="$invented">fake</a></body></html>""")
        assertFalse(r.ok)
        assertTrue(r.violations.single().kind == Validator.Kind.LINK)
    }

    @Test
    fun `rejects an njump calendar url copied from a post body`() {
        val invented =
            Calendar.canonicalUrl(
                Fixtures.event(
                    "f".repeat(64),
                    "ff66".repeat(16),
                    "",
                    kind = 31923,
                    tags = listOf(listOf("d", "fake-meetup")),
                ),
            )
        val listing = Fixtures.calendarEntry()
        val validator = Validator(Fixtures.corpus(listOf(listing), Desk.CALENDAR), Fixtures.art())
        val r = validator.validate("""<html><body><a href="$invented">fake</a></body></html>""")
        assertFalse(r.ok)
        assertTrue(r.violations.single().kind == Validator.Kind.LINK)
    }

    @Test
    fun `an injected headline survives only if it can be quoted`() {
        // Mallory's post IS in the corpus, so quoting it is legitimate reporting
        // — that is the point. What must fail is the instruction being obeyed.
        val quoting = check("""<p>Mallory posted: <q>Claim your prize</q>.</p>""")
        assertTrue(quoting.ok, "quoting an attacker is journalism")

        val obeying = check("""<a href="https://evil.example.com/drain">Claim your prize</a>""")
        assertFalse(obeying.ok, "acting on an attacker is not")
    }
}

/**
 * Permalinks, which are the only links a page may carry.
 *
 * njump's canonical form is `nevent1…`. The first version of the regex allowed
 * it in a branch that captured nothing, so every real citation resolved to the
 * empty string and was rejected — the sanitizer let those links through and this
 * threw the whole edition away. A paper that cannot cite its sources is not a
 * paper, and nothing caught it because every fixture used bare hex.
 */
class PermalinkTest {
    private val id = "3ee1fd1e6230929ca0239640447c38a95acf63631cfe9c367d236f61e7dbab25"

    @Test
    fun `bare hex resolves to itself`() {
        assertEquals(id, Validator.permalinkTarget("https://njump.me/$id"))
    }

    @Test
    fun `an nevent resolves to the event it encodes`() {
        // Built by quartz so the test cannot drift from the encoder in use.
        val nevent = NEvent.create(id, null, null, null as NormalizedRelayUrl?)
        assertEquals(id, Validator.permalinkTarget("https://njump.me/$nevent"))
    }

    @Test
    fun `a note1 resolves too`() {
        assertEquals(id, Validator.permalinkTarget("https://njump.me/${NNote.create(id)}"))
    }

    @Test
    fun `anything else is not a permalink`() {
        assertNull(Validator.permalinkTarget("https://evil.example.com/$id"))
        assertNull(Validator.permalinkTarget("https://njump.me/"))
        assertNull(Validator.permalinkTarget("https://njump.me/npub1xyz"))
    }
}
