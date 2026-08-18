# AGENTS.md

The headless generator (`generator/`) and the web app (`server/`) are both
built and run against the live relay. What has never run is the model call
itself: there is no `ANTHROPIC_API_KEY` in the dev container, so everything from
`Writer.write` onward is untested against the real API. **[`docs/PLAN.md`](docs/PLAN.md) is the design**
— read it before writing code; this file holds only what the plan does not: the
decisions that are settled, the ones that are not, the readings taken off the
live relays, and the conventions to hold to.

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

    ./gradlew :server:installDist
    OBSERVER_INSECURE_COOKIES=true PORT=8099 server/build/install/server/bin/server

`OBSERVER_DB`, `OBSERVER_RELAY`, `OBSERVER_EFFORT`, `PORT`, `HOST` and
`OBSERVER_INSECURE_COOKIES` configure the server. The last one lets the session
cookie travel over plain HTTP and is for local work only — a deployment that
sets it is asserting that TLS terminates somewhere in front of it.

`--check` reports the readiness chain and stops. `--dry-run` does everything
except call the model and writes the digest instead of a page. Neither needs an
API key. A full run reads `ANTHROPIC_API_KEY` from the environment.

## Settled — do not relitigate without a reason

- **Nostr goes through quartz. All of it.** `NostrClient` + the `fetchAll` and
  `count` accessories, `Filter`, `Event`, `AdvertisedRelayListEvent`,
  `ServiceProviderTag`, `MetadataEvent`, NIP-19 decoding. The generator once
  carried ~400 lines of hand-rolled bech32, websocket and NIP-01 dispatch; all
  of it already existed in the library the relay itself is built on. What is
  left local is in `nostr/Relays.kt` (timeout and REQ-size policy) and
  `nostr/Tags.kt` (generic tag reads quartz has no named helper for).

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
- **COUNTs must go one at a time.** Issuing the readiness chain's four COUNTs
  concurrently was tried and `--check` went from ~3s to hanging. Probably the
  AUTH challenge above, racing on one socket. The fetches around them do run in
  parallel; the counts do not.
- **The relay goes through spells of not answering COUNTs at all.** Seen
  2026-08-18: `--check` blocked until killed on roughly half of consecutive
  runs, and reproduced identically on the previous commit, so it is the store
  and not the client. `Relays.deadline` bounds every read so a request handler
  cannot block forever, but the underlying cause is undiagnosed. **This is the
  reason not to hammer it** — the audit itself did, and should not have.
- **Neither `nip85.nosfabrica.com` nor `scores.brainstorm.world` exposes an HTTP
  API.** Both answer NIP-11 as plain strfry relays, so minting a lens is an
  operator step, not a call.
- **A REQ over `max_message_length` is dropped in silence.** `search-staging`
  advertises 262144 bytes and enforces it with no NOTICE and no CLOSED: the
  subscription stays open saying nothing, the idle timer expires, and quartz
  reports an empty list. A provisional edition — nine desks × 600 author
  pubkeys, ~353 KB — returned zero events this way while every one of those
  queries answered normally on its own. Six desks at 235 KB answered; nine at
  353 KB did not. `Relays.batches` splits under a budget now.
- **Read NIP-11 before theorising about a relay.** `limitation` states the
  message cap, filter count, subscription count and default limit. All of the
  above was one `curl -H "Accept: application/nostr+json"` away.

## The publish path (Phase 3)

- **The server holds no key and can sign nothing.** It builds the two events a
  publish needs (`24242` upload auth, `35128` manifest), hands them to a signer,
  and checks what comes back with `Countersign` — same author, same tags, valid
  signature. Building the template server-side is what makes that check possible
  at all; a flow that just relays whatever the client invented has nothing to
  compare against.
- **`kind 35128` replaces.** Every publish rebuilds the manifest from the full
  archive in our own index. A manifest carrying only today is a manifest that
  deleted every other day.
