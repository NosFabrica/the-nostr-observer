package com.nosfabrica.observer.safe

import com.nosfabrica.observer.corpus.Art
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist

/**
 * The trust boundary. Everything the generator wrote crosses it; nothing
 * executable comes out the other side.
 *
 * The rule this enforces is NOT "the model may not write markup" — an earlier
 * design said that, and it bought safety by deleting the product. It is:
 *
 *     the published page cannot execute, cannot phone home, and cannot
 *     impersonate.
 *
 * That is a property of the artifact, checkable afterwards, and it costs the
 * writer nothing. The model picks the grid, the sections and the typography; it
 * simply cannot reach outside the page.
 *
 * This runs LAST and it runs on everything. Once an edition is signed and
 * uploaded to the reader's own Blossom server it is content-addressed and beyond
 * recall, and it is served under whatever headers that host sets — so on the
 * published copy this class is the only protection there is. Our own preview
 * adds a CSP with no `script-src`, but that is a backstop for a bug in here, not
 * a substitute for it.
 */
class Sanitizer(
    art: List<Art>,
    /**
     * Events this edition actually read, so a citation can be checked here too.
     *
     * Empty means "do not check membership", which is what the shape-only tests
     * want. A real run passes the corpus, because the alternative is that a
     * permalink to an event nobody read survives this pass and then fails the
     * validator — and a validator failure throws away the WHOLE edition, where
     * this throws away one link.
     */
    private val corpusEventIds: Set<String> = emptySet(),
    /**
     * The house stylesheet, which SHIPS WITH THE PAGE.
     *
     * It did not, and the first real edition was the thing that showed it. The
     * writer is handed this as reference in the system prompt and told that
     * most days need no `<style>` block of their own — so it wrote none, used
     * `class="sheet"`, `class="masthead"`, `class="folio"` and the rest, and
     * the published file contained no CSS at all. Every class resolved to
     * nothing. A newspaper rendered as a column of unstyled text.
     *
     * It goes FIRST so the author's own block, when there is one, overrides it
     * by ordinary cascade rather than by fighting specificity.
     */
    private val houseCss: String = read("/house.css"),
) {
    /** id -> real URL. The only image sources that will survive. */
    private val byId: Map<String, Art> = art.associateBy { it.id }
    private val allowedUrls: Set<String> = art.map { it.url }.toSet()

    private companion object {
        fun read(path: String): String =
            Sanitizer::class.java
                .getResourceAsStream(path)
                ?.bufferedReader()
                ?.readText()
                ?: error("missing resource $path — the stylesheet ships with the jar")
    }

    data class Result(
        val html: String,
        /** Everything removed, in the order it was found. Shown to the operator, not the reader. */
        val removed: List<String>,
    ) {
        val clean: Boolean get() = removed.isEmpty()
    }

    fun sanitize(
        rawHtml: String,
        /**
         * The house layout as a last resort.
         *
         * A page that fails to READ is almost always the author's stylesheet's
         * doing — the markup underneath it is the same markup that reads fine
         * with nothing but the house sheet. Dropping it is the fallback the
         * plan names for a page that will not render: plain and legible beats
         * styled and broken, and it costs no second generation.
         *
         * Nothing about safety changes here. The author's CSS was already
         * cleaned; this is an editorial retreat, not a security one.
         */
        keepAuthorCss: Boolean = true,
    ): Result {
        val removed = mutableListOf<String>()
        val doc = Jsoup.parse(rawHtml)
        doc.outputSettings().prettyPrint(false)

        val title = doc.title().ifBlank { "Edition" }
        val authored = extractCss(doc, removed)
        val css = if (keepAuthorCss) authored else ""
        if (!keepAuthorCss && authored.isNotBlank()) {
            removed.add("the author's stylesheet, ${authored.length} chars — fell back to the house layout")
        }
        // Ours, and it goes through the same pass anyway. The day somebody adds
        // a webfont to the house sheet is the day every published edition calls
        // a third party on open, and "we wrote it" is not a property the reader
        // of a hosted page can check.
        val house = cleanCss(houseCss, removed)
        resolveArt(doc, removed)
        unwrapExternalLinks(doc, removed)
        noteRemovals(doc, removed)

        val cleanedBody = Cleaner(safelist()).clean(doc).body()
        cleanInlineStyles(cleanedBody, removed)
        openCitationsInNewTab(cleanedBody)

        return Result(rebuild(title, house, css, cleanedBody.html()), removed)
    }

    // ---------------------------------------------------------------- art ---

    /**
     * `<img src="art-3">` becomes the URL the source event actually declared.
     *
     * This is why the shortlist hands over ids instead of URLs. An id the model
     * invented resolves to nothing and the whole figure goes, caption and all —
     * a caption describing a photograph that is not there is worse than no
     * photograph. A URL the model invented never gets a chance to resolve,
     * because a raw URL in `src` is not looked up at all: it is removed.
     */
    private fun resolveArt(
        doc: Document,
        removed: MutableList<String>,
    ) {
        for (img in doc.select("img").toList()) {
            val ref = img.attr("src").trim()
            val art = byId[ref] ?: byId[ref.removePrefix("#")]
            if (art == null) {
                // Tolerate the model citing a real URL directly rather than its
                // id — the reference is still provably from the corpus, which is
                // the property that matters.
                if (ref in allowedUrls) continue
                removed.add("img with unknown source '${ref.take(60)}'")
                (img.closest("figure") ?: img).remove()
                continue
            }
            img.attr("src", art.url)
            if (img.attr("alt").isBlank()) img.attr("alt", art.alt ?: art.caption.take(120))
            // Hotlinked art rots on somebody else's server. Referrer-free loading
            // is a courtesy to the reader; lazy loading is one to the host.
            img.attr("loading", "lazy")
            img.attr("referrerpolicy", "no-referrer")
        }
    }

    /**
     * The paper prints addresses; it does not make them clickable.
     *
     * A URL in the corpus is not evidence that the URL is safe — the corpus is
     * where the attacker writes. Anything that is not a permalink back to an
     * event we actually read is unwrapped to its own text: the reader still sees
     * what was said, and nothing under their masthead is one tap from a drainer.
     */
    private fun unwrapExternalLinks(
        doc: Document,
        removed: MutableList<String>,
    ) {
        for (a in doc.select("a[href]").toList()) {
            val href = a.attr("href")
            if (!href.startsWith("http", ignoreCase = true)) continue
            val cited = Validator.permalinkTarget(href)
            if (cited != null && (corpusEventIds.isEmpty() || cited in corpusEventIds)) continue
            removed.add("link to ${href.take(60)} (unwrapped to text)")
            a.unwrap()
        }
    }

    /**
     * A citation is a source, not a page that should replace the edition.
     * Whatever survived [unwrapExternalLinks] is a permalink; send it to a
     * new tab and cut `window.opener`.
     */
    private fun openCitationsInNewTab(body: Element) {
        for (a in body.select("a[href]")) {
            a.attr("target", "_blank")
            a.attr("rel", "noopener noreferrer")
        }
    }

    // ---------------------------------------------------------------- css ---

    /**
     * Pull the author's `<style>` out before [Cleaner] runs, because Cleaner
     * discards head content wholesale and the stylesheet is the one thing in the
     * head worth keeping.
     */
    private fun extractCss(
        doc: Document,
        removed: MutableList<String>,
    ): String {
        val blocks = doc.select("style").toList()
        val css = blocks.joinToString("\n") { it.data() }
        blocks.forEach { it.remove() }
        return cleanCss(css, removed)
    }

    /**
     * A denylist over CSS, and deliberately not a parser.
     *
     * CSS cannot execute in any browser this page will meet, so the risk is not
     * script — it is REACHING OUT. A single `background:url(https://attacker/?x)`
     * in a published edition pings a stranger's server for every reader who opens
     * it, which is a tracking beacon wearing a stylesheet. So every `url()` must
     * resolve to art we already allowed, and `@import` — which can pull an entire
     * remote stylesheet — goes unconditionally.
     *
     * Writing a real CSS parser to do this better would be the wrong trade: the
     * constructs that can fetch or execute are few and named, and the CSP on our
     * preview plus the sanitized `img` list cover what regexes miss.
     */
    internal fun cleanCss(
        css: String,
        removed: MutableList<String>,
    ): String {
        var out = css
        val imports = Regex("""@import[^;{]*(;|(?=\{))""", RegexOption.IGNORE_CASE)
        if (imports.containsMatchIn(out)) {
            removed.add("@import in stylesheet")
            out = imports.replace(out, "")
        }
        // Old-IE script vectors. Harmless everywhere current, removed anyway
        // because leaving a known-executable construct in place to argue about
        // browser share is not a security posture.
        for (bad in listOf("expression(", "-moz-binding", "javascript:", "vbscript:")) {
            if (out.contains(bad, ignoreCase = true)) {
                removed.add("$bad in stylesheet")
                out = Regex(Regex.escape(bad), RegexOption.IGNORE_CASE).replace(out, "none(")
            }
        }
        out =
            Regex("""url\(\s*(['"]?)([^)'"]*)\1\s*\)""", RegexOption.IGNORE_CASE).replace(out) { m ->
                val target = m.groupValues[2].trim()
                when {
                    target in allowedUrls -> {
                        m.value
                    }

                    // Data URIs are inert for images and the model may legitimately
                    // want a tiny inline texture, but they are also how you smuggle
                    // a payload past a URL allowlist. Not worth the argument.
                    target.startsWith("data:", ignoreCase = true) -> {
                        removed.add("data: url() in stylesheet")
                        "none"
                    }

                    else -> {
                        removed.add("url($target) in stylesheet")
                        "none"
                    }
                }
            }
        return out.trim()
    }

    /** The same rules, applied to `style=""` attributes, which Cleaner keeps verbatim. */
    private fun cleanInlineStyles(
        root: Element,
        removed: MutableList<String>,
    ) {
        for (el in root.select("[style]")) {
            val before = el.attr("style")
            val after = cleanCss(before, removed)
            if (after != before) el.attr("style", after)
        }
    }

    // ----------------------------------------------------------- allowlist ---

    private fun noteRemovals(
        doc: Document,
        removed: MutableList<String>,
    ) {
        // Recorded before Cleaner silently drops them, so an operator can tell
        // "the model tried something" apart from "the model wrote a plain page".
        for (tag in listOf("script", "iframe", "object", "embed", "form", "input", "button", "link", "meta")) {
            val n = doc.select(tag).size
            if (n > 0) removed.add("$n <$tag>")
        }
        val handlers = doc.allElements.sumOf { el -> el.attributes().count { it.key.startsWith("on", true) } }
        if (handlers > 0) removed.add("$handlers on* handler(s)")
    }

    /**
     * What survives: everything a newspaper is made of.
     *
     * SVG is deliberately absent. jsoup parses HTML, and SVG's camel-cased
     * attributes (`viewBox`, `preserveAspectRatio`) do not survive that round
     * trip reliably — an allowed-but-mangled `<svg>` renders as a blank box,
     * which is a worse outcome than not offering it. The system prompt says so.
     */
    private fun safelist(): Safelist =
        Safelist
            .relaxed()
            .addTags(
                "figure",
                "figcaption",
                "section",
                "article",
                "header",
                "footer",
                "main",
                "aside",
                "nav",
                "hr",
                "time",
                "mark",
                "abbr",
                "wbr",
                "s",
                "del",
                "ins",
                "details",
                "summary",
            ).addAttributes(":all", "class", "id", "style", "title", "lang", "dir")
            .addAttributes("img", "src", "alt", "width", "height", "loading", "referrerpolicy")
            .addAttributes("a", "href", "rel", "target")
            .addAttributes("time", "datetime")
            .addAttributes("abbr", "title")
            .addProtocols("img", "src", "https")
            .addProtocols("a", "href", "https", "http", "nostr", "mailto")
            .preserveRelativeLinks(false)

    // ------------------------------------------------------------- rebuild ---

    private fun rebuild(
        title: String,
        house: String,
        css: String,
        body: String,
    ): String =
        buildString {
            append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
            append("<meta charset=\"utf-8\">\n")
            append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
            // The backstop, not the protection. It holds on our preview and is
            // advisory once the edition is on the reader's own host.
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"")
            append("default-src 'none'; img-src https:; style-src 'unsafe-inline'; font-src data:; base-uri 'none'; form-action 'none'")
            append("\">\n")
            append("<meta name=\"referrer\" content=\"no-referrer\">\n")
            append("<title>").append(escape(title)).append("</title>\n")
            // House first, author second: the cascade is the override mechanism.
            if (house.isNotBlank()) append("<style>\n").append(house).append("\n</style>\n")
            if (css.isNotBlank()) append("<style>\n").append(css).append("\n</style>\n")
            append("</head>\n<body>\n").append(body).append("\n</body>\n</html>\n")
        }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
