package com.nosfabrica.observer.safe

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Playwright
import com.microsoft.playwright.options.ColorScheme
import java.io.Closeable

/**
 * Does the page actually read?
 *
 * The sanitizer says the page cannot misbehave and the validator says it does
 * not lie. Neither of them opens it. Nothing did, and the first real edition
 * shipped with no stylesheet at all — 33KB of correct, verified, unreadable
 * HTML that every one of 132 tests was happy with, because they all check what
 * comes OUT of the sanitizer and none of them asked whether a person could read
 * the result.
 *
 * So this is the check that needs a browser. It is deliberately narrow: the
 * failures worth catching mechanically are the ones a person would spot in a
 * second and a test never will.
 *
 * ## What it does not do
 *
 * Remote images are BLOCKED. Generation must not fan out to a dozen third-party
 * media hosts on the reader's behalf, and a proof that depends on somebody
 * else's uptime is a proof that fails for reasons that have nothing to do with
 * the page. The cost is real and worth stating: overflow caused by an
 * unconstrained image is not caught here, so [imagesAreConstrained] checks the
 * CSS property that would prevent it instead of waiting to see it happen.
 */
class Proof(
    private val widths: List<Int> = listOf(390, 1280),
) : Closeable {
    private companion object {
        /** Deliberately far wider than any viewport, so anything unconstrained shows. */
        const val GIANT = """<svg xmlns="http://www.w3.org/2000/svg" width="4000" height="3000"></svg>"""
    }

    data class Finding(
        val what: String,
        val detail: String,
    )

    data class Report(
        val findings: List<Finding>,
        /** Null when no browser could be started, which is NOT a failed page. */
        val ran: Boolean,
    ) {
        val ok: Boolean get() = findings.isEmpty()

        fun summary(): String =
            when {
                !ran -> "not rendered (no browser)"
                ok -> "renders clean"
                else -> "${findings.size} problem(s): " + findings.joinToString("; ") { it.what }
            }
    }

    private val playwright: Playwright? = runCatching { Playwright.create() }.getOrNull()

    private val browser: Browser? =
        playwright?.let {
            runCatching {
                it.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
            }.getOrNull()
        }

    /**
     * Render and inspect, or say honestly that we could not.
     *
     * A missing browser must never fail an edition. This is a check that adds
     * confidence, not a gate the reader's paper depends on being able to run —
     * a deployment without Chromium should lose the check and keep the paper.
     */
    fun check(html: String): Report {
        val page = browser?.newPage() ?: return Report(emptyList(), ran = false)
        val findings = mutableListOf<Finding>()
        try {
            // Nothing leaves the machine, and every picture is enormous.
            //
            // Blocking remote art outright was the first attempt and it hid the
            // failure worth finding: a blocked image has no intrinsic size, so
            // an unconstrained one cannot overflow and the check had to guess at
            // a CSS property instead. Guessing got it wrong — it read
            // `max-width: none` as unconstrained and flagged
            // `figure img { width: 100% }`, which constrains perfectly well.
            //
            // Serving a 4000px stand-in instead means the real failure happens
            // for real: an image the stylesheet does not constrain pushes the
            // page past the viewport and the overflow check names it. Nothing
            // is fetched, the result does not depend on somebody else's uptime,
            // and the thing being tested is the thing that would break.
            page.route("**/*") { route ->
                val url = route.request().url()
                when {
                    url.startsWith("data:") -> {
                        route.resume()
                    }

                    route.request().resourceType() == "image" -> {
                        route.fulfill(
                            com.microsoft.playwright.Route
                                .FulfillOptions()
                                .setStatus(200)
                                .setContentType("image/svg+xml")
                                .setBody(GIANT),
                        )
                    }

                    else -> {
                        route.abort()
                    }
                }
            }

            for (width in widths) {
                for (scheme in listOf(ColorScheme.LIGHT, ColorScheme.DARK)) {
                    page.emulateMedia(
                        com.microsoft.playwright.Page
                            .EmulateMediaOptions()
                            .setColorScheme(scheme),
                    )
                    page.setViewportSize(width, 900)
                    page.setContent(html)
                    val label = "${width}px ${scheme.name.lowercase()}"

                    overflow(page, width)?.let { findings += Finding("overflows at $label", it) }
                    unreadable(page)?.let { findings += Finding("unreadable at $label", it) }
                }
            }

            // Structure is scheme- and width-independent, so it is asked once.
            page.setViewportSize(widths.last(), 900)
            page.setContent(html)
            empty(page)?.let { findings += Finding("empty section", it) }
            tooShort(page)?.let { findings += Finding("nothing to read", it) }
            unstyled(page)?.let { findings += Finding("no stylesheet", it) }
        } finally {
            runCatching { page.close() }
        }
        return Report(findings, ran = true)
    }

    /**
     * A page wider than its window.
     *
     * The one layout failure that is unambiguous: on a phone it means the
     * reader swipes sideways to finish a sentence. Two pixels of tolerance
     * because sub-pixel rounding produces a scrollWidth one larger than the
     * viewport on pages that are visibly fine.
     */
    private fun overflow(
        page: com.microsoft.playwright.Page,
        width: Int,
    ): String? {
        val scroll = (page.evaluate("() => document.documentElement.scrollWidth") as Number).toInt()
        if (scroll <= width + 2) return null
        val culprit =
            page.evaluate(
                """() => {
                    const w = document.documentElement.clientWidth;
                    for (const el of document.querySelectorAll('*')) {
                        const r = el.getBoundingClientRect();
                        if (r.right > w + 2 || r.left < -2) {
                            return el.tagName.toLowerCase() + (el.className ? '.' + String(el.className).split(' ')[0] : '');
                        }
                    }
                    return 'unknown';
                }""",
            )
        return "${scroll}px of content in ${width}px, first past the edge: $culprit"
    }

    /**
     * Text the reader cannot see.
     *
     * Not a general contrast audit — the failure this catches is the one a
     * free-form stylesheet actually produces: a theme where the ink and the
     * paper are the same colour, or nearly. WCAG AA for body text is 4.5:1 and
     * that is the number used, applied to the body only, because judging every
     * element would fail honest editorial choices like a muted caption.
     */
    private fun unreadable(page: com.microsoft.playwright.Page): String? {
        val ratio =
            page.evaluate(
                """() => {
                    const lum = (c) => {
                        const [r, g, b] = c.map(v => {
                            v /= 255;
                            return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
                        });
                        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
                    };
                    const parse = (s) => (s.match(/\d+(\.\d+)?/g) || []).slice(0, 3).map(Number);
                    const body = document.body;
                    let bg = getComputedStyle(body).backgroundColor;
                    let el = body;
                    // Walk up for the first painted background; `transparent`
                    // on the body is normal and means the html element's.
                    while (el && (bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent')) {
                        el = el.parentElement;
                        bg = el ? getComputedStyle(el).backgroundColor : 'rgb(255, 255, 255)';
                    }
                    const fg = parse(getComputedStyle(body).color);
                    const back = parse(bg || 'rgb(255, 255, 255)');
                    if (fg.length < 3 || back.length < 3) return 21;
                    const a = lum(fg), b = lum(back);
                    return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
                }""",
            ) as Number
        return if (ratio.toDouble() >= 4.5) null else "body text contrast %.1f:1, below 4.5:1".format(ratio.toDouble())
    }

    /** A heading with nothing under it. "Sections are earned" cuts both ways. */
    private fun empty(page: com.microsoft.playwright.Page): String? {
        val found =
            page.evaluate(
                """() => {
                    for (const s of document.querySelectorAll('section, article')) {
                        const text = (s.innerText || '').trim();
                        const heading = s.querySelector('h1, h2, h3');
                        if (heading && text.length <= (heading.innerText || '').trim().length + 4) {
                            return (heading.innerText || '').trim().slice(0, 60);
                        }
                    }
                    return null;
                }""",
            )
        return found as String?
    }

    /** A page that rendered almost nothing, whatever the markup claimed. */
    private fun tooShort(page: com.microsoft.playwright.Page): String? {
        val chars = (page.evaluate("() => (document.body.innerText || '').trim().length") as Number).toInt()
        return if (chars >= 400) null else "$chars characters of visible text"
    }

    /**
     * A page whose classes resolve to nothing.
     *
     * This is the check that exists because of the bug that started all of
     * this: an edition shipped using `class="sheet"`, `class="masthead"` and
     * the rest, with no stylesheet at all. Every other check here passed it —
     * unstyled HTML wraps, contrasts and fills a page perfectly well. What it
     * does not do is look like a newspaper, and the only mechanical trace of
     * that is markup asking for rules nobody defined.
     */
    private fun unstyled(page: com.microsoft.playwright.Page): String? {
        val found =
            page.evaluate(
                """() => {
                    const classed = document.querySelectorAll('[class]').length;
                    if (classed === 0) return null;
                    let rules = 0;
                    for (const sheet of document.styleSheets) {
                        try { rules += sheet.cssRules.length; } catch (e) { /* cross-origin, not ours */ }
                    }
                    return rules === 0 ? classed + ' elements carry classes and no rule is defined' : null;
                }""",
            )
        return found as String?
    }

    override fun close() {
        runCatching { browser?.close() }
        runCatching { playwright?.close() }
    }
}
