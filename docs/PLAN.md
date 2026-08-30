# Observer Press — project plan

*Draft 4 · 17 August 2026*

A signed-in Nostr user asks for their paper. We read the last 24 hours through
their web of trust, write the front page, and hand it back. If they like it,
they publish it to their own Blossom servers as an nsite — under their key, on
their storage. **We generate; we do not host.**

The reference implementation is a prototype front page built by hand against the
live relay: 773 events across nine kinds, 244 profiles, ranked through
`observer:460c25e6…`, plus an anonymous control run.

---

## 1. The finding that should shape the whole product

Before planning anything, I measured whether the prototype generalizes. It does
not, yet — and the reason is specific and fixable.

> **11 of 244.** Of the 244 people who appeared on the prototype front page, only
> **11** could generate one of their own. 228 have an outbox list (`kind 10002`),
> so the first link of the chain is fine. But only 12 have published a
> `kind 10040`, and only 11 of those name a `30382:rank` provider publicly, with
> a relay hint — the two conditions the store requires before a lens resolves.
>
> Network-wide the population is about the same size: **302** stored 10040s,
> **276** distinct rank-provider keys, **297** observers per `/stats.json`. One
> provider identity per observer.

The failure is silent and it has two shapes, both fatal to a consumer product.
An unresolvable `observer:` token degrades to an anonymous read — which, measured
on the same window, is **209 of 400 posts from a single spam account**. A
resolvable-but-unscored observer is applied as a filter and returns nothing at
all. Either way the user gets a broken page and no explanation.

So the product's first job is not layout and not prose. It is **minting lenses**,
and telling people honestly where they are in that process.

The good news is that this is in-house. Of the ~302 lenses that exist, **181 are
hosted on `nip85.nosfabrica.com`** (plus staging and test hosts) and 70 on
Brainstorm. The scoring service that would have to onboard new observers is
already ours.

### Readiness: two chains, not one

Publishing to the user's own storage adds a second precondition alongside the
lens. Both are checked at pre-flight, both report their own first-unmet link, and
they fail independently: a reader with no Blossom server can still *see* their
paper, they just cannot publish it yet.

| Chain | Link | What must exist | State when missing |
|---|---|---|---|
| **Can we rank for you?** | `kind 10002` | Outbox relay list, so the router can discover anything about them at all | `no-relay-list` / `no-usable-relays` |
| | `kind 10040` | Read from those write relays; names the scoring service they trust | `no-score-list` |
| | `30382:rank` | A *public* entry with a relay hint. A 10040 declaring only `30382:followers` can order a list but cannot rank one | `no-rank-service` |
| | `kind 30382` | Cards signed by that service, actually synced into our store | `no-scores-yet` / `importing` |
| | ranked probe | Ask the authed and anonymous sockets the same thing — cards can be present and not yet *projected* | `projection-pending` |
| **Can you host your paper?** | `kind 10063` | BUD-03 server list naming at least one Blossom server they write to | `no-blossom-server` |
| | upload auth | A signer that will produce `kind 24242` events with a `t` tag of `upload` | `no-upload-consent` |

The lens chain is **already written and already correct**, in the relay
codebase: `shared/readiness.js` holds the decision (`assess(facts)`),
`web/readiness.js` holds the wording, `app.js` drives it. Port the decision
module verbatim. Three properties in it are hard-won and easy to lose:

- **The first unmet link wins**, and every link below it reports `waiting` rather
  than a second failure. A column of red crosses says four things are wrong when
  one is.
- **The ranked probe is not redundant.** Cards can be present and not yet
  projected, because the trust projection is per service and a service new to the
  relay is derived by a reconcile at startup. Asking both sockets is also what
  stops an empty corpus reading as a broken lens.
- **"Your own posts are behind" is an aside, not a link.** Ranking is complete
  without it; folding it in tells a reader whose lens is healthy that their
  search is broken.

---

## 2. Login to write, open to read

Requiring a login to generate settles the abuse question by construction: nobody
can mint a page about somebody else, so there is no crawlable surface of other
people's editions to protect. Reading stays open, because a published edition
lives on the reader's own server under their own key.

| Who | What they can do | Signer |
|---|---|---|
| Anyone | Read any published edition. No account, fully indexable. | none |
| Signed in | Generate their own edition for the last 24 hours; preview it privately; keep a masthead and an archive. | auth sig |
| Publishing | Push the edition to their Blossom servers and announce it as an nsite manifest. | `24242` + manifest |

