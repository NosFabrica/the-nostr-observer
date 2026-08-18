package com.nosfabrica.observer.corpus

import com.nosfabrica.observer.nostr.Corpus
import com.nosfabrica.observer.nostr.Desk
import com.nosfabrica.observer.nostr.values
import com.vitorpamplona.quartz.nip01Core.core.Event

/**
 * A picture the edition may use, named by an id the generator can cite.
 *
 * The id is the whole point. If the generator picked art by writing URLs, an
 * invented URL would be indistinguishable from a real one and the sanitizer
 * would have nothing to check it against. Handing over ids and resolving them
 * afterwards makes a fabricated image reference structurally impossible rather
 * than merely unlikely.
 */
data class Art(
    val id: String,
    val url: String,
    val mime: String?,
    val width: Int?,
    val height: Int?,
    val alt: String?,
    val eventId: String,
    val pubkey: String,
    val byline: String,
    val caption: String,
) {
    val portrait: Boolean get() = width != null && height != null && height > width
}

/**
 * Builds the shortlist from `imeta` tags alone. NOTHING IS FETCHED.
 *
 * Art is hotlinked where its author published it: this is public Nostr data and
 * media servers exist to serve it to whatever client asks. That decision deletes
 * fetching, resizing, EXIF handling and the whole image library — and it makes
 * this a metadata pass over events we already have.
 *
 * Two traps the prototype paid for:
 *  - `imeta` carries VIDEO as often as stills. Filter on the declared MIME, not
 *    the URL suffix; plenty of `.mov` arrives with no extension at all and plenty
 *    of `.jpg` in a URL is a redirect to something else.
 *  - `alt` is worth keeping even though nothing displays it today. Hotlinked art
 *    rots on somebody else's server, and alt text is the difference between a
 *    missing image degrading to a caption and degrading to a gap.
 */
object ArtDesk {
    /** Kinds whose `imeta` describes a video, so an `image` beside it is a poster frame. */
    private val VIDEO_KINDS = (Desk.VIDEOS.kinds + Desk.SHORTS.kinds).toSet()

    private val URL = Regex("""https?://\S+""")
    private val WHITESPACE = Regex("""\s+""")
    private val IMAGE_EXT = Regex("""\.(jpe?g|png|gif|webp|avif|bmp)(\?|$)""", RegexOption.IGNORE_CASE)
    private val VIDEO_EXT = Regex("""\.(mp4|mov|webm|m4v|avi|mkv)(\?|$)""", RegexOption.IGNORE_CASE)

    fun shortlist(
        corpus: Corpus,
        max: Int = 40,
        perDeskFirst: Int = 4,
    ): List<Art> {
        // Two passes, and the second one is why video ever gets a picture.
        //
        // One pass in corpus order fills every slot from the desks that come
        // first: notes and picture posts exhausted all forty before the video
        // desks were reached, so a poster frame could not appear on the page no
        // matter how good it was. Measured 2026-08-18, that was zero of the six
        // posters available.
        //
        // So each desk gets a few slots up front, then rank order takes the
        // rest. Within a desk the order is still the provider's ranking, which
        // is the property worth keeping: the change is about which desks are
        // reachable, not about preferring worse art.
        val candidates = corpus.ranked.mapValues { (_, events) -> events.flatMap(::candidates) }
        val taken = LinkedHashSet<Candidate>()

        candidates.values.forEach { fromDesk -> taken += fromDesk.take(perDeskFirst) }
        candidates.values.flatten().forEach { taken += it }

        val seen = mutableSetOf<String>()
        val out = mutableListOf<Art>()
        for (candidate in taken) {
            if (!seen.add(candidate.url)) continue
            out.add(art(out.size + 1, candidate, corpus))
            if (out.size >= max) break
        }
        return out
    }

    /** One `imeta` tag, read once, so the two passes above agree about it. */
    private data class Candidate(
        val url: String,
        val mime: String?,
        val width: Int?,
        val height: Int?,
        val alt: String?,
        val event: Event,
    )