- **The manifest goes out only after a Blossom server has the blob.** A manifest
  pointing at a hash nobody stores is a 404 with a signature on it.
- **NIP-46 runs on the server, NIP-07 in the browser.** A browser NIP-46 client
  needs secp256k1 ECDH, which WebCrypto does not have; and mobile browsers drop
  websockets when the tab is backgrounded, which is exactly when the reader is
  in their signer app. A server-held connection does not get backgrounded, and
  it is what Phase 4's scheduled runs need anyway. The cost is stated in
  `Bunkers`: while a session is open, this process can ask the reader's signer
  to sign the three kinds it asked permission for.
- **`Nip98AuthVerifier.verify` takes `(header, METHOD, URL, body)`.** All four
  are `String`s, so swapping the middle two compiles and fails at runtime with
  "method mismatch: expected http://.../api/session, got POST". It fails closed;
  a test caught it.
- **An empty `kind 10063` is a hard stop, not a default.** Substituting a server
  of our own would make us the host of a page whose whole promise is that the
  reader hosts it.

## Found by audit (2026-08-18) — do not reintroduce

- **Never rebuild our own URL from request headers.** Sign-in compares a NIP-98
  signature's `u` tag against the URL of the request. That check was made
  against `Host` / `X-Forwarded-Host`, both of which the caller chooses: any
  site can ask a visitor to sign an event for a URL it controls and replay it
  here with a matching header to be signed in as them. It is now
  `Config.publicUrl`, and two tests hold it shut. **A deployment MUST set
  `OBSERVER_PUBLIC_URL`** or every sign-in is rejected.
- **The two halves of the link rule must agree.** The permalink regex allowed
  `nevent1…` in a branch that captured nothing, so `groupValues[1]` was empty
  for every real citation. The sanitizer kept those links and the validator
  rejected them — and a validator failure throws away the entire edition. njump
  citations are decoded through quartz's NIP-19 parser now, and the sanitizer
  takes the corpus so an unknown citation loses one link instead of the paper.
- **Check-then-act on a shared map is a race.** Two clicks on the generate
  button started two editions and two model bills. `ConcurrentHashMap.compute`,
  with a test that fails on the old code.
- **A TTL enforced only on access is a leak.** Drafts, sessions and pending
  templates all expired only when something happened to touch them. A timer
  sweeps them now, which also took a per-poll `DELETE` off the read path.
- **One `Writer` per edition leaked an HTTP client** (connection pool and
  threads) for the life of the process. It is one per `Press` now.
- **The archive is not ours alone.** The manifest is rebuilt from the reader's
  own kind 35128 merged with our index, so losing our database — or moving them
  to another deployment — no longer silently deletes every earlier edition on
  the next publish.

## Quartz behaviours worth knowing here

- **`decodePublicKeyAsHexOrNull` decodes an nsec.** Measured: it returns the
  hex of the SECRET key rather than null, because the payload is 32 bytes and
  that is all it checks. `Main.kt` refuses an `nsec1` prefix *before* calling
  it, or a reader who pastes the wrong key has it put into a relay filter and
  sent over the wire. There is a test that pins this.
- **`AdvertisedRelayListEvent.writeRelays()` does not vet schemes.** It returns
  what the tag said, `https://` entries included. `ReadinessProbe` keeps a
  `wss://`/`ws://` filter over its output.
- **`fetchAll` merges the filters.** The hand-rolled client returned one list
  per filter; quartz returns one list. Desks are recovered by kind, which is
  why the anonymous control run is a separate call — it is kind 1 like the
  notes desk, and merged in it would file spam as news. The overlap number the
  CLI prints is the alarm for that: it belongs near zero.
- **Quartz is Kotlin Multiplatform with Android in its graph.** It pulls
  `androidx.sqlite`, published only to Google's Maven, so `settings.gradle.kts`
  needs `google()`. Without it the failure names the missing AndroidX artifact
  and not the reason, which reads like a broken JitPack pin.

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
