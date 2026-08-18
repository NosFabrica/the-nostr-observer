package com.nosfabrica.observer.press

import com.nosfabrica.observer.Press
import com.nosfabrica.observer.nostr.Relays
import com.nosfabrica.observer.press.publish.Announce
import com.nosfabrica.observer.press.store.Continuities
import com.nosfabrica.observer.press.store.Db
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Whatever the browser said, checked before it becomes a zone.
 *
 * `ZoneId.of` throws on anything it does not recognise, and this string arrives
 * in a request body and is used inside a launched coroutine — so an
 * unrecognised one would fail somebody's edition with a message nobody could
 * connect back to a timezone. UTC is the wrong time for most readers and the
 * right answer when we have not been told.
 */
class ZoneOfTest {
    private fun editions(dir: Path): Editions {
        val db = Db(dir.resolve("z.db").toString())
        val relays = Relays()
        val press = Press(relays, "wss://unreachable.invalid")
        return Editions(
            press,
            Runs(),
            Announce(relays, "wss://unreachable.invalid", press),
            Continuities(db),
            CoroutineScope(SupervisorJob()),
        )
    }

    @Test
    fun `a real zone is taken`(
        @TempDir dir: Path,
    ) {
        assertEquals(ZoneId.of("America/New_York"), editions(dir).zoneOf("America/New_York"))
    }

    @Test
    fun `nonsense falls back to UTC instead of failing an edition`(
        @TempDir dir: Path,
    ) {
        val editions = editions(dir)
        assertEquals(ZoneOffset.UTC, editions.zoneOf("Mars/Olympus_Mons"))
        assertEquals(ZoneOffset.UTC, editions.zoneOf(""))
        assertEquals(ZoneOffset.UTC, editions.zoneOf(null))
        // A fixed offset is a valid ZoneId but not in the tz database, and the
        // browser never sends one. Refusing it costs nothing and keeps the
        // accepted set to names whose DST rules we can actually follow.
        assertEquals(ZoneOffset.UTC, editions.zoneOf("+05:30"))
    }
}
