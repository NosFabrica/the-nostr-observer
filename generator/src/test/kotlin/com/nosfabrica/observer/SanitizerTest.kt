package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Calendar
import com.nosfabrica.observer.nostr.Classifieds
import com.nosfabrica.observer.nostr.Streams
import com.nosfabrica.observer.safe.Sanitizer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The boundary, exercised with the page an injected corpus would produce.
 *
 * Every case here is something a model that took the bait might actually write.
 * The assertion is never "the model behaved" — it is that misbehaviour reaches
 * the reader as a hole in the page rather than as code.
 */
class SanitizerTest {
    private val sanitizer = Sanitizer(Fixtures.art())

    private fun clean(body: String) = sanitizer.sanitize("<!doctype html><html><head><title>T</title></head><body>$body</body></html>")

    @Test
    fun `strips script tags`() {
        val r = clean("""<p>hello</p><script>fetch('https://evil.example.com')</script>""")
        assertFalse(r.html.contains("script", ignoreCase = true), "no script survived")
        assertFalse(r.html.contains("evil.example.com"))
        assertTrue(r.removed.any { it.contains("<script>") })
    }

    @Test
    fun `strips event handlers`() {
        val r = clean("""<p onclick="steal()" onmouseover="x()">hello</p>""")
        assertFalse(r.html.contains("onclick"))
        assertFalse(r.html.contains("onmouseover"))
        assertTrue(r.removed.any { it.contains("on* handler") })
    }

    @Test
    fun `strips iframes forms and inputs`() {
        val r = clean("""<iframe src="https://evil.example.com"></iframe><form action="/x"><input name="key"></form>""")
        listOf("iframe", "<form", "<input").forEach { assertFalse(r.html.contains(it, ignoreCase = true), "$it survived") }
    }

    @Test
    fun `strips javascript hrefs`() {
        val r = clean("""<a href="javascript:alert(1)">alert me</a>""")
        assertFalse(r.html.contains("javascript:"))
    }

    @Test
    fun `unwraps open-web links to their own text`() {
        val r = clean("""<p>She linked <a href="https://evil.example.com/drain">her prize</a> today.</p>""")
        assertFalse(r.html.contains("<a "), "no anchor survived")
        assertTrue(r.html.contains("her prize"), "the words stay, the destination goes")
        assertTrue(r.removed.any { it.contains("unwrapped") })
    }

    @Test
    fun `keeps a permalink back to a source event`() {
        val id = "a".repeat(64)
        val r = clean("""<a href="https://njump.me/$id">source</a>""")
        assertTrue(r.html.contains("njump.me/$id"), "verification links are the one exception")
    }

    @Test
    fun `permalinks open in a new tab`() {
        val id = "a".repeat(64)
        val r = clean("""<a href="https://njump.me/$id">source</a>""")
        assertTrue(r.html.contains("target=\"_blank\""), "the paper stays put")
        assertTrue(r.html.contains("noopener"), "the new tab must not get window.opener")
    }

    @Test
    fun `keeps a verified stream watch link and encodes it`() {
        val stream = Fixtures.liveStream()
        val sanitizer =
            Sanitizer(
                Fixtures.art(),
                emptySet(),
                mapOf(stream.id.lowercase() to stream),
            )
        val writer = Streams.writerUrl(stream.id)
        val r =
            sanitizer.sanitize(
                """<!doctype html><html><head><title>T</title></head><body>
               <a href="$writer">NoGood Radio</a></body></html>""",
            )
        assertTrue(r.html.contains("zap.stream/naddr1"), "writer form is encoded to naddr")
        assertTrue(r.html.contains("NoGood Radio"), "the title stays linked")
        assertTrue(r.clean, r.removed.toString())
    }

