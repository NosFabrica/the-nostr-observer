# AGENTS.md

**[`docs/PLAN.md`](docs/PLAN.md) is the design**, and it is short because it points at the
authorities: Quartz's `quartz/plans/2026-11-11-observer-site.md` (the site, the schedule, the
Assistant as runner), `quartz/plans/2026-11-11-paper-boundary-v2.md` (the page checks), NIP-FA
(the AI key) and NIP-FB (the schedule). When this repo disagrees with them, they win: fix the
repo.

## What this is

- `generator/`: the Observer's engine, a library. A JVM port of Amethyst's
  `commons/src/commonMain/kotlin/com/vitorpamplona/amethyst/commons/frontpage/` (plus its
  `jvmAndroid` actuals). It has a `main` for a manual, local, dry-run print.
- `runner/`: the Brainstorm Assistant's print worker, a container on Brainstorm's servers.
  It holds no nsec and signs and decrypts through brainstorm_server's
  `/internal/observerRunner/*`. `runner/README.md` has the contract and the deployment.

- `.claude/skills/`: the two Claude Code skills, still in use. `nostr-observer` prints a
  paper with Claude Code as the writer and publishes it as a claude.ai artifact;
  `observer-pages` deploys chosen editions to Vercel. Node, tested in CI with
  `node --test ".claude/skills/*/test/*.test.mjs"` and a fake relay (no network). Their
  `reference/` brief and stylesheet are edited in place now.

The first design's web app (sign-in, NosFabrica's own Anthropic key, a bunker countersigning
the publish, its own SQLite store) was removed on 2026-11-12. It was never deployed. Its history
and its measurements are in git up to `0901b92`.

## Build

    ./gradlew build --max-workers=2           # compile + test + spotlessCheck
    ./gradlew spotlessApply                   # before every commit
    ./gradlew :generator:run --args="<npub> --dry-run"
    ./gradlew :runner:installDist             # the worker; see runner/README.md

Other agents build Quartz and Amethyst on the same machine: one Gradle invocation at a time.

## Rules

- **The engine follows Amethyst.** Keep its file, class and function names, and port changes
  from there rather than diverging here. `tools/diff-amethyst.sh <amethyst checkout>` shows
  the difference. What is deliberately left out (accounts, the local store, the per-device
  claim, view models, the picker) is listed at the top of that script.
- **Settled** (don't reopen; see PLAN.md): the reader's own NIP-FA key writes, never a
  NosFabrica key; a fixed 24-hour window; the model writes the whole document (the engine
  inserts the house stylesheet, as Amethyst's does); only checked pages are shown or published; hotlinked art; no NSFW
  classification; the name is The Observer.
- **Never log a key or a plaintext.** Not the AI key, not a decrypted schedule, not a
  decrypted kind 12000, not a ciphertext. Log owner, kind and outcome. `askedOf` names a
  provider by host only. The runner's tests assert this on captured logs.
- **Never call a real model, relay or Blossom server from a test.** Tests fake the HTTP
  layer (OkHttp interceptors), the relay client (`INostrClient`) and the server API.
- **Only the owner's Blossom servers.** No kind 10063 means no publish, never a fallback.
- **Quartz** comes from JitPack pinned by a full commit hash (`gradle/libs.versions.toml`).
  Hashes carry no order, so every module `force()`s the pin; the only relation between two
  hashes is `git merge-base --is-ancestor`. Quartz is KMP with Android in its graph, so
  `settings.gradle.kts` needs `google()`. `-PquartzDir=../quartz` builds a local checkout.
- **Dependency licences**: read the LICENSE or POM before adding one. Permissive is fine;
  LGPL or a linking exception, say so; anything stricter, stop and ask.
- Comments say why, especially why not. Run `spotlessApply` before committing.
- Commits are conventional (`feat:`, `fix:`, `docs:`, `refactor!:`). Never `--no-verify`.

## The search relay is shared

`wss://search.brainstorm.world` is production and other people read it. Read from it; never
publish test events to it, and don't hammer it (it has spells of not answering).

- It CLOSES any REQ whose `search` names neither `observer:<hex>` nor `include:spam`: an
  unranked lookup must say `include:spam` (`INCLUDE_SPAM`), or it reads as empty.
- A NIP-50 search without `since` times out; with a 24-hour `since` it answers at once.
- `observer:` ranks, it doesn't filter: `limit` is the lens's cutoff, and
  `filter:rank:gte:N` is the trust floor (not redundant with `limit`).
- A REQ over NIP-11 `max_message_length` is dropped in silence: author lists are chunked.
- Read NIP-11 before theorising about a relay.

Every number above was measured on a stated date (August 2026). Re-measure before relying on
one.
