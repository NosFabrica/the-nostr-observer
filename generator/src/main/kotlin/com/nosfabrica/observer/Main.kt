package com.nosfabrica.observer

import com.nosfabrica.observer.corpus.ArtDesk
import com.nosfabrica.observer.corpus.Digest
import com.nosfabrica.observer.nostr.Bech32
import com.nosfabrica.observer.nostr.Follows
import com.nosfabrica.observer.nostr.Lens
import com.nosfabrica.observer.nostr.LensRequest
import com.nosfabrica.observer.nostr.Pull
import com.nosfabrica.observer.nostr.Readiness
import com.nosfabrica.observer.nostr.ReadinessProbe
import com.nosfabrica.observer.nostr.RelayClient
import com.nosfabrica.observer.safe.Sanitizer
import com.nosfabrica.observer.safe.Validator
import com.nosfabrica.observer.write.Continuity
import com.nosfabrica.observer.write.Writer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant
import kotlin.system.exitProcess

private const val DEFAULT_RELAY = "wss://search-staging.brainstorm.world"
private const val WINDOW_SECONDS = 24L * 60 * 60

/** Flags that take no value. Everything else consumes the next argument. */
private val BOOLEAN_FLAGS = setOf("--dry-run", "--check", "--provisional")

private val USAGE =
    """
    observer-press — print one edition

      observer-press <npub-or-hex> [options]

      --relay <url>    relay to read from (default $DEFAULT_RELAY)
      --out <file>     where to write the edition (default edition.html)
      --until <epoch>  end of the 24h window (default: now)
      --effort <lvl>   low | medium | high | xhigh | max (default high)
      --dry-run        pull, prune and shortlist, but do not call the model
      --check          report the readiness chain and stop
      --provisional    skip the lens and build from the follow list instead

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
        val dryRun = flags.containsKey("--dry-run")
        val relayUrl = flags["--relay"] ?: DEFAULT_RELAY
        val out = File(flags["--out"] ?: "edition.html")
        val until = flags["--until"]?.toLongOrNull() ?: Instant.now().epochSecond
        val since = until - WINDOW_SECONDS

        val observer =
            try {
                Bech32.toHexPubkey(args[0])
            } catch (e: IllegalArgumentException) {
                System.err.println("Not a usable pubkey: ${e.message}")
                exitProcess(2)
            }

        RelayClient(relayUrl).use { relay ->
            step("Reading $relayUrl through $observer")

            // Pre-flight before anything expensive. The failure it catches is
            // silent by design: an unresolvable observer degrades to an anonymous
            // read, which on a measured window was 209 of 400 posts from one spam
            // account. Finding that out after the model call is finding it late.
            val facts = ReadinessProbe(relay).gather(observer, since)
            val verdict = Readiness.assess(facts)
            step("Lens: ${verdict.state} - ${Readiness.explain(verdict)}")
            verdict.chain.forEach { link ->
                println("    ${symbol(link.status)} ${link.key}${link.detail?.let { " ($it)" } ?: ""}")
            }
            if (flags.containsKey("--check")) return@use

            val lens =
                if (verdict.ranks && !flags.containsKey("--provisional")) {
                    Lens.Trusted(observer)
                } else {
                    if (!verdict.ranks) {
                        println()
                        println("  No usable lens, so there is no ranked paper to print.")
                        println("  " + LensRequest.Manual.EXPLANATION)
                        println()
                    }
                    step("Building a provisional lens from the follow list")
                    Follows(relay).provisional(observer, facts.writeRelays.orEmpty()).also {
                        step(
                            "Provisional: ${it.direct} follows, ${it.extended} vouched-for strangers" +
                                (if (it.truncated) ", capped at ${it.authors.size} authors" else ""),
                        )
                    }
                }

            val corpus = Pull(relay).corpus(lens, observer, since, until)
            val voices =
                corpus
                    .all()
                    .map { it.pubkey }
                    .distinct()
                    .size
            step("Pulled ${corpus.all().size} events from $voices people, ${corpus.profiles.size} profiles")

            if (corpus.notes.isEmpty()) {
                System.err.println(
                    "\nNothing came back for this window through ${lens.label}. A quiet day is a real\n" +
                        "answer and a thin paper is the right response to it -- but with zero notes\n" +
                        "there is no paper to print at all.",
                )
                exitProcess(3)
            }

            val art = ArtDesk.shortlist(corpus)
            val digest = Digest().render(corpus, art)
            step("Digest: ${digest.kept} kept, ${digest.dropped} pruned, ~${digest.approxTokens} tokens")
            step("Art: ${art.size} candidates, hotlinked (nothing fetched)")

            if (dryRun) {
                out.writeText(digest.text)
                step("Dry run - digest written to ${out.path}")
                return@use
            }

            val effort = effort(flags["--effort"])
            step("Writing the page...")
            val draft = Writer(effort = effort).write(corpus, digest, art, Continuity())
            step(
                "Model returned ${draft.html.length} chars - ${draft.inputTokens} in, " +
                    "${draft.outputTokens} out, $${"%.2f".format(draft.costUsd())}",
            )

            val sanitized = Sanitizer(art).sanitize(draft.html)
            if (sanitized.removed.isEmpty()) {
                step("Sanitizer: clean")
            } else {
                step("Sanitizer removed ${sanitized.removed.size} thing(s):")
                sanitized.removed.forEach { println("    - $it") }
            }

            val report = Validator(corpus, art).validate(sanitized.html)
            step("Validator: ${report.summary()}")
            report.violations.forEach { println("    ! ${it.kind}: ${it.detail} -- \"${it.excerpt}\"") }

            out.writeText(sanitized.html)
            step("Wrote ${out.path} (${sanitized.html.length} bytes)")

            // A page that fails the check is not an edition. Exiting non-zero is
            // how the caller - a person now, a publish button later - is stopped
            // from treating it as one.
            if (!report.ok) {
                System.err.println("\nThis edition would NOT be offered for publication.")
                exitProcess(4)
            }
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