    @Test
    fun `keeps a verified shopstr listing link and encodes it`() {
        val listing = Fixtures.classified()
        val sanitizer =
            Sanitizer(
                Fixtures.art(),
                emptySet(),
                emptyMap(),
                mapOf(listing.id.lowercase() to listing),
            )
        val writer = Classifieds.writerUrl(listing.id)
        val r =
            sanitizer.sanitize(
                """<!doctype html><html><head><title>T</title></head><body>
               <a href="$writer">4 Bars Rough Cut Tallow</a></body></html>""",
            )
        assertTrue(r.html.contains("shopstr.store/listing/naddr1"), "writer form is encoded to naddr")
        assertTrue(r.html.contains("4 Bars Rough Cut Tallow"), "the title stays linked")
        assertTrue(r.clean, r.removed.toString())
    }

    @Test
    fun `keeps a verified njump calendar link and encodes it`() {
        val listing = Fixtures.calendarEntry()
        val sanitizer =
            Sanitizer(
                Fixtures.art(),
                emptySet(),
                emptyMap(),
                emptyMap(),
                mapOf(listing.id.lowercase() to listing),
            )
        val writer = Calendar.writerUrl(listing.id)
        val r =
            sanitizer.sanitize(
                """<!doctype html><html><head><title>T</title></head><body>
               <a href="$writer">Bitcoin Meetup in Porto</a></body></html>""",
            )
        assertTrue(r.html.contains("njump.me/naddr1"), "writer form is encoded to naddr")
        assertFalse(r.html.contains("njump.me/${listing.id}"), "bare hex must not survive")
        assertTrue(r.html.contains("Bitcoin Meetup in Porto"), "the title stays linked")
        assertTrue(r.clean, r.removed.toString())
    }

    @Test
    fun `unwraps a zap stream url that names no stream we read`() {
        val writer = Streams.writerUrl("f".repeat(64))
        val r = clean("""<p><a href="$writer">fake stream</a></p>""")
        assertFalse(r.html.contains("<a "), "no anchor survived")
        assertTrue(r.html.contains("fake stream"))
    }

    @Test
    fun `unwraps a shopstr url that names no listing we read`() {
        val writer = Classifieds.writerUrl("f".repeat(64))
        val r = clean("""<p><a href="$writer">fake listing</a></p>""")
        assertFalse(r.html.contains("<a "), "no anchor survived")
        assertTrue(r.html.contains("fake listing"))
    }

    @Test
    fun `unwraps an njump calendar url that names no listing we read`() {
        // Empty corpusEventIds would keep any njump hex as a citation; a real
        // run always passes the corpus, so membership is what drops fakes.
        val writer = Calendar.writerUrl("f".repeat(64))
        val r =
            Sanitizer(Fixtures.art(), setOf("a".repeat(64))).sanitize(
                """<!doctype html><html><head><title>T</title></head><body>
               <p><a href="$writer">fake meetup</a></p></body></html>""",
            )
        assertFalse(r.html.contains("<a "), "no anchor survived")
        assertTrue(r.html.contains("fake meetup"))
    }

    @Test
    fun `removes at-import and remote url from the stylesheet`() {
        val r =
            sanitizer.sanitize(
                """<!doctype html><html><head><title>T</title>
               <style>@import url("https://evil.example.com/x.css");
               body{background:url(https://evil.example.com/beacon.png)}</style></head>
               <body><p>hi</p></body></html>""",
            )
        assertFalse(r.html.contains("@import"))
        assertFalse(r.html.contains("evil.example.com"))
        assertTrue(r.removed.any { it.contains("@import") })
    }

    @Test
    fun `keeps a url pointing at allowed art`() {
        val r =
            sanitizer.sanitize(
                """<!doctype html><html><head><title>T</title>
               <style>.hero{background:url(${Fixtures.ART_URL})}</style></head>
               <body><p>hi</p></body></html>""",
            )
        assertTrue(r.html.contains(Fixtures.ART_URL), "art url survived in CSS")
    }

    @Test
    fun `resolves an art id to the real url`() {
        val r = clean("""<figure><img src="art-1" alt=""><figcaption>Chillis</figcaption></figure>""")
        assertTrue(r.html.contains(Fixtures.ART_URL))
        assertTrue(r.html.contains("Chillis"))
        assertTrue(r.html.contains("referrerpolicy"))
    }

