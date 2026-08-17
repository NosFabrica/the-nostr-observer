package com.nosfabrica.observer

import com.nosfabrica.observer.corpus.ArtDesk
import com.nosfabrica.observer.corpus.Digest
import com.nosfabrica.observer.nostr.Bech32
import com.nosfabrica.observer.nostr.Pull
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

private val USAGE =
    """
    observer-press — print one edition

      observer-press <npub-or-hex> [options]

      --relay <url>    relay to read from (default $DEFAULT_RELAY)
      --out <file>     where to write the edition (default edition.html)
      --until <epoch>  end of the 24h window (default: now)
      --effort <lvl>   low | medium | high | xhigh | max (default high)
      --dry-run        pull, prune and shortlist, but do not call the model

    Reads ANTHROPIC_API_KEY from the environment.
    """.trimIndent()

fun main(args: Array<String>) =
    runBlocking {
        if (args.isEmpty() || args[0] in setOf("-h", "--help")) {
            println(USAGE)
            return@runBlocking
        }

        val flags =
            args
                .drop(1)
                .chunked(2)
                .filter { it.size == 2 }
                .associate { it[0] to it[1] }
        val dryRun = args.contains("--dry-run")
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
            val corpus = Pull(relay).corpus(observer, since, until)
            val voices =
                corpus
                    .all()
                    .map { it.pubkey }
                    .distinct()
                    .size
            step("Pulled ${corpus.all().size} events from $voices people, ${corpus.profiles.size} profiles")

            // The failure this catches is silent and it is the reason the project
            // exists: an unresolvable observer degrades to an anonymous read, which
            // on a measured window was 209 of 400 posts from one spam account.
            if (corpus.notes.isEmpty()) {
                System.err.println(
                    "\nNo ranked notes came back. Either this lens does not resolve on this relay,\n" +
                        "or the window is genuinely empty. Check the reader's kind 10040 and whether\n" +
                        "their provider's kind 30382 cards have reached this store.",
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
            step("Writing the page…")
            val draft = Writer(effort = effort).write(corpus, digest, art, Continuity())
            step(
                "Model returned ${draft.html.length} chars — ${draft.inputTokens} in, ${draft.outputTokens} out, $${"%.2f".format(
                    draft.costUsd(),
                )}",
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

            // A page that fails the check is not an edition. Exiting non-zero is how
            // the caller — a person now, a publish button later — is stopped from
            // treating it as one.
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

// ASCII on purpose: the JVM's default console encoding is not UTF-8 everywhere,
// and a status line that renders as "? Reading" reads like an error.
private fun step(msg: String) = println("- $msg")