Ranking through an observer still needs no signature — the login is an
access-control decision, not a technical requirement of the read. Which means the
demo problem does not disappear, it moves: **the shared edition is now the only
demo.** Seed real editions from consenting accounts before launch.

NIP-46 needs care on mobile. An `asknostr` thread in the prototype corpus — Egge,
17 Aug — asks how anyone is handling same-device `nostrconnect://` sign-ins on
browsers that drop websocket subscriptions when the tab is backgrounded. With
login mandatory, that sits directly on the critical path.

---

## 3. Freedom upstream, safety at the boundary

An earlier draft proposed that the model fill a fixed schema and never emit
markup, on the grounds that a model that can write markup can write an injected
`<script>`. That was right about the threat and wrong about the fix — it bought
safety by removing the thing that makes this product interesting.

The property actually needed is not *"the model cannot write markup."* It is
**"the published page cannot execute, cannot phone home, and cannot
impersonate."** That is enforceable after generation, without constraining the
writer at all.

So **the model emits a complete document.** It chooses the grid, decides what
runs above the fold, invents section names, gives a five-story day five columns
and a one-story day a full-width splash. Then everything it produced crosses a
boundary that strips capabilities and leaves the design.

This matters more now, not less: once a page is on the user's Blossom server it
is signed, content-addressed and out of our hands. **The boundary is the last
point at which anything can be checked at all.**

### What the sanitizer allows

- **Allowed:** structural and text elements, tables, lists, `figure`/`figcaption`,
  `class`/`id`/`style` attributes, and one author-written `<style>`
  block. Grid, flex, custom properties, media queries — all of it.
- **Dropped:** `<script>`, every `on*` handler, `javascript:` URLs, `<iframe>`,
  `<object>`, `<form>`, and in CSS, `@import` and any `url()` that is not an
  allowed image.
- **Resolved against an allowlist:** image references. The generator names art by
  an id from the shortlist; the sanitizer replaces each id with *the URL the
  source event actually declared*. An id it invented resolves to nothing and the
  figure is dropped with its caption.

That allowlist is load-bearing now that art is hotlinked. Off-origin image loads
are permitted — but only to URLs that appeared in a real event, never to a URL
the model composed. The rule is not "same origin"; it is **"provably from the
corpus."**

**Links are stricter, and building it taught us why.** The first version allowed
any URL that appeared in the corpus, reasoning that a link nobody posted must
have been invented. A test caught what that misses: the corpus is where the
attacker writes. Posting `click https://evil.example.com/drain` put that URL on
the allowlist, and an injected instruction to link every story to it then passed
cleanly — a phishing link under the reader's masthead, signed by the reader. So
the paper does not link to the open web at all: URLs are printed as text, the way
a printed newspaper prints an address, and the only clickable external link is a
permalink back to a source event.

Our preview is served under `default-src 'none'; img-src https:; style-src
'unsafe-inline'` — no `script-src` at all. Note what that does *not* cover: a
published edition is served by the reader's Blossom host under whatever headers
it sets, so on the published copy the sanitizer is the only protection there is.

---

## 4. The pipeline

| Stage | What it does | Status |
|---|---|---|
| Pre-flight | The lens readiness chain, gathered live and decided by the port. No lens, no edition — the chain says which link is unmet | built |
| Pull | One websocket, one `REQ` per DESK in parallel, `since` = 24h, `search: "observer:<pk> sort:rank filter:rank:gte:20"`. Fourteen desks: 1, 20, 30311, 1068, 21+34235, 22+34236, 1063, 9802, 30023, 30402, 30818, 31922+31923, 32267, 30617 | built |
| Identify | Batch `kind 0` for every author seen, 100 per REQ, newest wins | proven |
| Control run | Same query, observer token swapped for `include:spam` (the relay closes tokenless queries; it is still the anonymous ranking) — the "Instrument" panel. A relay query, not a model call | proven |
| Budget | Prune to a token target. A rich lens returns far more than a sparse one in the same 24 hours, so the cap is on *volume*, not time | built |
| Art shortlist | Read `imeta` for url, MIME, dimensions and alt text. No fetching, no resizing | built |
| Generate | One call. Fixed system prompt + continuity block + digest + art shortlist → a complete document | built, run against the real API |
| Sanitize & validate | Allowlist pass; every quote a verbatim substring of a source event; art resolved from ids; open-web links unwrapped | built |
| Proof | Render the candidate headlessly at 390px and 1280px in both schemes. No horizontal overflow, body text at 4.5:1, no empty sections, no page whose classes resolve to nothing. Regenerate once, then fall back to the house layout | built |
| Publish | Upload the blob, then publish ONE `kind 35128` for that day, `d` = `observer-<date>`. Nothing is replaced and nothing is merged: each edition is its own site, and the archive is the set of them | built |

