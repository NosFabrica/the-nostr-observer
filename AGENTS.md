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
- `service/`: the Brainstorm Assistant's Observer task service (`observer-service`), a
  container on Brainstorm's servers that assistant-runner wakes with `POST /v1/run` for one
  owner. It reads the Assistant's key from brainstorm_server's `brainstorm_nsec` and Fernet key
  file (`:core`'s `AssistantKeys`), decrypts and signs as the Assistant with the allowlist the
  server enforced (`LocalAssistant`), and keeps its runs and unpublished papers in Postgres
  (`assistant_runner.*`). It never calls brainstorm_server. `service/README.md` has what a wake
  does and the deployment.

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
    ./gradlew :service:installDist            # the task service; see service/README.md

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
  decrypted kind 12000, not a ciphertext, not the Assistant's nsec or a Fernet key or token.
  Log owner, kind and outcome. `askedOf` names a provider by host only. The service's tests
  assert this on captured logs.
- **Never call a real model, relay, Blossom server or database from a test.** Tests fake the
  HTTP layer (OkHttp interceptors), the relay client (`INostrClient`) and the key table
  (`MemoryDirectory`), and use `:core`'s in-memory stores. Postgres tests use `:core`'s
  `TestPostgres` (a throwaway `initdb` cluster, or `TEST_DATABASE_URL`) and are skipped, saying
  so, when there is neither.
- **Only the owner's Blossom servers.** No kind 10063 means no publish, never a fallback.
- **Quartz** comes from JitPack pinned by a full commit hash (`gradle/libs.versions.toml`).
  Hashes carry no order, so every module `force()`s the pin; the only relation between two
  hashes is `git merge-base --is-ancestor`. Quartz is KMP with Android in its graph, so
  `settings.gradle.kts` needs `google()`. `-PquartzDir=../quartz` builds a local checkout.
- **assistant-runner's `:core`** (`NosFabrica/assistant-runner`: the Assistant keys, the
  `assistant_runner` schema's stores, the trigger contract) is pinned the same way
  (`assistantRunner` in `gradle/libs.versions.toml`, forced, its `quartz` pin equal to ours).
  That repository is **private**, so JitPack can't build it yet: `settings.gradle.kts` builds a
  checkout instead, `-PassistantRunnerDir=<path>` or `../assistant-runner` when it exists. Until
  the repository is public, CI needs a checkout of it beside this one (or the build fails to
  resolve the pin).
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
