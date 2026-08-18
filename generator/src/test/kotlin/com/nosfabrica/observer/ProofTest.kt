package com.nosfabrica.observer

import com.nosfabrica.observer.safe.Proof
import com.nosfabrica.observer.safe.Sanitizer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * The check that opens the page.
 *
 * These are the only tests in the suite that run a browser, and they exist
 * because everything else here checks what comes OUT of the sanitizer. That is
 * how an edition shipped with no stylesheet at all: correct markup, every quote
 * verified, a hundred and thirty green tests, and a column of unstyled text.
 *
 * A machine without Chromium skips rather than fails — [Proof] degrades to
 * "not rendered" on purpose, and a check that cannot run must not cost anybody
 * their paper.
 */
class ProofTest {
    private companion object {
        val proof = Proof()

        @JvmStatic
        @AfterAll
        fun shut() = proof.close()

        /** Enough words that [Proof] does not simply call the page empty. */
        val WORDS = "Two blocks, then nothing: the network spent the day arguing about entropy. ".repeat(12)

        fun page(
            style: String,
            body: String = "<main><h1>The Nostr Observer</h1><p>$WORDS</p></main>",
        ) = "<!doctype html><html><head><style>$style</style></head><body>$body</body></html>"
    }

    private fun check(html: String): Proof.Report {
        val report = proof.check(html)
        assumeTrue(report.ran, "no browser on this machine")
        return report
    }

    @Test
    fun `an ordinary page reads`() {
        assertTrue(check(page("body { color: #111; background: #fff; font: 16px serif }")).ok)
    }

    @Test
    fun `ink the colour of the paper is caught`() {
        // The failure a free-form stylesheet actually produces, and the one a
        // reader reports as "the page is blank".
        val report = check(page("body { color: #f4f4f4; background: #fff }"))
        assertFalse(report.ok)
        assertTrue(report.findings.any { it.what.startsWith("unreadable") }, report.summary())
    }

    @Test
    fun `a picture nothing constrains pushes the page sideways`() {
        // Every image request is answered with a 4000px stand-in, so an
        // unconstrained one overflows for real instead of being guessed at
        // through a CSS property -- which is what the first version did, and it
        // read `figure img { width: 100% }` as unconstrained.
        val report =
            check(
                page(
                    "body { color: #111; background: #fff }",
                    "<main><h1>x</h1><p>$WORDS</p><img src=\"https://example.com/a.jpg\" alt=\"\"></main>",
                ),
            )
        assertFalse(report.ok)
        assertTrue(report.findings.any { it.what.startsWith("overflows") }, report.summary())
    }

    @Test
    fun `the same picture inside a stylesheet that constrains it does not`() {
        assertTrue(
            check(
                page(
                    "body { color: #111; background: #fff } img { width: 100%; height: auto }",
                    "<main><h1>x</h1><p>$WORDS</p><img src=\"https://example.com/a.jpg\" alt=\"\"></main>",
                ),
            ).ok,
        )
    }

    @Test
    fun `a heading with nothing under it is an empty section`() {
        val report =
            check(
                page(
                    "body { color: #111; background: #fff }",
                    "<main><p>$WORDS</p></main><section><h2>The Shop Floor</h2></section>",
                ),
            )
        assertFalse(report.ok)
        assertTrue(report.findings.any { it.what == "empty section" }, report.summary())
    }

    @Test
    fun `the bug that started all this — classes with no rules behind them`() {
        val naked =
            "<!doctype html><html><body><main class=\"sheet\"><h1 class=\"masthead\">The Nostr Observer</h1>" +
                "<p class=\"lede\">$WORDS</p></main></body></html>"
        val report = check(naked)
        assertFalse(report.ok)
        assertTrue(report.findings.any { it.what == "no stylesheet" }, report.summary())
    }

    /**
     * The last rung of the ladder in [com.nosfabrica.observer.Press.edition].
     *
     * When a page will not read twice running, the author's stylesheet is
     * dropped and the house one carries it. The property that matters is that
     * what comes back is still styled — falling back to NO stylesheet would
     * reproduce the original bug as the fix for it.
     */
    @Test
    fun `falling back to the house layout leaves a page that still reads`() {
        val hostile = page("body { color: #fff; background: #fff }")
        val sanitizer = Sanitizer(emptyList())
        assertFalse(check(sanitizer.sanitize(hostile).html).ok, "the author's stylesheet is the problem")

        val fallen = sanitizer.sanitize(hostile, keepAuthorCss = false)
        assertTrue(fallen.removed.any { it.contains("house layout") }, fallen.removed.toString())
        assertTrue(fallen.html.contains("<style>"), "the house sheet still ships")
        assertTrue(check(fallen.html).ok, "the house layout has to be the safe one")
    }
}