Two orderings worth keeping. **Art is shortlisted before generation** — not to
save a fetch, but because it is what makes an invented image reference
structurally impossible. And **the proof render happens before the user ever sees
the page**, because a broken layout is now the most likely failure mode: there is
no template to fall back on.

### Gotchas already paid for

- `blossom.primal.net` answers **302 with `content-length: 0`** — a fetch without
  redirect-following writes an empty file and looks like a dead link.
- `imeta` tags carry video as often as stills. Filter on declared MIME, not on the
  URL suffix.
- Phone photos arrive rotated; anything that renders them must respect EXIF.
- A bare `observer:<pk>` with no search term is valid and returns a ranked recency
  feed — that is the whole product, and it works.
- Since 2026-08-30 the relay CLOSES any query whose `search` names neither an
  `observer:` nor `include:spam` — so every unranked read (profiles, the reader's
  own lists, the control run) must say `include:spam` or it comes back empty.

---

## 5. The generation contract

Default to `claude-opus-5` with adaptive thinking. The task is genuinely
editorial — judging which of 400 posts is a story, finding the thread between
four unrelated notes, deciding what shape the page should be. Tune with
`output_config.effort` rather than reaching for a cheaper model.

### Layout free, design opt-in

- The **house stylesheet** ships with the service and is named in the system
  prompt: palette, type scale, rules, column primitives.
- The generator **always writes the markup** — that is where layout lives.
- It writes a `<style>` block **only when it wants to depart** — a black-bordered
  edition for a death, a single-column splash for one enormous story — and says
  why in a field we keep.

Roughly halves output tokens on the ordinary day, because the ordinary day does
not restate 40 KB of CSS.

### Soft continuity

```
continuity:
  masthead:  "The Nostr Observer"
  motto:     "All the Notes Fit to Rank"
  sections:  [Off the Wire, Gardens & Provisions, The Diary]
  recent_headlines: [...]        # so today does not repeat yesterday
rule: keep these unless the day's events genuinely warrant a change.
      if you change one, return changed_masthead and a one-line reason.
```

The model returns the values it used plus an optional reason; we store them and
show the reason in the archive. Drift becomes a visible, occasional event rather
than random churn.

### Cost

| Scenario | Input | Output | Per edition |
|---|---:|---:|---:|
| House style kept — the ordinary day | 70K | 12K | $0.65 |
| Model restyles the page | 70K | 25K | $0.98 |
| Either, via Batch API | — | — | −50% |

Opus 5 at $5/$25 per MTok. Input extrapolated from the prototype's 773-event,
540 KB digest for a 24-hour window, held near-constant by the budget stage. Batch
is asynchronous scheduling, not caching. **No prompt caching** — decided, revisit
when volume justifies it.

---

## 6. Guardrails

### The provider is the moderator

An earlier draft proposed classifying images and dropping anything NSFW. That was
wrong, and the reasoning generalizes to every future filtering idea.

The corpus is not the network. It is *this reader's ranked view* of the network,
assembled by a trust provider they chose and can change. If their provider ranks
adult content highly for them, that is the system working. Interposing our own
classifier would make us the arbiter of a feed whose entire selling point is that
the arbiter is theirs — invisibly, so the reader would never know their paper had
been edited on their behalf. **We honor the lens, including the parts we would
not have chosen.**

Two narrow things this does not excuse:

- **Content that is illegal to store anywhere.** Not a moderation preference. The
  exposure is small: published pages live on the user's Blossom servers, and we
  no longer fetch image bytes at all. Keep a kill switch, not a policy.
- **Honoring what authors asked for.** A `content-warning` tag is the author's own
  signal. Pass it through so the page carries the warning the poster wrote —
  suppressing it would be as much of an override as filtering.

### Two injection vectors, one closed

The system prompt is fixed, hidden and unmodifiable by users, which closes the
user-as-attacker vector cleanly. The harder half is unchanged: **every note in
the corpus is written by someone who is not the reader.** In order of how much
they carry:

- The sanitizer — the worst outcome is a bad headline, never an executing page.
- Corpus text arrives in a delimited block, framed once in the fixed prompt as
  third-party content that is never an instruction.
