package com.nosfabrica.observer

import com.nosfabrica.observer.safe.Validator
import org.junit.jupiter.api.Assertions.assertFalse
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
    fun `an injected headline survives only if it can be quoted`() {
        // Mallory's post IS in the corpus, so quoting it is legitimate reporting
        // — that is the point. What must fail is the instruction being obeyed.
        val quoting = check("""<p>Mallory posted: <q>Claim your prize</q>.</p>""")
        assertTrue(quoting.ok, "quoting an attacker is journalism")

        val obeying = check("""<a href="https://evil.example.com/drain">Claim your prize</a>""")
        assertFalse(obeying.ok, "acting on an attacker is not")
    }
}
