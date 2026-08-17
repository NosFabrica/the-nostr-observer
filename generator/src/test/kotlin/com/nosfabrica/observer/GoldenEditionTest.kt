package com.nosfabrica.observer

import com.nosfabrica.observer.corpus.Art
import com.nosfabrica.observer.safe.Sanitizer
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A real broadsheet through the boundary.
 *
 * The unit tests answer "does the sanitizer stop the bad things". They cannot
 * answer the other half, which is the likelier way to ship a broken product:
 * DOES IT LEAVE A GOOD PAGE ALONE? An allowlist that quietly eats `<figure>` or
 * flattens a twelve-column grid would pass every adversarial test in this suite
 * and produce rubble every morning.
 *
 * So the fixture is the prototype front page — 56 KB of hand-written broadsheet
 * built from a real 24-hour window through a real lens, with seven figures, a
 * reverse-ink panel, market tables and a nine-section below-the-fold. If the
 * sanitizer damages that, it damages everything.
 */
class GoldenEditionTest {
    private val page: String =
        GoldenEditionTest::class.java
            .getResourceAsStream("/prototype-edition.html")!!
            .bufferedReader()
            .readText()

    /** The seven figures the fixture cites, as the shortlist would have supplied them. */
    private val art: List<Art> =
        (1..7).map { n ->
            Art(
                id = "art-$n",
                url = "https://blossom.example.com/$n.jpg",
                mime = "image/jpeg",
                width = 1200,
                height = 800,
                alt = "art $n",
                eventId = "e$n",
                pubkey = Fixtures.ALICE,
                byline = "Alice",
                caption = "caption $n",
            )
        }

    private val result = Sanitizer(art).sanitize(page)

    @Test
    fun `the whole broadsheet survives`() {
        val structure =
            listOf(
                "masthead",
                "dateline",
                "fold",
                "c-lead",
                "c-main",
                "c-rail",
                "lead-head",
                "story",
                "wire-item",
                "quote-box",
                "weather-row",
                "lens",
                "lens-grid",
                "band",
                "cols3",
                "cols4",
                "tablewrap",
                "colophon",
            )
        structure.forEach { assertTrue(result.html.contains(it), "class '$it' was eaten") }

        // No <aside> here: the prototype built its rail out of a grid column.
        // SanitizerTest covers that element on a page that actually uses it.
        listOf("<figure", "<figcaption", "<table", "<blockquote", "<section", "<header", "<footer")
            .forEach { assertTrue(result.html.contains(it), "element '$it' was eaten") }
    }

    @Test
    fun `the stylesheet survives with its custom properties and media queries`() {
        listOf("--paper", "--ink", "--accent", "prefers-color-scheme", "grid-template-columns", "clamp(")
            .forEach { assertTrue(result.html.contains(it), "CSS '$it' was eaten") }
    }

    @Test
    fun `the seven figures resolve to real urls`() {
        (1..7).forEach { n ->
            assertTrue(result.html.contains("https://blossom.example.com/$n.jpg"), "art-$n did not resolve")
        }
        assertFalse(result.html.contains("art-1\""), "no unresolved id left behind")
    }

    @Test
    fun `a well-behaved page loses nothing`() {
        // Every removal here would be a false positive on an honest edition,
        // which is the failure this whole test exists to catch.
        assertTrue(result.clean, "sanitizer removed things from a clean page: ${result.removed}")
    }

    @Test
    fun `the words are still there`() {
        listOf(
            "Two Blocks, Then Silence",
            "All the Notes Fit to Rank",
            "Gardens &amp; Provisions",
            "trosso19",
        ).forEach { assertTrue(result.html.contains(it), "text '$it' was lost") }
    }
}