- The quote validator catches the most damaging output, because an injected story
  generally cannot cite real events verbatim.
- Operator instructions mid-run go in a `{"role":"system"}` message in
  `messages[]` — the injection-safe operator channel — never spliced into content.
- A page that trips the validator is never offered for publication.

### Quotes must be real

Credibility rests entirely on the quotes being verbatim, and **once the reader
signs and uploads, we cannot retract anything.** Normalize whitespace, then
require every quoted span to be a substring of a source event and every
attribution to match that event's pubkey. Figures too.

### Attribution and relay citizenship

- Every quoted item links back to the event and names its author.
- An author opt-out removes someone from *future* editions. It cannot reach
  published ones — those are on other people's servers under other people's keys,
  which is the deal Nostr makes and should be stated plainly.
- **Do not run this against `search-staging`.** It is shared and live, and the
  relay repo's guidance is explicit that it should be read, not written to and
  not hammered. This service needs its own Vespa deployment.

---

## 7. Publishing: the paper is theirs

Putting editions on the reader's own Blossom servers as an nsite resolves the
addressing problem by dissolving it, moves storage and retention to the person
who owns the content, and makes "delete my paper" a thing the reader can actually
do without asking us.

### Use the current kinds

Most nsite material in circulation describes **`kind 34128`**, one event per file
with a `d` tag for the path. **That is deprecated.** NIP-5A defines a single
manifest event instead: `kind 15128` for a pubkey's root site (no `d` tag),
`kind 35128` for a named site under that pubkey.

```json
{
  "kind": 35128,
  "tags": [
    ["d", "observer"],
    ["path", "/index.html", "<sha256 of the latest edition>"],
    ["path", "/2026-08-17", "<sha256>"],
    ["path", "/2026-08-16", "<sha256>"],
    ["server", "https://blossom.example.com"]
  ]
}
```

One replaceable event carries the whole archive. Each day adds a path tag and
repoints `/index.html`. A host resolving a request prefers the manifest's own
`server` tags, falls back to the pubkey's `kind 10063` BUD-03 list, and 404s if
there is neither — which is why the storage readiness chain checks for a 10063
up front.

### What we publish

1. The reader approves the previewed edition.
2. We produce the final blob — one small HTML file, art referenced at the URLs
   its authors published — and compute its sha256.
3. Their signer produces a `kind 24242` authorization with a `t` tag of `upload`;
   we `PUT` the blob to each server in their 10063 list.
4. Their signer produces the updated `kind 35128` manifest; we publish it to
   their write relays.
5. We store the address and the hash. Nothing else.

### Art is hotlinked

An earlier draft argued for inlining every image as a `data:` URI. That was
over-engineering against a threat model that does not apply: **this is public
Nostr data, and media servers exist to serve it to whatever client asks.** Blossom
hosts already field requests from every Nostr client in circulation.

What hotlinking deletes is substantial: no fetching, no resizing, no EXIF
handling, no image library on the server at all, and no megabyte-per-edition
blob. Pages land around 30–60 KB. The art shortlist becomes a metadata pass over
`imeta` tags we already have.

Two consequences to accept rather than solve. An author who deletes a blob leaves
a hole in an old edition — which is what a link is. And a reader viewing an
edition discloses their IP to each host serving it, exactly as browsing any Nostr
client already does. Keep `alt` text from `imeta` so a missing image degrades to a
caption rather than a gap.

### The friction this buys, honestly

**Every publish needs two signatures** — the upload authorization and the
manifest. With a browser extension that is two prompts; with a remote signer it
is two round-trips through the NIP-46 path already flagged as awkward. A
*scheduled* edition has nobody present to approve anything, so autopublish needs
a persistent bunker connection consented to in advance. That is Phase 4. v1 is
"here is today's paper, publish it?" and a button.

### What we still store

The continuity record (masthead, motto, sections, recent headlines), an index of
published editions with their hashes and addresses, and unpublished drafts under
an unguessable id, scoped to the session that made them and expiring on their
own. No public URL space of our own, so nothing to crawl.

---

## 8. Stack

Kotlin, Quartz and Ktor — the same three the relay is built on. Quartz already
knows every event shape this project touches, and the readiness logic being
ported has a working reference implementation next door.

Yes, there has to be a server. The relay pull could run in a browser, but the
model call cannot — the API key has to live somewhere the reader cannot read it,
and "the system prompt is hidden and unmodifiable" is only true if the prompt
never reaches the client.

