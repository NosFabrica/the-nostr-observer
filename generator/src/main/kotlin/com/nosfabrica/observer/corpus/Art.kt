package com.nosfabrica.observer.corpus

import com.nosfabrica.observer.nostr.Corpus
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
    private val URL = Regex("""https?://\S+""")
    private val WHITESPACE = Regex("""\s+""")
    private val IMAGE_EXT = Regex("""\.(jpe?g|png|gif|webp|avif|bmp)(\?|$)""", RegexOption.IGNORE_CASE)
    private val VIDEO_EXT = Regex("""\.(mp4|mov|webm|m4v|avi|mkv)(\?|$)""", RegexOption.IGNORE_CASE)

    fun shortlist(
        corpus: Corpus,
        max: Int = 40,
    ): List<Art> {
        val out = mutableListOf<Art>()
        val seen = mutableSetOf<String>()
        // Ranked order is the provider's order, so taking the first N is taking
        // the art from the posts this reader's own lens rated highest.
        for (event in corpus.all()) {
            for (fields in event.values("imeta")) {
                val kv =
                    fields
                        .mapNotNull { field ->
                            val at = field.indexOf(' ')
                            if (at <= 0) null else field.substring(0, at) to field.substring(at + 1).trim()
                        }.toMap()
                val url = kv["url"] ?: continue
                if (!seen.add(url)) continue
                val mime = kv["m"]
                if (!isImage(url, mime)) continue
                val (w, h) = parseDim(kv["dim"])
                out.add(art(out.size + 1, url, mime, w, h, kv["alt"], event, corpus))
                if (out.size >= max) return out
            }
        }
        return out
    }

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
        url: String,
        mime: String?,
        w: Int?,
        h: Int?,
        alt: String?,
        event: Event,
        corpus: Corpus,
    ): Art {
        val byline = corpus.byline(event.pubKey)
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
            url = url,
            mime = mime,
            width = w,
            height = h,
            alt = alt?.takeIf { it.isNotBlank() },
            eventId = event.id,
            pubkey = event.pubKey,
            byline = byline,
            caption = body.ifBlank { alt.orEmpty() },
        )
    }
}
