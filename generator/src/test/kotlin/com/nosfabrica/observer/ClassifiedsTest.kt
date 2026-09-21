package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.Classifieds
import com.nosfabrica.observer.nostr.Desk
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClassifiedsTest {
    private val listing = Fixtures.classified()
    private val corpus = Fixtures.corpus(listOf(listing), Desk.CLASSIFIEDS)

    @Test
    fun `writer url is event id hex`() {
        assertEquals(
            "https://shopstr.store/listing/${Fixtures.LISTING_ID}",
            Classifieds.writerUrl(Fixtures.LISTING_ID),
        )
    }

    @Test
    fun `canonical url round-trips through naddr`() {
        val url = Classifieds.canonicalUrl(listing)
        assertEquals(
            Fixtures.LISTING_ID,
            Classifieds.listingLinkTarget(url, listOf(listing)),
        )
    }

    @Test
    fun `writer form resolves only for a listing we read`() {
        val writer = Classifieds.writerUrl(Fixtures.LISTING_ID)
        assertEquals(Fixtures.LISTING_ID, Classifieds.listingLinkTarget(writer, listOf(listing)))
        assertNull(Classifieds.listingLinkTarget(writer, emptyList()))
    }

    @Test
    fun `a shopstr url copied from a post body is not verified`() {
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
        assertNull(Classifieds.listingLinkTarget(invented, listOf(listing)))
    }

    @Test
    fun `naddr encoding uses nip-19 field order`() {
        val naddr = NAddress.create(30402, Fixtures.ALICE, Fixtures.LISTING_D, emptyList())
        val parsed = NAddress.parse(naddr)!!
        assertEquals(Fixtures.LISTING_D, parsed.dTag)
        assertEquals(Fixtures.ALICE, parsed.author)
        assertEquals(30402, parsed.kind)
    }

    @Test
    fun `listed classifieds need a d tag`() {
        val bare =
            Fixtures.event(
                "aa55".repeat(16),
                Fixtures.ALICE,
                "",
                kind = 30402,
                tags = listOf(listOf("title", "No address")),
            )
        assertTrue(Classifieds.listed(Fixtures.corpus(listOf(bare), Desk.CLASSIFIEDS)).isEmpty())
    }
}