    private fun candidates(event: Event): List<Candidate> =
        event.values("imeta").mapNotNull { fields ->
            val kv =
                fields
                    .mapNotNull { field ->
                        val at = field.indexOf(' ')
                        if (at <= 0) null else field.substring(0, at) to field.substring(at + 1).trim()
                    }.toMap()

            // A newspaper prints a still from the film, not the film.
            //
            // A video's `imeta` names the VIDEO in `url` and, when the client
            // bothered, a poster frame in `image`. Measured 2026-08-18: about
            // one video in six carries one (6 of 37 on kind 34236, 1 of 6 on
            // 34235), so most video stories are text and that is fine. What must
            // never happen is the video url reaching an `<img src>`, which is
            // why the poster is taken from a different field rather than by
            // hoping the mime is wrong.
            // A poster is NOT sniffed. The field is called `image` and it sits
            // on a video, which is a declaration, not a hint -- and measured
            // 2026-08-18 every real one was extension-less
            // (`media.divine.video/7f4e79…`), so sniffing rejected six posters
            // out of seven. `m` describes the video and says nothing about the
            // poster, and one real kind-34235 carried a poster with no `m` at
            // all, which is why the event's KIND decides and not the mime.
            val poster =
                kv["image"]?.takeIf { event.kind in VIDEO_KINDS || isVideo(kv["m"]) }
            val url = poster ?: kv["url"] ?: return@mapNotNull null
            // https only, because the sanitizer allows no other scheme in an
            // `img src`. Art it would strip is a hole in the page, not a picture.
            if (!url.startsWith("https://", ignoreCase = true)) return@mapNotNull null
            val mime = if (poster != null) null else kv["m"]
            if (poster == null && !isImage(url, mime)) return@mapNotNull null
            // A poster's dimensions are the video's, and they describe the
            // frame, so they still say whether it stands up or lies down.
            val (w, h) = parseDim(kv["dim"])
            Candidate(url, mime, w, h, kv["alt"], event)
        }

    /** A declared video mime, which is what makes an `image` field a poster rather than decoration. */
    internal fun isVideo(mime: String?): Boolean = mime != null && mime.startsWith("video/", ignoreCase = true)

    /**
     * A declared MIME wins outright, in both directions. Only when nothing was
     * declared do we fall back to the URL, and then a known video extension is a
     * rejection rather than an unknown — the failure we are avoiding is putting a
     * `.mov` in an `<img>`, which renders as a broken box.
     */

    internal fun isImage(
        url: String,
        mime: String?,
    ): Boolean {
        if (mime != null && mime.isNotBlank()) return mime.startsWith("image/", ignoreCase = true)
        if (VIDEO_EXT.containsMatchIn(url)) return false
        return IMAGE_EXT.containsMatchIn(url)
    }

    internal fun parseDim(dim: String?): Pair<Int?, Int?> {
        val parts = dim?.split('x', 'X') ?: return null to null
        if (parts.size != 2) return null to null

        // Dimensions arrive as "1080.0x1920.0" about as often as "1080x1920".
        fun num(s: String) =
            s
                .trim()
                .substringBefore('.')
                .toIntOrNull()
                ?.takeIf { it > 0 }
        return num(parts[0]) to num(parts[1])
    }

    private fun art(
        n: Int,
        candidate: Candidate,
        corpus: Corpus,
    ): Art {
        val event = candidate.event
        // The caption the generator sees is what the poster actually wrote, with
        // the media URL stripped back out — clients paste the URL into the body
        // as well as the tag, and "look at this https://blossom…" is not a caption.
        val body =
            event.content
                .replace(URL, " ")
                .replace(WHITESPACE, " ")
                .trim()
                .take(280)
        return Art(
            id = "art-$n",
            url = candidate.url,
            mime = candidate.mime,
            width = candidate.width,
            height = candidate.height,
            alt = candidate.alt?.takeIf { it.isNotBlank() },
            eventId = event.id,
            pubkey = event.pubKey,
            byline = corpus.byline(event.pubKey),
            caption = body.ifBlank { candidate.alt.orEmpty() },
        )
    }
}
