# The Observer — design

*Rewritten 12 November 2026. The first design (a web service with sign-in, NosFabrica's own
model key and a bunker countersign) is in this file's git history up to `0901b92`; nothing of
it is current.*

A daily front page from the last 24 hours of a person's Nostr, read through their own web of
trust, written by an AI with **their own key**, and published as **one public nsite per
person**. The reader's own clients print it (Amethyst on Android and desktop, Brainstorm on
the web). A hosted Brainstorm Assistant can print it for them when no device of theirs is on.

This repository holds two things:

- **`generator/`, the engine**: a JVM port of Amethyst's `commons/frontpage` (desks, corpus,
  digest, editorial brief, house stylesheet, writer, page checks, publisher). Amethyst's copy
  is the reference; this one follows it. `tools/diff-amethyst.sh` shows where they differ.
- **`runner/`, the Assistant's print worker**: a container on Brainstorm's servers that
  prints the Observer for readers who named their Assistant as the runner of their schedule.

## The authorities

These win over anything written here. Read them instead of a summary.

| Topic | Where |
|---|---|
| The site, `editions.json`, publishing and roll-off, the synced schedule, settings as params, the Assistant as runner, the server's rules | Quartz `quartz/plans/2026-11-11-observer-site.md` |
| The page checks (the security boundary) and the paper's CSP | Quartz `quartz/plans/2026-11-11-paper-boundary-v2.md` |
| The reader's AI key (kind 12000) | NIP-FA (`FA.md` on `nips` branch `claude/nip-fa-ai-agent-keys`) |
| The schedule (kind 34817), `param`, the runner `p` | NIP-FB (`FB.md` on `nips` branch `claude/nip-fb-scheduled-tasks`) |
| The engine's behaviour | Amethyst `commons/src/commonMain/kotlin/com/vitorpamplona/amethyst/commons/frontpage/` |
| The worker's HTTP contract with brainstorm_server | `runner/README.md` (section "The contract") |
| Brainstorm's TypeScript copy, for cross-checking | Brainstorm-UI `client/src/lib/frontPage/` |

## Settled

- **The writer is the reader's own AI key** (NIP-FA), called in the OpenAI Chat Completions
  format at the key's own URL. There is no NosFabrica-wide model key. The Assistant prints
  with the Assistant's own NIP-FA list.
- **A fixed 24-hour window.** **The model writes the whole document**, inlining the house
  stylesheet. **Only pages that pass the page checks** are shown or published. **Art is
  hotlinked**, never downloaded. **No NSFW classification**: the reader's lens is the
  moderator. The product is called **The Observer**.
- **The site**: one NIP-5A named nsite per person, kind 35128, `d` = `observer`, dated pages,
  `editions.json`, roll-off. Uploads go only to Blossom servers the publisher's owner listed
  (kind 10063).
- **The schedule**: NIP-FB kind 34817, `d` = `observer`. Options are `param` tags (`model`;
  `key` = the key's URL and its last 4 characters). A public `p` names a runner, and the
  private tags are then encrypted to it. Devices never print a schedule that names a runner.

## The Assistant's print worker

brainstorm_server holds one Assistant key per Brainstorm user (the owner). The worker holds
no nsec: it decrypts and signs as an owner's Assistant through brainstorm_server's
`/internal/observerRunner/*` endpoints. Every few minutes it:

1. pages the (owner, Assistant) list from the server;
2. reads kind 34817 `d=observer` naming those Assistants in `p`;
3. keeps only schedules whose author is the owner mapped to that Assistant, with exactly one
   `p` and a valid signature, and drops the rest;
4. decrypts each through the server and decides with Quartz's runner rules: due, not paused,
   after the time it was set, params understood;
5. reads both Observer sites (the owner's and the Assistant's) and skips an occurrence that
   is already printed on either;
6. picks the Assistant's NIP-FA key by the `key` param and the model by `model`;
7. prints with the engine (the same press and page checks as the apps);
8. uploads to the **owner's** Blossom servers, with each 24242 signed through the server;
9. publishes the **Assistant's** Observer site, with `["p", owner]`, signed through the server.

The AI key is plaintext only in the worker's memory for the run, and is never logged.

## What the engine still owes Amethyst

The port keeps Amethyst's file and class names so the two copies diff cleanly. What it
leaves out is the app: accounts, the local edition store, the per-device morning claim
(`FrontPageMorning`), the view model and the model picker. Those belong to clients.
