# AGENTS.md

Nothing is built yet. **[`docs/PLAN.md`](docs/PLAN.md) is the design** — read it
before writing code; this file holds only what the plan does not: the decisions
that are settled, the ones that are not, and the conventions to hold to.

## What this is

A service that reads a signed-in Nostr user's web-of-trust view of the last 24
hours and generates a newspaper front page from it, which they then publish to
their own Blossom servers as an nsite.

Sibling project to **[vespa-relay](https://github.com/NosFabrica/vespa-relay)**,
which is the search relay this reads from. That repo's `AGENTS.md` is worth
reading — the commenting conventions, the JitPack pinning trap, and the
"instrument before you theorize" habit all apply here.

## Build

    ./gradlew build                 # compile + test + spotless
    ./gradlew spotlessApply         # run BEFORE committing; formatting alone fails the build
    ./gradlew :generator:installDist
    generator/build/install/generator/bin/generator <npub> --check
    generator/build/install/generator/bin/generator <npub> --dry-run

`--check` reports the readiness chain and stops. `--dry-run` does everything
except call the model and writes the digest instead of a page. Neither needs an
API key. A full run reads `ANTHROPIC_API_KEY` from the environment.

## Settled — do not relitigate without a reason

- **Window is 24 hours, fixed.** Not "since last login."
- **No prompt caching**, and no shared wire/personal split. Every edition is
  generated standalone from one feed.
- **The model writes the whole document**, markup and optionally CSS. It is not
  filling a schema. Safety is enforced *after* generation by the sanitizer, not
  before it by constraining the writer.
- **No NSFW classification.** The trust provider is the moderator. We honor the
  lens including the parts we would not have chosen.
- **Art is hotlinked**, never fetched, resized, re-hosted or inlined. There is no
  image library in this project.
- **Login required to generate**; no login to read a published edition.
- **The system prompt is fixed, hidden, and never reaches the client.**
- **The paper prints addresses; it does not make them clickable.** No `<a href>`
  to the open web survives — only permalinks back to a source event. An earlier
  version allowlisted any URL that appeared in the corpus; a test caught that
  the corpus is where the attacker writes, so posting a phishing URL was enough
  to allowlist it. Presence in the corpus is evidence of nothing.

## Measured facts about the relays (2026-08-17 — re-measure, do not trust)

- **`search-staging` holds no kind 3 at all**, and `/stats.json` confirms it is
  not a mirrored kind. Follow lists must come from the reader's own write relays,
  discovered from their kind 10002 — which *is* mirrored. The outbox model
  working, not a workaround.
- **NIP-45 COUNT answers** on both `search-staging` and `scores.brainstorm.world`.
  It is still optional, and a null count is a supported answer that must draw
  nothing rather than estimate.
- **`search-staging` sends an AUTH challenge before answering a COUNT**, even
  though `auth_required` is false. Anything resolving on the first non-EVENT
  frame reads the challenge as the answer.
- **A NIP-50 search with no `since` times out** on this store; the same search
  with a 24-hour `since` answers immediately.
- **Neither `nip85.nosfabrica.com` nor `scores.brainstorm.world` exposes an HTTP
  API.** Both answer NIP-11 as plain strfry relays, so minting a lens is an
  operator step, not a call.

## Not settled

The open questions live at the end of `docs/PLAN.md`. The one that gates the
timeline is whether `nip85.nosfabrica.com` can onboard new observers on demand.

## Conventions

Mirror vespa-relay: Kotlin, Gradle version catalog, spotless + ktlint, git hooks
that run `spotlessCheck` pre-commit and tests pre-push. Run `spotlessApply`
before committing or the hook will reject you on formatting alone.

Comments explain *why*, and especially why-not — a comment that restates the code
is noise, a comment recording the thing that cost a day is the point. Stacked
KDoc fails ktlint.

### Numbers in this repo

Every measurement in the plan is a reading taken against a live, moving system on
a stated date. Treat them as evidence, not constants. Re-measure before relying
on one, and when you do, write the new date next to it.

### The relay is shared

`search-staging.brainstorm.world` is a real relay other people read. Read from it;
do not publish test events to it and do not hammer it. This service needs its own
Vespa deployment before it serves anyone.

## Prior art

The reference implementation is a prototype front page built by hand against the
live relay — 773 events across nine kinds, 244 profiles, ranked through one
observer, plus an anonymous control run that turned out to be 52% spam from a
single account. That contrast is the product thesis; keep it on the page.