| Concern | Choice | Notes |
|---|---|---|
| Language | Kotlin 2.4.0 / JVM | Gradle with a version catalog, spotless + ktlint, git hooks — mirror the relay's setup |
| Nostr | Quartz | Event models, signature verification, relay client, filters, NIP-19, NIP-42 AUTH, NIP-46 remote signing. Pin on JitPack and `force()` it in every module — JitPack versions are commit hashes and Gradle resolves conflicts *lexicographically*. It is KMP with Android in its graph, so `settings.gradle.kts` needs `google()` |
| HTTP + sockets | Ktor 3.5.1 | Netty engine for the app; ktor-client for Blossom `PUT`. Outbound relay sockets via Quartz's `BasicOkHttpWebSocket`, as the router already does |
| Model | anthropic-java 2.34.0 | `AnthropicOkHttpClient.fromEnv()`; `.model("claude-opus-5")`; `.thinking(ThinkingConfigAdaptive)`; effort nests inside `OutputConfig`. **Use `createStreaming`** — a whole front page is a long generation and a blocking call will hit the HTTP timeout. Rides on okhttp, already in the graph |
| Sanitizer | jsoup | `Cleaner` + a custom `Safelist`. Two things it will not do: it does not parse CSS, so `@import` and `url()` need their own pass; and `<style>` plus the `style` attribute must be added to the safelist deliberately |
| Storage | SQLite (JDBC) | Continuity records, a published-edition index, expiring drafts. Numbered SQL migrations, as the relay does |
| Proof render | Playwright for Java | The one heavyweight dependency, and the only way to catch a layout that overflows or goes unreadable in dark mode. Defer past the first spike if it slows the container down |
| Images | none | Deleted by the hotlinking decision. No ImageIO, no thumbnailing, no EXIF |

Versions are the relay's current pins, quoted so the two projects start aligned.

---

## 9. Phases

**Phase 1 — Headless generator.** A CLI that takes an npub and prints a finished
edition to a file. No auth, no publishing, no web app. The whole pipeline through
the proof render, with the sanitizer and validator in from the first commit —
they define what the generator is allowed to be, so they cannot be bolted on
later. The prototype run is the golden fixture: the same 773 events must still
produce a page whose every quote validates. Add an adversarial fixture early — a
synthetic corpus carrying injection attempts, a mislabelled video and an invented
image id — asserted to produce a clean page.

**Phase 2 — Lens provisioning.** The 4.5% problem, and the only real external
dependency. Port `readiness.js`; build the onboarding that mints a provider
identity on the NosFabrica scoring service, has the user sign a `10040` naming
it, and then waits. Scoring is asynchronous, so design for the wait: a progress
panel driven by the readiness state machine. *(A **provisional edition** built
from the reader's `kind 3` follows was built here and later removed — see the
Phase 3 postscript for why.)*

**Phase 3 — Sign in, generate, publish.** NIP-07 and NIP-46 sign-in, the private
preview, the masthead continuity record, and the publish flow: 24242 upload,
35128 manifest, storage readiness check. This is where the mobile signer story
has to actually work.

**Phase 4 — Standing orders.** Scheduled daily runs against a consented bunker
connection. Optionally announce each edition as a `kind 30023` so it travels
through normal clients as well as the nsite.

---

## 10. Risks

| Sev | Risk | Response |
|---|---|---|
| 1 | Lens provisioning does not scale. One provider key per observer means onboarding is real compute, not a signature. | **Open, and now the only thing between a new reader and a paper** — the provisional fallback that used to cover the wait has been removed. Establish the true cost and latency of scoring one new observer. |
| 2 | A published page carries a fabricated quote or injected headline under a real person's name — signed by the reader, on their server, unretractable. | Validator gates the publish button, not just the render. Adversarial fixture in CI from Phase 1. |
| 3 | Signing friction kills the loop. Login is mandatory and publishing needs two more signatures; the NIP-46 mobile path is known-awkward. | Prototype the full mobile signer path in Phase 3 before building on it. Generate-then-publish, never publish-to-see. |
| 4 | No shareable editions exist at launch, so a stranger has nothing to look at. | Seed real editions from consenting accounts. The shared paper is the only demo now. |
| 5 | Free-form generation produces a broken page and there is no template to fall back on. | **Built.** The proof render runs inside `Press.edition`, before anything is offered: render, regenerate once, then drop the author's stylesheet for the house layout. It found the real failure it was written for — an edition that shipped with no stylesheet at all and passed every other check. |
| 6 | The reader's Blossom server is down or drops blobs; hotlinked art rots independently. | Keep our own copy of the page; write to every server in their 10063. Accept art rot — carry `alt` text so it degrades to a caption. |
| 7 | Thin graphs produce embarrassing papers for exactly the new users we most want to impress. | Sections are earned, not fixed — a short honest paper beats nine empty columns. |
| 8 | Uncertain: the store's reputation tensor has no self-edge, so a reader may score 0 under their own lens — yet the prototype returned 14 of the observer's own posts. | **Answered the other way.** The reader's own posts DO rank, highly, and that turned out to be the problem rather than the question: a paper is what other people did today. `Pull.belongs` drops them from every desk and keeps them in the control run. Still worth verifying across several observers that a reader with a thin graph is not scored 0 outright. |

