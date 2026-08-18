package com.nosfabrica.observer

import com.nosfabrica.observer.nostr.LensRequest
import com.nosfabrica.observer.nostr.Readiness
import com.nosfabrica.observer.nostr.Relays
import com.vitorpamplona.quartz.nip19Bech32.decodePublicKeyAsHexOrNull
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

private const val DEFAULT_RELAY = "wss://search-staging.brainstorm.world"

/** Flags that take no value. Everything else consumes the next argument. */
private val BOOLEAN_FLAGS = setOf("--dry-run", "--check")

private val USAGE =
    """
    observer-press - print one edition

      observer-press <npub-or-hex> [options]

      --relay <url>    relay to read from (default $DEFAULT_RELAY)
      --out <file>     where to write the edition (default edition.html)
      --until <epoch>  end of the 24h window (default: now)
      --effort <lvl>   low | medium | high | xhigh | max (default high)
      --dry-run        pull, prune and shortlist, but do not call the model
      --check          report the readiness chain and stop

    Reads ANTHROPIC_API_KEY from the environment.
    """.trimIndent()

fun main(args: Array<String>) =
    runBlocking {
        if (args.isEmpty() || args[0] in setOf("-h", "--help")) {
            println(USAGE)
            return@runBlocking
        }

        // Parsed by walking, not by chunking pairs. `chunked(2)` reads
        // `--dry-run --out x` as ("--dry-run" -> "--out") and silently loses the
        // filename, which is exactly what it did on the first live run.
        val flags = mutableMapOf<String, String>()
        var i = 1
        while (i < args.size) {
            val arg = args[i]
            when {
                !arg.startsWith("--") -> {
                    Unit
                }

                arg in BOOLEAN_FLAGS -> {
                    flags[arg] = "true"
                }

                i + 1 < args.size -> {
                    flags[arg] = args[i + 1]
                    i++
                }

                else -> {
                    flags[arg] = "true"
                }
            }
            i++
        }
        val relayUrl = flags["--relay"] ?: DEFAULT_RELAY
        val out = File(flags["--out"] ?: "edition.html")
        val until = flags["--until"]?.toLongOrNull() ?: Instant.now().epochSecond

        // quartz owns NIP-19 decoding, but NOT this guard, and the difference
        // matters. `decodePublicKeyAsHexOrNull` decodes ANY 32-byte bech32
        // payload: measured, it turns a valid nsec into the hex of the SECRET
        // key rather than returning null. Without this check a reader who
        // pastes their nsec into the front door would have their private key
        // put into a relay filter and sent over the wire.
        if (args[0].startsWith("nsec1", ignoreCase = true)) {
            System.err.println("That is a SECRET key. Paste your npub or hex public key instead.")
            exitProcess(2)
        }
        val observer =
            decodePublicKeyAsHexOrNull(args[0])
                ?: run {
                    System.err.println("Not a usable npub or hex pubkey: ${args[0].take(24)}")
                    exitProcess(2)
                }

        Relays().use { relays ->
            val press = Press(relays, relayUrl, effort(flags["--effort"]))

            if (flags.containsKey("--check")) {
                val (_, verdict) = press.readiness(observer, until - WINDOW_SECONDS)
                step("Reading $relayUrl through $observer")
                report(verdict)
                return@use
            }

            try {
                if (flags.containsKey("--dry-run")) {
                    val (_, _, digest) = press.gather(observer, until, ::show)
                    out.writeText(digest.text)
                    step("Dry run - digest written to ${out.path}")
                    return@use
                }

                val edition = press.edition(observer, until, onStep = ::show)
                out.writeText(edition.html)
                step("Wrote ${out.path} (${edition.html.length} bytes)")

                // A page that fails the check is not an edition. Exiting non-zero
                // is how the caller - a person now, a publish button later - is
                // stopped from treating it as one.
                if (!edition.publishable) {
                    System.err.println("\nThis edition would NOT be offered for publication.")
                    exitProcess(4)
                }
            } catch (refused: Press.Refused) {
                // NO_LENS is already on screen: `report` printed the whole chain
                // and the sentence explaining the first unmet link. Repeating the
                // message here said the same thing twice in different words.
                if (refused.reason != Press.Refused.Reason.NO_LENS) {
                    System.err.println("\n${refused.message}")
                }
                exitProcess(3)
            }
        }
    }

private fun show(progress: Press.Step) {
    when (progress) {
        is Press.Step.Reading -> {
            step("Reading ${progress.relay} through ${progress.observer}")
        }

        is Press.Step.Lensed -> {
            report(progress.verdict)
        }

        is Press.Step.Pulled -> {
            step("Pulled ${progress.events} events from ${progress.voices} people, ${progress.profiles} profiles")
            step("Control: ${progress.control} anonymous notes, ${progress.overlap} of them also in the paper")
        }

        is Press.Step.Digested -> {
            step("Digest: ${progress.kept} kept, ${progress.dropped} pruned, ~${progress.approxTokens} tokens")
            step("Art: ${progress.art} candidates, hotlinked (nothing fetched)")
        }

        Press.Step.Writing -> {
            step("Writing the page...")
        }

        is Press.Step.Written -> {
            step(
                "Model returned ${progress.chars} chars - ${progress.inputTokens} in, " +
                    "${progress.outputTokens} out, $${"%.2f".format(progress.costUsd)}",
            )
        }

        is Press.Step.Cleaned -> {
            if (progress.removed.isEmpty()) {
                step("Sanitizer: clean")
            } else {
                step("Sanitizer removed ${progress.removed.size} thing(s):")
                progress.removed.forEach { println("    - $it") }
            }
        }

        is Press.Step.Checked -> {
            step("Validator: ${progress.report.summary()}")
            progress.report.violations.forEach { println("    ! ${it.kind}: ${it.detail} -- \"${it.excerpt}\"") }
        }
    }
}

private fun report(verdict: Readiness.Verdict) {
    step("Lens: ${verdict.state} - ${Readiness.explain(verdict)}")
    verdict.chain.forEach { link ->
        println("    ${symbol(link.status)} ${link.key}${link.detail?.let { " ($it)" } ?: ""}")
    }
    if (!verdict.ranks) {
        println()
        println("  No usable lens, so there is no ranked paper to print.")
        println("  " + LensRequest.EXPLANATION)
        println()
    }
}

private fun effort(name: String?) =
    when (name?.lowercase()) {
        "low" -> com.anthropic.models.messages.OutputConfig.Effort.LOW
        "medium" -> com.anthropic.models.messages.OutputConfig.Effort.MEDIUM
        "xhigh" -> com.anthropic.models.messages.OutputConfig.Effort.XHIGH
        "max" -> com.anthropic.models.messages.OutputConfig.Effort.MAX
        else -> com.anthropic.models.messages.OutputConfig.Effort.HIGH
    }

private fun symbol(status: Readiness.Status) =
    when (status) {
        Readiness.Status.OK -> "[ok]     "
        Readiness.Status.PARTIAL -> "[partial]"
        Readiness.Status.BROKEN -> "[BROKEN] "
        Readiness.Status.WAITING -> "[waiting]"
        Readiness.Status.ASIDE -> "[aside]  "
    }

// ASCII on purpose: the JVM's default console encoding is not UTF-8 everywhere,
// and a status line that renders as "? Reading" reads like an error.
private fun step(msg: String) = println("- $msg")
