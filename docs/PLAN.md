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
- **`service/`, the Assistant's Observer task service**: a container on Brainstorm's servers
  that prints the Observer for readers who named their Assistant as the runner of their
  schedule, when assistant-runner wakes it for one of them.

## The authorities

These win over anything written here. Read them instead of a summary.

| Topic | Where |
|---|---|
| The site, `editions.json`, publishing and roll-off, the synced schedule, settings as params, the Assistant as runner, the server's rules | Quartz `quartz/plans/2026-11-11-observer-site.md` |
| The page checks (the security boundary) and the paper's CSP | Quartz `quartz/plans/2026-11-11-paper-boundary-v2.md` |
| The reader's AI key (kind 12000) | NIP-FA (`FA.md` on `nips` branch `claude/nip-fa-ai-agent-keys`) |
| The schedule (kind 34817), `param`, the runner `p` | NIP-FB (`FB.md` on `nips` branch `claude/nip-fb-scheduled-tasks`) |
| The engine's behaviour | Amethyst `commons/src/commonMain/kotlin/com/vitorpamplona/amethyst/commons/frontpage/` |
| The wake (`POST /v1/run`) | assistant-runner's `docs/PLAN.md` and `contract/v1/` (its vectors are copied into `service/src/test/resources/`) |
| The Assistant's key (`brainstorm_nsec`, `_plaintext_nsec`, the Fernet key file) | brainstorm_server's `app/repos/brainstorm_nsec.py` and `app/utils/encryption.py` |
| What one wake does, the deployment | `service/README.md` |
| Brainstorm's TypeScript copy, for cross-checking | Brainstorm-UI `client/src/lib/frontPage/` |

## Settled

- **The writer is the reader's own AI key** (NIP-FA), called in the OpenAI Chat Completions
  format at the key's own URL. There is no NosFabrica-wide model key. The Assistant prints
  with the Assistant's own NIP-FA list.
- **A fixed 24-hour window.** **The model writes the whole document**; the engine inserts the
  house stylesheet into it (Amethyst's `insertHouseCss`) before the page checks. **Only pages that pass the page checks** are shown or published. **Art is
  hotlinked**, never downloaded. **No NSFW classification**: the reader's lens is the
  moderator. The product is called **The Observer**.
- **The site**: one NIP-5A named nsite per person, kind 35128, `d` = `observer`, dated pages,
  `editions.json`, roll-off. Uploads go only to Blossom servers the publisher's owner listed
  (kind 10063).
- **The schedule**: NIP-FB kind 34817, `d` = `observer`. Options are `param` tags (`model`;
  `key` = the key's URL and its last 4 characters). A public `p` names a runner, and the
  private tags are then encrypted to it. Devices never print a schedule that names a runner.

## The Assistant's task service

brainstorm_server holds one Assistant key per Brainstorm user (the owner). The scheduler,
[assistant-runner](https://github.com/NosFabrica/assistant-runner), reads the owners'
schedules, finds a due occurrence and wakes this service with `POST /v1/run` for that owner. The
service never calls brainstorm_server: it reads the Assistant's key from the server's
`brainstorm_nsec` table (four columns, under a column grant) and Fernet key file, and keeps its
runs and unpublished papers in that Postgres's `observer` schema, its own. For the owner it:

1. answers the wake at once (`202`, a joined `202` for an owner already printing, `200` for an
   occurrence its `runs` row settles, `429` when busy, `400` when malformed), then runs:
2. reads the owner's newest kind 34817 `d=observer` by author (never by `#p`, which would miss
   the version that takes printing back), with more than half of the owner's write relays
   answering;
3. requires that version to name the owner's Assistant in exactly one `p`, with a valid
   signature;
4. decrypts it as the Assistant (only the owner's own schedule naming it, and the Assistant's
   12000 only beside it: the allowlist the server enforced) and decides with Quartz's runner
   rules: due, not paused, after the time it was set, params understood, no `runs` row;
5. reads both Observer sites (the owner's and the Assistant's) and skips an occurrence that
   is already printed on either;
6. picks the Assistant's NIP-FA key by the `key` param, the model by `model` and the writing
   effort by `effort` (`deep`, or Standard by its absence);
7. claims the occurrence in `observer.runs` before spending: only the inserter prints;
8. prints with the engine (the same plan, page and page checks as the apps), with Brainstorm's
   links and the edition's number, recent headlines, masthead and the schedule's clock;
9. stores the paper, then uploads to the **owner's** Blossom servers, each 24242 signed by the
   Assistant;
10. publishes the **Assistant's** Observer site, with `["p", owner]`, signed by the Assistant;
11. ends the run. A paper that didn't go out is published again for 12 hours, never printed
    again, while the schedule still names the Assistant.

**The service and the runner share no code.** Neither depends on the other's repository. They
share only what both talk to: the wake and its JSON (the contract's vectors, which each side tests
against), brainstorm_server's four `brainstorm_nsec` columns read with the server's
`_plaintext_nsec` rule, and the Fernet key file. The service has its own copy of the key reading
(`service/…/keys/`) and its own state (`service/…/state/`, the `observer` schema), each tested on
its own; the runner never reads the service's tables, and learns that an occurrence is settled
from the `200` the service answers.

Consent to a public site stays with Brainstorm: it asks the reader before it names the
Assistant in their schedule, and the service publishes whenever the schedule names it.

The AI key and the Assistant's nsec are plaintext only in the service's memory for the run, and
are never logged.

## What the engine still owes Amethyst

The port keeps Amethyst's file and class names so the two copies diff cleanly. What it
leaves out is the app: accounts, the local edition store, the per-device morning claim
(`FrontPageMorning`), the view model and the model picker. Those belong to clients.