Retired along the way: unbounded windows (fixed at 24h), NSFW misclassification
(we no longer classify), enumerable public URLs (no public URL space of our own),
and the image-storage question (we no longer store images).

---

## 11. Open questions

- **Is the NIP-85 scoring service ours to extend?** The plan assumes
  `nip85.nosfabrica.com` can onboard new observers on demand. If not, Phase 2 is
  the critical path and the timeline changes.
- ~~**Named site or root site?**~~ **Answered, and further than asked.** One
  `kind 35128` PER EDITION, `d: "observer-<date>"`. A single site holding every
  day as a path meant each publish replaced the event carrying the archive, so a
  bad read deleted the back catalogue; separate sites replace nothing, and
  removing one day becomes a plain NIP-09 deletion.
- ~~**Do we keep a copy of published editions?**~~ **No.** The table is gone. The
  reader's own site events are the archive, on their own relays, and we read
  them back to list it.
- **What happens on a day with nothing in it?** A fixed 24-hour window makes quiet
  days possible. The paper should say so — a thin single-column edition is honest,
  and four columns of filler is the one thing that would make it feel generated.
- ~~**License.**~~ MIT, in `LICENSE`.

---

## Provenance

The 11-of-244, 302 10040s, 276 provider keys and 209-of-400 figures were measured
against `wss://search-staging.brainstorm.world` on 17 August 2026 while building
the prototype edition. They are readings taken at a moment on a live, moving
system — re-measure before committing to any of them.

nsite kinds and resolution order from NIP-5A. Blossom upload authorization
(`kind 24242`, `t` verb `upload`) from BUD-01/02; the user server list
(`kind 10063`) from BUD-03. The deprecation of `kind 34128` in favour of
`15128`/`35128` is called out because most tooling and write-ups still describe
the old shape.

---

## Postscript: what Phase 2 measured

Building the readiness probe corrected four things this plan had guessed at. All
four are in `AGENTS.md` with their date.

1. **The search relay holds no kind 3.** A follow-list lens built against it
   degrades to "just the reader" for everybody, silently — it did exactly that on
   the first live run and looked like a reader with no friends. Follow lists had
   to come from the reader's own write relays, discovered through the kind 10002
   the store *does* mirror. *(Moot now: that lens has been removed. Kept as a
   measured fact about the store, which is still true.)*
2. **NIP-45 COUNT answers** on both the search relay and the provider relay, so
   link 3's import percentage is measured rather than guessed. For the prototype
   observer: 149,171 of 149,266 cards, 99.9%.
3. **A NIP-50 search without `since` times out.** The first ranked probe left it
   off; both sides came back empty, `Readiness` correctly read that as a quiet
   window, and link 4 passed vacuously while testing nothing.
4. **There is no provisioning API.** Both scoring hosts are plain strfry relays.
   `LensRequest.Provisioner` is the seam for when one exists; `Manual` is the
   honest implementation until then, and it says a person is involved rather than
   pretending to queue something.

The provisional lens works: a reader with no `kind 10040` at all now gets 1,015
follows and 7,449 vouched-for strangers, capped to 600 authors, and a real paper.
*(Removed in Phase 3 — see the last postscript.)*

---

## Postscript: the quartz migration

The table above names Quartz for "event models, signature verification, NIP-42
AUTH, NIP-46 remote signing", and Phase 1 shipped without it — roughly 400 lines
of hand-rolled bech32, websocket plumbing and NIP-01 dispatch, including an AUTH
frame learned the hard way by watching a COUNT come back empty. Quartz was in the
version catalog and in nobody's imports. `Bech32.kt`, `RelayClient.kt` and
`Event.kt` are gone; `nostr/Relays.kt` and `nostr/Tags.kt` are what was actually
ours.

