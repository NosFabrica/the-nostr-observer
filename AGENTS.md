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

- **There is no fallback lens.** A reader without a resolvable `kind 10040` gets
  the readiness chain and a wait, not a paper. The provisional lens — follows
  plus follows-of-follows, ranked by recency — was built for the 4.5% finding
  and removed on 2026-08-18: it showed a first-time reader the one version of
  the product that cannot demonstrate what the product is for (measured overlap
  with the unranked control: 0 of 400, where a real lens gives 1 of 400), and it
  read up to 120 strangers' follow lists off other people's relays to do it.
  `observer:<pk> sort:rank` with an unresolvable token does not error — it
  silently becomes the anonymous ranking — so the readiness chain is the gate,
  and `Press` refuses with `NO_LENS` rather than building a corpus another way.

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
- **`observer:` RANKS, it does not filter.** The candidate set for a query is
  the whole window — 35,084 kind-1 notes in 24 hours, measured 2026-08-18 — and
  `limit` is what turns that into a top-N. So `limit` is the lens's cutoff, not
  pagination, and "just ask for everything" is a request for the firehose.
- **Selection is by score; delivery is by `created_at` descending.** Measured:
  a `limit=400` read spans the full 23.5 hours and shares only 48 events with
  the newest 400 of a `limit=3000` read, so it is not a recency cut — but 2999
  of 2999 consecutive pairs arrive in time order, and quartz's `EventCollector`
  appends without sorting, so that ordering is the relay's. **The score is not
  recoverable from the response**, which is why a client cannot do its own
  ranked cut and why `limit` has to carry that job.
- **`filter:rank:gte:N` is the trust floor, and it is NOT redundant with
  `limit`.** Counts over one 24-hour window for the prototype observer: no floor
  35,084 · gte:5 22,899 · gte:10 16,265 · gte:20 11,838 · gte:30 9,607 · gte:50
  6,834. At `limit=400`, adding `gte:20` replaced 49 of the 400 notes — so the
  top-N is not a strict top-N by the same score the floor uses. The pipeline
  sends `gte:20`; the control run gets no floor and no observer, deliberately.
- **The floor bites hardest on the small desks.** Same window: long-form 94 → 22,
  calendar 100 → 28, file metadata 29 → 10, wiki 20 → 11, and what survives is
  concentrated in very few authors (wiki: 11 entries from 1 person). That is
  "sections are earned, not fixed" working as the plan intends, but it is a
  visible editorial change and not only a quality gate.
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
  reports an empty list. An edition built from a 600-pubkey author list across
  nine desks — ~353 KB — returned zero events this way while every one of those
  queries answered normally on its own. Six desks at 235 KB answered; nine at
  353 KB did not. `Relays.batches` splits under a budget. That caller has since
  been removed with the provisional lens, so the split is now a guard nothing
  exercises in production.
- **Read NIP-11 before theorising about a relay.** `limitation` states the
  message cap, filter count, subscription count and default limit. All of the
  above was one `curl -H "Accept: application/nostr+json"` away.

## The Claude Code skill (`.claude/skills/`)

**It lives in `.claude/skills/` and not in a `plugin/` wrapper, deliberately.**
It was a plugin first. That bought nothing: no `marketplace.json` existed
anywhere, so nobody could install it as one, and the skill ships no commands,
agents, hooks or MCP servers — the only things a plugin adds over a skill. What
it cost was the install step. `.claude/skills/` is where Claude Code looks for
PROJECT skills, so a checkout of this repository now has the Observer skill
loaded already, in the terminal, in the desktop app, and at claude.ai/code —
which is the whole browser path, and it needs no terminal at all. One copy, so
nothing to mirror and nothing to drift. If a marketplace listing is ever wanted,
a `.claude-plugin/plugin.json` is ten lines and can be added then.