    @Test
    fun `drops the whole figure when the art id was invented`() {
        val r =
            clean(
                """<figure><img src="art-99"><figcaption>A photograph that does not exist</figcaption></figure>
               <p>keep me</p>""",
            )
        assertFalse(r.html.contains("does not exist"), "orphan caption removed with its figure")
        assertTrue(r.html.contains("keep me"))
        assertTrue(r.removed.any { it.contains("art-99") })
    }

    @Test
    fun `drops an image whose src is a url the model invented`() {
        val r = clean("""<p>x</p><img src="https://evil.example.com/tracker.gif">""")
        assertFalse(r.html.contains("evil.example.com"))
    }

    @Test
    fun `keeps the layout the model chose`() {
        val r =
            clean(
                """<div class="sheet"><header class="masthead"><h1>The Nostr Observer</h1></header>
               <section class="fold"><div class="col span-8"><h2 class="lead-head">A Headline</h2>
               <p class="first">Body text.</p></div><aside class="col span-4"><div class="box">rail</div></aside>
               </section></div>""",
            )
        listOf("sheet", "masthead", "fold", "span-8", "lead-head", "<aside", "A Headline").forEach {
            assertTrue(r.html.contains(it), "$it survived")
        }
        assertTrue(r.clean, "a well-behaved page removes nothing: ${r.removed}")
    }

    @Test
    fun `always emits a policy and a charset`() {
        val r = clean("<p>x</p>")
        assertTrue(r.html.contains("Content-Security-Policy"))
        assertTrue(r.html.contains("default-src 'none'"))
        assertTrue(r.html.startsWith("<!doctype html>"))
    }

    @Test
    fun `sanitises inline style attributes too`() {
        val r = clean("""<div style="background:url(https://evil.example.com/x.png);color:var(--ink)">x</div>""")
        assertFalse(r.html.contains("evil.example.com"))
        assertTrue(r.html.contains("var(--ink)"), "the harmless half of the declaration is kept")
    }

    @Test
    fun `title is escaped rather than trusted`() {
        val r = sanitizer.sanitize("""<!doctype html><html><head><title>x</title></head><body></body></html>""")
        assertEquals(1, Regex("<title>").findAll(r.html).count())
    }
}

/**
 * The stylesheet has to be IN the page.
 *
 * Found by the first real end-to-end run and not by any fixture. The writer is
 * handed the house stylesheet as reference and told most days need no `<style>`
 * of their own, so it wrote none and used `class="sheet"`, `class="masthead"`,
 * `class="folio"` — and the published file carried no CSS at all. Every class
 * resolved to nothing and the edition was a column of unstyled text.
 */
class HouseStyleTest {
    private fun clean(body: String) = Sanitizer(Fixtures.art()).sanitize("<html><body>$body</body></html>").html

    @Test
    fun `a page that brings no css of its own still gets the house one`() {
        val html = clean("""<div class="sheet"><header class="masthead"><h1>The Nostr Observer</h1></header></div>""")
        assertTrue(html.contains("<style>"), "no stylesheet at all")
        assertTrue(html.contains(".masthead"), "the house rules are not in the page")
    }

    @Test
    fun `the author's css comes after the house css, so it wins`() {
        val html = clean("""<style>.masthead { color: rebeccapurple }</style><div class="masthead">x</div>""")
        val house = html.indexOf(".sheet")
        val authors = html.indexOf("rebeccapurple")
        assertTrue(house in 0..<authors, "the author's block must come last to override by cascade")
    }

    @Test
    fun `the house css goes through the same url pass as anyone else's`() {
        // Ours, and checked anyway. An earlier version of this test asserted
        // that no `@import` came out — which was true only because none had
        // ever been put in, so it tested nothing. This one feeds a house sheet
        // that DOES call home and asserts it is stripped.
        val hostile = "@import url(https://evil.example.com/x.css);\n.sheet { background: url(https://evil.example.com/beacon.png) }"
        val result = Sanitizer(Fixtures.art(), emptySet(), houseCss = hostile).sanitize("<html><body><p>x</p></body></html>")
        assertFalse(result.html.contains("evil.example.com"), "the house sheet reaches every reader of a published edition")
        assertTrue(result.removed.any { it.contains("@import") }, result.removed.toString())
    }
}