The migration paid for itself immediately by surfacing a bug the hand-rolled
client had been hiding. It returned one list per filter; `fetchAll` returns one
merged list, so desks are recovered by kind — and the anonymous control run is
kind 1 exactly like the notes desk. Merged, the 400-post control run was being
filed as news. The instrument panel's whole claim is the difference between those
two sets, and the CLI now prints the overlap on every run so a regression is
visible before the model is called. Measured after the fix, for the prototype
observer: 744 events from 244 people, and **1 of the 400 anonymous notes also in
the paper** — the same figure the prototype edition was built on.

The second find was a REQ 353 KB long. Nine desks each carrying 600 author
pubkeys exceeds the 262144-byte `max_message_length` the relay advertises, and an
oversized frame is dropped with no NOTICE and no CLOSED, so that edition
came back with zero events while every one of those queries answered normally on
its own. `Relays.batches` splits a filter list under a budget and throws on a
single filter too big to send, because the alternative to a stack trace here is a
blank page an hour later.

Two quartz behaviours needed local guards rather than trust, both pinned by tests
and recorded in `AGENTS.md`: `decodePublicKeyAsHexOrNull` decodes an nsec into
the hex of the secret key, and `AdvertisedRelayListEvent.writeRelays()` returns
whatever scheme the tag carried.


---

## Postscript: what Phase 3 built

The web app is in `server/`: a Ktor app, a SQLite file, and three static files
of console. The generation pipeline moved into `Press` in the generator module
so the CLI and the web app run the SAME steps in the same order — anything else
means a `--dry-run` verifies something a reader never gets.

**Sign-in is NIP-98, for both signers.** A signed event over this exact request,
verified by quartz. It is deliberately the same shape whether a browser
extension or a phone signed it, which keeps the awkward part of NIP-46 in the
transport rather than in a second sign-in protocol.

**NIP-46 runs on the server.** This was the plan's open risk and it needed a
decision rather than a prototype. A browser implementation needs secp256k1 ECDH
that WebCrypto does not provide, and the failure mode the corpus itself
describes — mobile browsers dropping websockets when the tab is backgrounded —
happens precisely while the reader is switching to their signer app. A
connection held by the server does not get backgrounded, and holding one is what
Phase 4 needs regardless. The cost is real and is written down where it happens:
for the life of a session, this process can ask the reader's signer to sign the
three kinds it asked permission for.

**The server holds no key.** It builds the two events a publish needs and checks
what comes back. `Countersign` rejects three separate things, and only the first
is obvious: an invalid signature, a valid signature from somebody else, and — the
one that matters — a valid signature from the right reader over different tags.
Without that third check the template would be decoration, and a client could
have us upload a blob we never saw under a signature we did verify.

**The masthead loop is closed.** `Masthead` reads the `<!-- masthead: ... -->`
announcement out of the model's raw output (the sanitizer drops comments, so by
the time the page is safe to serve the announcement is gone) and stores it for
tomorrow. That makes it the one path from today's corpus to tomorrow's prompt —
a one-day-latency injection channel — so what is stored is capped to a name,
flattened to a single line, and stripped of markup.

Still not exercised: the model call. There is no `ANTHROPIC_API_KEY` in the dev
container, so a live run reaches "Writing your front page" and fails with a 401
from Anthropic, cleanly, as a FAILED draft. Everything before it is verified
live — a fresh key signs in, the readiness chain answers `no-relay-list` for it,
and a job for the prototype observer read 742 posts from 247 people with 1 of
400 control notes overlapping.

---

## Postscript: removing the provisional lens

Built in Phase 2 for the 11-of-244 finding, removed on 18 August 2026. Written
down because the reasoning that justified it was sound and what changed was the
product around it, not the measurement.

**Login was decided after it.** The provisional lens was designed when anyone
could request any npub's paper, so a stranger with no lens could arrive cold and
had to see *something*. That person no longer exists: generation requires a
sign-in, and since minting a lens is an operator step anyway, a reader without
one is already someone a human is about to onboard.

**It showed the wrong product first.** It was recency over follows and
follows-of-follows, and its own KDoc said it was "worse and honest about it".
The thesis of this project is the gap between the ranked view and the unranked
one — 1 of 400 events overlapping. The one measurement taken of the provisional
path put that overlap at **0 of 400**: not a weaker version of the claim, just
not the claim. A first-time reader would have formed their impression from the
one edition that cannot show what the product is for.

