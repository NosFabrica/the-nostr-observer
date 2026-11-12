package com.nosfabrica.observer

import com.nosfabrica.observer.frontpage.FRONT_PAGE_WINDOW_SECONDS
import com.nosfabrica.observer.frontpage.FrontPagePress
import com.nosfabrica.observer.frontpage.PaperBoundary
import com.nosfabrica.observer.frontpage.ProbeVerdict
import com.nosfabrica.observer.frontpage.WriterException
import com.nosfabrica.observer.frontpage.WriterFailure
import com.nosfabrica.observer.frontpage.digest
import com.nosfabrica.observer.frontpage.fromNpub
import com.nosfabrica.observer.frontpage.isEventIdHex
import com.nosfabrica.observer.frontpage.paperBoundary
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.RelayUrlNormalizer
import com.vitorpamplona.quartz.nip01Core.relay.sockets.okhttp.BasicOkHttpWebSocket
import com.vitorpamplona.quartz.utils.TimeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlin.system.exitProcess

private const val DEFAULT_RELAY = "wss://search.brainstorm.world"

private val USAGE =
    """
    generator: a local dry run of the Observer's engine

      generator <npub-or-hex> --dry-run [--relay <url>] [--out <dir>]

    Probes the reader's ranking, pulls the last 24 hours through it and writes the digest the
    writer would read to <dir>/observer-<code>.digest.md (default: editions/). It never calls
    a model: a paper is printed by the reader's own clients, or by their Assistant (runner/).
    """.trimIndent()

fun main(args: Array<String>) {
    val flags = args.filter { it.startsWith("--") }.toSet()
    val positional = args.filterIndexed { i, a -> !a.startsWith("--") && (i == 0 || args[i - 1] !in setOf("--relay", "--out")) }
    if (positional.size != 1 || "--dry-run" !in flags) {
        println(USAGE)
        exitProcess(if ("--help" in flags || "-h" in args) 0 else 2)
    }

    fun value(name: String) = args.indexOf(name).takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }

    val who = positional.single()
    // An nsec must never reach a relay filter: refuse it before anything decodes it.
    if (who.startsWith("nsec1")) {
        System.err.println("That is a secret key. Give the npub.")
        exitProcess(2)
    }
    val observer = fromNpub(who) ?: who.lowercase().takeIf { isEventIdHex(it) }
    if (observer == null) {
        System.err.println("Not an npub or a hex pubkey: $who")
        exitProcess(2)
    }
    val relay = RelayUrlNormalizer.normalizeOrNull(value("--relay") ?: DEFAULT_RELAY)
    if (relay == null) {
        System.err.println("Not a relay URL.")
        exitProcess(2)
    }
    val out = File(value("--out") ?: "editions")

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val client = NostrClient(BasicOkHttpWebSocket.Builder { OkHttpClient() }, scope)
    val boundary: PaperBoundary = paperBoundary() ?: error("no page checks on this platform")
    val press =
        FrontPagePress(client, relay, boundary, { _, _ -> throw WriterException(WriterFailure.UNSUPPORTED) })
    try {
        runBlocking {
            val until = TimeUtils.now()
            if (press.probe(observer, until - FRONT_PAGE_WINDOW_SECONDS) != ProbeVerdict.READY) {
                println("The ranking isn't projected yet: the observed read is empty while the anonymous one isn't.")
                exitProcess(1)
            }
            val corpus = press.pull(observer, until)
            val text = digest(corpus)
            out.mkdirs()
            val file = File(out, "observer-${corpus.code}.digest.md")
            file.writeText(text)
            println("${corpus.events.size} posts, ${corpus.art.size} pictures, overlap ${corpus.overlap}/${corpus.control.size}")
            println("digest: ${file.path} (${text.length} characters)")
        }
    } finally {
        client.disconnect()
        scope.cancel()
    }
}