Plain claude.ai chat is NOT a substitute, and the blocker has no workaround.
Custom skills do upload there (Customize → Skills, as a ZIP, with code
execution enabled), but the sandbox reaches an allowlist — Anthropic, the
package registries, github, ubuntu — that does not include the relay. The
"all domains" setting opens HTTP egress through a proxy; the ranked query is a
websocket. Measured 2026-08-22: `search-staging` serves its NIP-11 document
over HTTPS and 404s `/req`, `/api/search` and `/search`, so there is no HTTP
form of `observer:<pk> sort:rank` to fall back to.

A second, much smaller implementation of the read path, in Node, shipped as a
Claude Code skill. It exists because it is the only distribution of this product
that can legitimately run on the reader's own Claude subscription: Anthropic's
[legal-and-compliance page](https://code.claude.com/docs/en/legal-and-compliance)
says OAuth is for "ordinary use of Claude Code and other native Anthropic
applications" and that third-party developers may not "route requests through
Free, Pro, or Max plan credentials on behalf of their users". Distributing a
skill is distributing text; the reader's own Claude Code makes the call. A
desktop app that spawns their `claude` CLI would not clear that bar, and neither
would a `CLAUDE_CODE_OAUTH_TOKEN` pasted into anything of ours.

- **NO NIP-45 COUNT ANYWHERE.** Every question it asks is a REQ. That is what
  sidesteps the AUTH-challenge-before-COUNT behaviour, the four-concurrent-COUNTs
  hang, and the spells of not answering COUNTs at all — all three recorded above.
  The cost is stated in `readiness.mjs`: link 3 becomes present/absent instead of
  a percentage, so the skill never reports `importing` and never prints a bar.
  That is the existing contract (`Readiness.fraction` already returns null with no
  honest denominator), not a new one.

- **The readiness chain is a GATE, not a warning.** Same reason as everywhere
  else: an unresolvable observer degrades silently. Measured 2026-08-21 against
  `search-staging` with an npub that has no cards for its provider: the fourteen
  desks returned 0 events while the control run returned 400 — the `filter:rank:gte:20`
  floor bites where the bare `sort:rank` probe would have degraded quietly. Do not
  read that as the floor making the gate unnecessary; the readiness probe itself
  sends no floor, precisely so it can see the degradation.

- **`validate.mjs` does two jobs, because there is no sanitizer.** In the Kotlin,
  `Sanitizer` strips and `Validator` verifies. The skill has no stripping half, so
  forbidden markup is REFUSED rather than removed — a silent strip would hide a
  successful injection. Its haystack is the ranked desks only, matching
  `Validator.kt`'s `corpus.all()`; the control run is not quotable.

- **Permalinks are jumble.social `nevent1` URLs**, decoded rather than captured.
  The writer cites `https://jumble.social/notes/<64-hex>`; `resolve.mjs` encodes
  the nevent; `validate.mjs` decodes it and checks the id against the corpus.
  The Kotlin regex once allowed `nevent1…` in a branch that captured nothing, so
  every such link compared against the empty string and a page citing its sources
  the normal way failed its own check. Decode, or do not accept the link. The
  full Observer still uses njump.me; this is the skill's host.

- **`reference/` is generated.** `tools/sync-skill.sh` copies `system-prompt.md`
  and `house.css` in and prepends a banner correcting the three statements in the
  brief that are true only of the Messages API harness (a sanitizer runs after
  you; the corpus is a `<corpus>` block; return HTML and nothing else). Run it
  after editing either resource — the copies are committed, so `git diff
  --exit-code` after running it says whether they are current.

- **Artifacts block remote images.** Art is hotlinked by settled decision, so in
  the artifact view every picture degrades to its caption and only the saved
  local file shows the art. That is why the brief's `alt`-text rule earns its
  keep here rather than being theoretical.

- **`resolve.mjs` exists because the brief promises it.** The brief says
  `<img src="art-3">` and "the id is replaced with the real URL afterwards", and
  "never write a raw URL in `src`". The first version of this skill told the
  writer the opposite and validated URLs only, so a page written to the brief
  would have had every picture rejected — the same two-halves-disagreeing bug as
  the `nevent1` branch that captured nothing. `resolve.mjs` is that afterwards:
  ids to URLs, unknown id loses its whole figure, hex permalinks encoded to
  jumble.social nevent URLs, links to the open web unwrapped to plain text. It
  PRINTS every change that is not a plain resolution, because a dropped figure
  or an unwrapped link is the visible edge of an injection attempt and a
  sanitizer that tidies up in silence hides the one event worth seeing.

- **Tests: `node --test ".claude/skills/nostr-observer/test/*.test.mjs"`,** wired
  into `build.yml` alongside a `git diff --exit-code` on the generated
  `reference/`. `test/fakerelay.mjs` is a dependency-free websocket server that
  reproduces the AUTH-before-answer challenge, a mid-stream NOTICE, a silent
  subscription and a CLOSED-with-reason, so the relay hazards above are held to
  the same no-network rule as the rest of CI. The golden-edition test runs the
  56 KB prototype broadsheet through `resolve` + `validate` with a corpus derived
  from the page itself and asserts nothing is flagged: the adversarial tests ask
  whether the boundary stops bad pages, and that one asks whether it damages good
  ones, which is the likelier way to ship something broken.

### Audit, 2026-08-22

Five bugs, three of them one root cause, plus the two costs nobody had measured.

- **A `>` inside any attribute value made the boundary FAIL OPEN.** Element
  lookup was `<img\b[^>]*?\bsrc=…`, and `[^>]*?` ends at the first `>` wherever
  it is — so `<img alt="a > b" src="https://evil.example/x.jpg">` was never
  matched and never checked, and `<a title="1 > 2" href="…">` slipped the link
  rule the same way. Captions come from the corpus, and the corpus is where the
  attacker writes. `resolve.mjs` was blind in the same place, so an id inside
  such a tag shipped as a literal `src="art-3"` that validate could not see
  either. All three now go through `html.mjs`, a scanner that tracks quoting.
  A regex cannot do this: knowing where a tag ends means knowing whether you
  are inside a quoted value.

- **`/\son[a-z]+\s*=/` over the raw document read prose as an attack.** "we ran
  it once=twice" and "the flag is only=set" both tripped it. That fails CLOSED,
  so it is the golden edition's failure mode — a boundary that rejects good
  pages prints nothing every morning — and it walked past the golden test only
  because the fixture happens to contain no such phrase. Markup checks now run
  against parsed tags and attribute names. Two holes closed on the way past:
  `<base href>` rewrites every relative URL on the page and `<meta refresh>`
  redirects it, and neither was refused.

- **The REQ budget counted characters where the relay counts bytes.** A filter
  up to twice `max_message_length` passed the guard and was then dropped in
  silence — precisely the failure the guard exists to prevent. `Buffer.byteLength`.

- **One socket per read.** Six for the readiness chain, sixteen for a corpus
  pull, each a fresh handshake to a host this file says not to hammer, and
  which advertises a subscription limit of fifty. `nostr.mjs` now pools one
  connection per relay and multiplexes subscriptions over it, as `Relays.kt`
  does with quartz's single `NostrClient`; it closes on an unref'd linger so
  consecutive reads reuse it and an idle process still exits. The fake relay
  counts connections so the rule stays true. Readiness also stopped fetching
  the 10002 and the 10040 one after the other — they are independent — and the
  storage chain rides along instead of costing a seventh round trip. Measured
  after: readiness 0.8s, a full fifteen-desk corpus pull 3.0s.

- **The digest was unbounded, and the reader pays for it.** Measured on a
  realistic busy window it came to 335,000 characters — about 84,000 tokens —
  before the writer had done anything, most of it long-form excerpted at the
  same length as a one-line note. Now per-desk excerpt lengths and a 200,000
  character budget, which lands a busy day at ~50,000 tokens and leaves a quiet
  day untouched. Trimming has a floor per desk, because trimming purely by size
  cut the notes to 64 of 400 to protect a long-form column nobody asked for —
  the budget making an editorial decision, which is not its job. **Whatever
  comes off is named in the digest**: a digest that quietly drops half the
  long-form reads as a quiet day for long-form, and a thin honest paper is
  supposed to mean one.

### First real edition, 2026-08-22

The chain passed for the first time, against the key in `Fixtures.OBSERVER`, and
`system-prompt.md` met a model. Readiness green on all four links; 671 events
across 13 desks in 3.0s; 247 voices; **overlap 0 of 400**. The boundary came
back clean on the first pass — 21 quotes verbatim, 3 art ids resolved, nothing
dropped or unwrapped. Edition D8C3EA.

Three things the run found that no test could:

- **Banning `<meta>` outright rejects every real page.** The audit added it to
  stop `<meta http-equiv="refresh">` and took `charset` and `viewport` with it.
  Narrowed to the http-equiv form. The golden fixture is a body fragment, so it
  has no `<head>` and could never have caught this — the same false-positive
  class as the prose that read as an event handler, found the same way, by
  running the thing rather than testing it.

- **The Node digest drops structured fields the brief depends on.** `Digest.kt`
  emits `PRICE`/`STATUS` for classifieds, `WHEN`/`LOCATION` for calendar, and
  `AUTHOR`/`SOURCE`/`CONTEXT` for highlights; `corpus.mjs` emits title and
  content only. They were recoverable from the raw tags in `corpus.json` by
  hand, but unaided this prints a shop column with no prices — "everything
  except the news" — and invites attributing a highlight excerpt to the
  highlighter, which the brief forbids outright. Highest-value next fix.

- **No reader timezone, and no denominator.** The brief says never print UTC and
  never convert a time yourself; the digest carries only UTC, so the dateline's
  date is a judgement rather than something handed over. And with no COUNT there
  is no honest `N of M`, so the middle span read "671 events through your lens"
  instead. Both are the cost of the no-COUNT rule and the thin digest, and both
  are visible on the furniture of every edition.

### Alt text is the fabrication channel nothing guards

Reviewing why the first edition's photographs did not appear, 2026-08-22. Three
findings, and the interesting one is not the images.

- **The markup and the URLs were correct.** All three resolved to live hosts,
  HTTP 200, right content-types, two of them serving `access-control-allow-origin: *`.
  The artifact viewer runs a content policy that refuses every external host, so
  the pictures never load there however good the URL is. Not a bug and not
  fixable from inside the page; the saved local file shows them fine.

- **`imeta alt` is a fiction in the wild: 0 of 40 shortlisted pictures carried
  one.** `Art.kt` keeps `alt` on the theory that it is "the difference between a
  missing image degrading to a caption and degrading to a gap". That only works
  if somebody writes it, and on a real 24-hour window nobody did. So the writer
  has to author the alt, and `SKILL.md` never asked it to — the first edition
  shipped three bare `<img>` tags and degraded to three empty boxes, which is
  precisely the outcome the alt rule exists to prevent.

- **THE REAL ONE: nothing checks captions or alt.** `Validator` gates quotes,
  picture sources and links. A caption is prose about a photograph the writer has
  never seen, published under a real person's byline, through the only channel in
  the pipeline with no gate on it. Two of the first edition's three captions
  asserted things not in evidence — one described the contents of a frame, the
  other said a picture was taken close up when the post said you would have to
  zoom in to see anything. Both were caught by reading, which does not scale.
  `SKILL.md` now forbids describing a picture you cannot see and requires caption
  and alt to be derived from the post. **A mechanical check is still missing**,
  and it is a harder problem than the quote rule: there is no source text to
  compare a caption against, only the post it came from.

## The publish path (Phase 3)

- **The server holds no key and can sign nothing.** It builds the two events a
  publish needs (`24242` upload auth, `35128` manifest), hands them to a signer,
  and checks what comes back with `Countersign` — same author, same tags, valid
  signature. Building the template server-side is what makes that check possible
  at all; a flow that just relays whatever the client invented has nothing to
  compare against.
- **`kind 35128` replaces — which is why each edition is its own site.** A `d`
  of `observer-<date>` means a new day replaces nothing, so no publish has to
  first read the archive and merge into it. That hazard (a read that came back
  empty for the wrong reason deleting the back catalogue) is gone along with the
  canary and the fail-closed refusal it needed.
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
- **Half the Blossom servers a reader is likely to list will not host HTML**,
  and that is policy rather than a bug: HTML served from their domain is script
  on their domain. Measured 2026-08-18 with a throwaway key, uploading a real
  36 KB edition (`./gradlew :server:blossomProbe`):

  | server | answer |
  | --- | --- |
  | `blossom.primal.net` | 200, and the URL comes back with `.html` on the end |
  | `nostr.download` | 201 |
  | `nostr.build`, `blossom.band` | 415 `File type not allowed` — same backend, same refusal |
  | `blossom.f7z.io` | 401 `Pubkey not authorized by any storage rule` |
  | `cdn.satellite.earth` | no answer inside 60s |

  `nostr.build` answers `text/html` with a 400 whose sentence is nonsense
  ("expected application/json"); send `application/octet-stream` and it gives
  the honest 415. Both arrive in `X-Reason`, which is why that header is read.
- **The archive is read from the reader's relays and nowhere else.** It used to
  ask `hosts.take(3) + searchRelay`, which was wrong twice: an edition could be
  listed because OUR relay resolves it while theirs do not — "resolves for us
  and for nobody else", dressed as a working feature — and a publish goes to
  every write relay while the read looked at three, so an edition that landed
  on the fourth was invisible. `ReadinessProbe.blossomServers` still asks ours
  as well, deliberately: that read only decides where to try uploading, and a
  wrong answer there fails loudly at the upload rather than misleading anybody.
- **A refused upload destroys something, and the console says so.** The page is
  written, checked and paid for, and it lives in `Runs` memory and nowhere
  else. So that path sets the run `FAILED` (leaving it `SIGNING` meant the next
  poll asked the reader's signer for two more signatures for a publish that
  cannot happen), answers with `Lost` rather than a one-line `Problem`, and
  offers the bytes at `GET /api/editions/{id}/page` for as long as the sweep
  leaves them. Served `application/octet-stream` as an attachment, never
  `text/html`: this origin holds the session cookie, and an edition is markup a
  model wrote. `GET /api/editions/current` exists only so a reload finds that
  offer again.
- **An edition is READ at `/view`, and the `sandbox` in its CSP is what makes
  that allowable.** `/page` still hands the bytes over as a download and has not
  changed; `/view` serves the same bytes as `text/html`, because a reader has to
  be able to look at their paper — and an edition that failed its own checks
  will never be published, so nowhere else will ever hold it. The rule above is
  not repealed, it is met a different way: `Content-Security-Policy: sandbox`
  as a *response header* puts the document in an opaque origin however it is
  reached, framed or navigated to directly, so the session cookie is unreachable
  from it. `allow-scripts` and `allow-same-origin` must never both appear —
  together they let a page remove its own sandbox. The rest of the header
  (`default-src 'none'`, `img-src https: data:`) restricts what the page may
  load and would not, on its own, have been enough.
- **Blossom is a blob store, and linking a reader at `server/hash` is not a
  view.** It hands back bytes with whatever `Content-Type` it likes, so the same
  link renders on `blossom.primal.net` and downloads elsewhere — which is what
  the archive's "Read it" did. An nsite is resolved by something that reads the
  manifest and serves the blob AS a site; `GET /api/archive/{day}/view` is that,
  for one signed-in reader's own editions. `Blossom.fetch` tries their servers in
  order (a read needs one copy; only a publish needs all of them) and **checks
  the blob against the hash in the manifest the reader signed** before serving a
  byte of it. A server that answers with something else is refused outright, not
  passed over quietly — it is either broken or lying and both are worth saying.
  Nothing is stored here; the canonical copy stays on their servers.
- **`blossom.primal.net` appends `.html` to the URL it returns.** Concrete proof
  that `server + "/" + hash` was a guess: the hash alone also resolves there
  today, but nothing requires it to. Take the URL from the descriptor.
- **The archive cannot take it from the descriptor, so it does not offer one at
  all.** A manifest names servers and a hash; the descriptor was a response to a
  request made on a different day and we deliberately store nothing. `Past` used
  to carry `servers.first() + "/" + hash` assembled in the route — the same
  guess, one layer along, on the side where there is no descriptor to correct it
  with. It is gone: `/api/archive/{day}/view` reads the edition properly and a
  guessed link had nothing left to do. `Blossom.fetch` builds that same string to
  FETCH with, which is fine and is not the same thing — it checks the bytes
  against the signed hash, moves to the next server on failure, and reports which
  ones failed. A guess that catches itself is not a guess handed to a reader.
- **The whole path has been run against the real network**, once, end to end:
  `./gradlew :server:liveRun`. It mints a throwaway key, publishes its `10002`
  and `10063`, then goes through `writeRelaysOf` -> `servers` -> upload ->
  `Countersign` -> `Announce.publish` -> `editions` and checks that the archive
  names the page that was uploaded. It is a `main()` in the test source set with
  no `@Test`, because it writes to other people's machines.

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

## Highlights are somebody else's words (2026-08-18)

A `kind 9802` highlight's content is a verbatim excerpt of another person's
writing. The digest rendered it exactly like a post — byline of the
highlighter, no source, no author — so a model reading it writes *"Gigi wrote:
…"* when Gigi only marked the passage. A real quote, under the wrong name,
signed by the reader.

**The validator cannot catch this.** It checks that quoted text appears
verbatim in a source event, and it does — in the highlight. Text fidelity and
correct attribution are different properties, and only the first was ever
checked. Anywhere the corpus carries one person's words under another person's
signature, the same hole opens.

Measured over 31 highlights in one window: 11 carry a `p` naming the author, 20
an `r` source URL, 7 an `a` long-form address, 18 a `context` with the
surrounding passage. All of it was being discarded. The digest now prints
`HIGHLIGHTED BY`, an explicit EXCERPT warning, `AUTHOR` (resolved to a name, or
"not named" — silence invites the writer to fall back on the byline), `SOURCE`
and `CONTEXT` marked as unquotable. Quoted authors are added to the profile
fetch, since they signed nothing in the window and would otherwise be hex.

## Content added 2026-08-18

- **`30311` live, and only while live.** Replaceable events keep the record of a
  finished stream in the window: 11 `live` against 7 `ended` in one measured
  day. `Desk.keeps` drops the ended ones. "Now" means generation time — the page
  is static, so the prompt tells the writer to say when a stream started rather
  than promise it is still running.
- **`1068` polls.** Options arrive as `["option", id, label]` in two id shapes
  (`"0"` and `"Bu2a9f"`), so the label is always the second field.
- **`30617` git repositories.** Uses `name`/`description` where everything else
  uses `title`/`summary`.
- **`31922` joined the calendar desk** — the all-day half of NIP-52 to 31923's
  timed half. Zero in the measured window, which is how the gap stayed
  invisible.
- **Rejected with numbers:** `1111` comments are the biggest untapped pool by
  people (266 events, 124 authors) but only 15 of 263 point at anything in our
  corpus, so a desk of them is context-free replies. `9735` zap receipts are the
  highest-volume unread kind (407) and are a SIGNAL, not a desk — 22 of 580
  corpus events were zapped in-window, top post 5 times.

## Video (2026-08-18)

- **The current NIP-71 kinds are empty; the deprecated ones carry the video.**
  Measured through the prototype observer, one 24-hour window at floor 20:
  `kind 21` → 0, `kind 22` → 0, `kind 34235` → 6 from 5 authors, `kind 34236` →
  37 from 13. A desk asks for both. This is the mirror of the nsite decision,
  where the CURRENT kind was right — so check, do not assume.
- **A desk may span several kinds now**, which is only safe because each desk is
  one REQ. While they shared a REQ, results were recovered by kind and two desks
  claiming one kind collided — that was the bug that filed the control run as
  news.
- **A video's poster is `imeta`'s `image` field, and it is never sniffed.**
  Every real one measured was extension-less (`media.divine.video/7f4e79…`), so
  an `isImage` check rejected six of seven. `m` describes the video and says
  nothing about the poster, and one real 34235 had a poster and no `m` at all —
  so the event's KIND decides. About one video in six carries a poster; the rest
  are text stories, which is fine.
- **Art slots are allocated per desk first, then by rank.** One pass in corpus
  order gave all forty to notes and pictures, so a video poster could not reach
  the page however good it was. Each desk takes up to four, then rank order
  fills the rest.

## Found by audit (2026-08-19), third pass

- **Playwright is not concurrent, and `Proof` is a singleton.** `Press` holds one
  browser and the server holds one `Press`, so two readers printing at the same
  moment rendered through the same instance. Measured with four threads: one
  succeeded, three threw `Object doesn't exist: tracing@…` and
  `Cannot find object to call __adopt__: browser-context@…`. On the server that
  landed in the catch-all in `Editions.run`, so the second reader lost an
  edition the model had already been paid for. Every call now queues on one
  owning thread, and `check` degrades to `ran = false` rather than throwing —
  by the time it runs the money is spent, so a browser problem must not also
  cost the paper. `ProofConcurrencyTest` holds the line.
- **An expired session left its remote signer connected forever.** `Sessions`
  keys on the SHA-256 of a token, deliberately; `Bunkers` keyed on the raw
  cookie. So the two maps had no name in common, the sweeper could not close
  what it expired, and a live subscription to the reader's signer outlived its
  session for the life of the process — with a map of working cookies next door
  to the one that carefully holds none. Both key on `Sessions.fingerprint` now
  and `sweep()` returns those keys so housekeeping can close them.
- **`removals` grew without bound on authenticated input.** It was keyed by
  reader AND day, with a comment saying a sweep would cost more than the leak.
  True of anybody using it; not true of anybody looping, since
  `/api/archive/{day}/remove` takes any well-formed date and nothing removed an
  entry that was never signed. One per reader now, and swept.
- **`/api/readiness` fetched the reader's entire archive to pick a word.** It
  ran `announce.editions` — a fan-out across every one of their relays, pulling
  every site event they have ever published — to choose between "has published
  before" and "asked at publish" on one link of a chain inside a closed
  `<details>`. Both branches of `Readiness.storage` return the identical
  verdict, and the console asks `/api/archive` moments later, which ran the same
  query again. Removed. `nameOf` and `storage` on that route also ran one after
  the other against the same hosts; they are concurrent now.
- **`blossomServers` asks three of their relays; `editions` asks all of them.**
  Same-looking code, opposite requirement, so it is written down in both: a
  `kind 10063` is replaceable and every relay has the same one, while an archive
  is a union and any relay may hold the only copy of a day.

## Found by audit (2026-08-18), second pass

- **`until` never reached a filter.** It was threaded from the CLI into `Corpus`
  and read by nothing, so a window had a start and no end: `--until` backdating
  asked for "the 24 hours ending last Tuesday" and got everything from last
  Monday to now. Latent on the server, which always passes the present. Both
  ends are in the filters now, verified by a backdated run whose events all fall
  inside the requested day.

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