**It was the most expensive thing in the tree per unit of value.** It brought
`Follows` and its two-hop vouching, the `authors` branch in `Pull`, a CLI flag,
a checkbox, a refusal state, and the REQ-size splitting in `Relays` — nine desks
× 600 pubkeys was the only thing here that ever built a 353 KB frame. It also
read up to 120 strangers' follow lists off other people's relays, which is the
cost this project is otherwise most careful about.

What replaces it is what was already there: the readiness chain says which link
is unmet, in a sentence written for a person, and `Press` refuses with
`NO_LENS`. The console disables the button and says we will tell them when the
lens is ready.

`Relays.batches` was kept. The relay's 262144-byte cap is real and exceeding it
fails silently, so the guard outlives the caller that found it — but nothing on
a live path now approaches it, and its tests are the only thing exercising it.

This makes open question #1 — whether `nip85.nosfabrica.com` can onboard
observers on demand — the whole critical path. There is no longer anything
between a new reader and an empty screen except that service.

---

## Postscript: what came out after the provisional lens

Three smaller removals, plus one thing that turned out to be half-built rather
than dead.

**`GET /api/archive`** had no consumer. The console never called it, and it
answered in a borrowed type — `Outcome` is a publish RESULT, and it was carrying
archive rows with the day and the address crushed into one string and `ok`
hardcoded to true. `Published.of` still holds the data; the endpoint can be
written to fit the archive view on the day that view exists.

**`LensRequest.Provisioner`** was an interface with a single implementation that
returned null, exercised only by a test. It was a seam for the day the scoring
service grows an API — an API whose existence is this plan's open question #1.
A guess about a shape nobody has seen is not a seam. `LensRequest.EXPLANATION`
survives, because a reader waiting on an operator deserves a sentence.
`template()` went the same way a commit later, and with it `DIMENSIONS`, its
only other user. The argument for keeping it was that it encoded the `kind
10040` shape — but an unsigned event builder that no flow calls is a guess about
the onboarding flow too, and NIP-85 will still be there to read when somebody
writes one. What was left of the file was a single sentence explaining the wait,
which moved to `Readiness`, where the rest of the reader-facing copy already
lived.

**The motto could never change.** It was a database column, a `Continuity`
field, a line of prompt and a `remember()` parameter — and `Masthead.next`
copied it from yesterday unconditionally, while the system prompt defined a
`<!-- masthead: -->` announcement with no motto equivalent. The brief in §5 was
that the standing phrases stay softly in place and MAY move for a large enough
day; only the first half was built. The prompt now documents
`<!-- motto: ... -->` alongside the masthead, and `Masthead` reads it back under
the same bounds — capped to a phrase, flattened to one line, stripped of markup,
because it is the same one-day-latency channel from today's corpus into
tomorrow's instructions.

---

## Postscript: video, and what the desks are for

The nine desks were reviewed for removal and kept — they earn their place — and
video was added beside them.

**The spec and the network disagree, and the network wins.** NIP-71 moved video
to `kind 21` and `kind 22`, deprecating `34235`/`34236`. Measured through the
prototype observer over one 24-hour window at a trust floor of 20: the current
kinds returned **nothing at all**, and the deprecated ones returned 6 and 37
events. A desk asks for both. This is the exact mirror of the nsite decision in
§7, where the deprecated `kind 34128` was the wrong choice and the current
`15128`/`35128` was right — so the lesson is not "prefer old" or "prefer new",
it is that the answer is a measurement.

Video also made a desk span more than one kind for the first time, which is only
safe because of the per-desk fan-out: while the desks shared one REQ, results
had to be recovered by kind, and two desks claiming one kind is precisely the
collision that filed the anonymous control run as news.

**The page cannot play video and never will** — an edition is a static file on
somebody else's media server. So the paper treats a video the way a newspaper
treats a film: it writes about it, prints a still if there is one, and gives the
running time. The prompt says so, and `DURATION` is now in the digest because it
is the one fact a video's body text never carries.

Two things had to be fixed before a still could actually appear. The poster is
`imeta`'s `image` field, and it must NOT be sniffed: every real one measured was
extension-less, so an `isImage` check rejected six of seven, and one real event
carried a poster with no mime at all — the event's kind is the fact that holds.
And the art shortlist was allocated first-come in corpus order, so notes and
picture posts consumed all forty slots before video was reached; each desk now
takes a few up front and rank order fills the rest. That is the same
first-come starvation still outstanding in the digest's character budget.
