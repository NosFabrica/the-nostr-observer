# observer-runner: the Brainstorm Assistant's print worker

Prints the Observer for readers whose schedule names their Brainstorm Assistant as its runner
(NIP-FB, "Runner"; Quartz `quartz/plans/2026-11-11-observer-site.md`, "The Assistant as
runner"). It runs on Brainstorm's servers, beside brainstorm_server, as its own container.

**It holds no nsec.** Every decryption and every signature is brainstorm_server's, through
`/internal/observerRunner/...`, and the server answers only for the owner's own Assistant.

## What one poll does

1. Pages `GET /internal/observerRunner/assistants` for every (owner, Assistant).
2. Reads kind 34817 `d=observer` **by author** (the owners, 500 a REQ, never `#p`) from the
   index relays, then, for the owners the index shows a schedule for (and hourly for the
   rest), from each owner's NIP-65 write relays too, 16 relays at a time. It keeps each
   owner's newest. A `#p` read would never see the version an owner published to take printing
   back, and a relay still holding the older one would keep the worker printing.
   A relay answered for an owner only when it sent that owner's schedule, or reached EOSE on a
   page that wasn't full: each REQ sets `limit` to its number of authors, and a full page (or
   one of 100 or more, the shortest page a relay is assumed to cut to without saying so) has its
   missing authors asked again in chunks half the size. A CLOSED is asked again the same way,
   down to 25 authors.
3. Every event it reads must verify (id and signature) and be dated at most 10 minutes ahead,
   or it is ignored: anyone can hand a relay an event with the owner's or the Assistant's
   pubkey on it. Every relay is asked on its own subscription and gets at most 20 s and 2,000
   events for one read, whatever it sends; past either it answered for nobody. (Quartz's own
   window is an idle one, which a relay streaming junk would reset forever.)
4. Requires that newest version to carry exactly one public `p`, the owner's Assistant
   (`ScheduledTaskClaims.checkRunner(event, assistant, owner)`: the author must be the owner the
   server maps to that Assistant). No `p`: the owner took printing back (`NOT_NAMED`). More than
   half of the owner's write relays must have answered the read (`NO_QUORUM` otherwise).
5. Has the server decrypt the schedule (`decrypt` with the whole signed event; the plaintext is
   kept by event id), skips one with a param it doesn't understand (`ObserverParams.understands`:
   `model`, `key` and `effort`),
   and decides with `ScheduledTaskClaims.decideAsRunner`: due, not paused, after the time it was
   set, not already started here, no live claim by one of the owner's devices
   (`CLAIMED_BY_DEVICE`).
6. Reads both sites, the owner's and the Assistant's (`ObserverSite.runnerSiteFilter`), and
   skips an occurrence `ObserverSite.alreadyPrinted(readerEditions, runnerEditions, …)` finds.
   If the Assistant's own site can't be read (none of **its** relays answered; the index
   answering doesn't count) it doesn't print, since a publish would start from nothing.
7. Checks what a print needs before spending: the owner's Blossom servers (kind 10063), the
   Assistant's write relays, the owner's public rank provider (kind 10040).
8. Reads the Assistant's own kind 12000 with the server's majority rule (more than half of the
   Assistant's relays must answer, `NO_QUORUM` otherwise), has the server decrypt it, sending
   the schedule being printed beside it (`schedule`), picks the key with `ObserverParams.key`
   (the `key` param: URL + last 4 characters) and the model with `ObserverParams.model`, else
   the best model the endpoint lists (asked for at most 20 s). When the `key` param names an
   endpoint the Assistant's list doesn't have, the run fails with `KEY_NOT_GIVEN`, with or
   without a `model` param: the reader chose to spend there, and no other provider's key is used
   instead. Nothing is spent, and it is looked at again only when the Assistant's kind 12000
   changes.
9. Refuses a key URL that is loopback, private or internal (`REFUSED_URL`) or a Tor onion
   (`UNREACHABLE_ONION`: the worker has no Tor, and never resolves a `.onion` name), and never
   tries another key. For a Routstr key, checks its balance first (below).
10. Reads the owner's schedule once more, with the same quorum, right before spending: if it
    changed (`SCHEDULE_CHANGED`) the next poll decides on the new one.
11. Prints with the engine (`generator/`, Amethyst's press and page checks), at most 438 s
    (what Amethyst's scheduled run leaves a device's print), four owners at a time. A print is
    a plan call, then the page from the digest and the plan's posts in full, then up to three
    repairs; a plan call that fails (an HTTP error, a cut-off, a timeout) leaves one pass from
    the digest alone. Every call carries `max_tokens`, and the schedule's `effort=deep` asks for
    the provider's high reasoning effort. The page's citations and bylines are Brainstorm's,
    `https://brainstorm.world/e/<nevent|naddr>` and `https://brainstorm.world/p/<npub>`. The
    writer is told the edition's number (the distinct days on both sites and in the run
    records, plus one), the last five headlines from both sites' `editions.json`, the masthead,
    motto and sections the newest page announces, and the date and time on the schedule's zone.
12. Uploads the page, `archive.html` and `editions.json` to the **owner's** Blossom servers only,
    each with a kind 24242 the server signs (`t=upload`, one `x`, 5 minutes to expiry, no `p`).
13. Publishes the **Assistant's** Observer site (35128, `d=observer`, `["p", owner]`), signed by
    the server, to the Assistant's write relays. What it publishes is the event the server
    returned (the server replaces the `client` tag), checked to verify and to be the
    Assistant's site for that owner. The edition is dated by the occurrence it is for, on the
    schedule's calendar, never by when the print finished.

A run that reached the model is recorded as ended and never run again on its own, whatever came
of it: it may have spent the Assistant's money. A run that stopped before (the ranking not
projected yet) is forgotten and tried again on the next poll. The records are a JSON file
(`OBSERVER_RUNNER_STATE`), written with an fsync and an atomic rename before each print starts;
keep it on a volume. A start that can't be written down isn't a start: nothing is printed
(`RECORDS_UNAVAILABLE`). A records file that doesn't parse is never read as empty (that would
forget every morning in it): the worker prints nothing until an operator fixes or moves it.

Every printed paper is written to `pending/` beside the records BEFORE it is published, one
file per (owner, occurrence), and removed once it is out: a worker stopped mid-publish still
has it. One that didn't publish (Blossom down, a relay refusing, `sign` failing) is published
again on later polls for 12 hours (`PUBLISH_PENDING`), never printed again, and only while the
owner's schedule still names the Assistant. An older paper still waiting goes out with a newer
one, in the same site event; it is never replaced by it.

If brainstorm_server refuses the runner's token in the middle of a poll, no new run starts, but
the runs under way finish (each may already have paid for a page), and are recorded and kept.

## Routstr keys

A key is a Routstr key only when its own URL says so: its `/v1/models` lists `sats_pricing`, or
its `/v1/info` answers with an `npub` and `mints` (Amethyst's rule). Only custom `https` `/v1`
endpoints are asked, never another provider's preset, and the answer is kept per endpoint. The
worker reads no kind 38421: every call goes to the key's own URL, never to an endpoint an
announcement names.

- **Before printing**, `GET /v1/balance/info` with the key. Under twice the model's
  `sats_pricing.max_cost`, or under one `max_cost` plus Amethyst's estimate of the edition in
  sats (`RoutstrPricing.editionSats`: the plan and the page over a full digest, Deep's reasoning
  when the schedule asks for it), whichever is more, there is no print (nothing when the node
  gives neither): `INSUFFICIENT_BALANCE`, nothing spent, looked at again
  hourly that occurrence. A balance the node doesn't give lets the print go ahead.
- **During the print**: a 402 is `INSUFFICIENT_BALANCE`, a 503 `payment_settlement_failed` is
  `CHARGED_NO_ANSWER` (the node charged and withheld the page), both ended and never resent. A
  424 before any answer (the node's own provider down) spent nothing: it is forgotten and tried
  again hourly (`UPSTREAM_UNAVAILABLE`). After the first answer, any failure is ended.
- An `.onion` node is `UNREACHABLE_ONION`.

## Consent

The worker publishes the Assistant's public Observer site for an owner whenever that owner's
own signed schedule names the Assistant. It asks nothing else: **consent stays with
Brainstorm**, which gets the reader's consent to a public site before it ever names the
Assistant in their schedule (Amethyst can only clear a runner, never name one). An owner who
takes printing back publishes a schedule without the `p`, and the worker stops at its next poll
that reads it (step 4), a pending paper included.

## Cost per poll

For N owners with an Assistant, S of whom have an Observer schedule and D of those with an
occurrence due and not yet settled:

- `assistants`: ceil(N / 500) calls to brainstorm_server.
- Schedules: ceil(N / 500) REQs per index relay; then, per write relay of the S owners (and
  hourly of the N − S), ceil(owners on it / 500) REQs. NIP-65 lists: ceil(owners / 500) REQs
  to the index, at most hourly per person (cached).
- Decrypts: one per new schedule version (cached by event id).
- Per D only: the two sites (one REQ each, over the owner's or the Assistant's relays plus the
  index), the editions file from Blossom, the 10063, 10040 and kind 0, the 12000 and its
  decrypt, and the re-read of the schedule; for a Routstr key, its `/v1/models` (or `/v1/info`
  once) and `/v1/balance/info`. An occurrence that printed, ended, failed or is already on a site
  is settled until the next one is due, so it costs nothing more; one the owner can fix (no
  10063, no 10040, no key, no model, a low balance, a node's provider down) is looked at again
  hourly. One that turned on the Assistant's keys (no key, `KEY_NOT_GIVEN`, a refused URL) costs
  one read of its kind 12000 a poll, to see whether the owner changed it.

So a steady poll with nothing due is about 2 × ceil(N / 500) REQs plus the owners' own relays,
whatever N is.

## Never logged

The AI key, any decrypted schedule or key list, any ciphertext. Lines carry the owner, the
outcome (`RunOutcome`) and a detail of the worker's own (an enum name, a count, a refusal
reason). A writer failure is logged by its kind and HTTP status only, since a provider's reply
can echo the request. The tests check this on captured logs.

## SSRF

The worker is inside Brainstorm's network, and every URL it dials for a reader came from that
reader: the AI key's endpoint (NIP-FA allows `http` to loopback), their Blossom servers, their
relays. So all of them go through `guardedClient` (`SafeHttp.kt`):

- the URL: `https` (`wss` for relays) only; no address literal that is loopback, unspecified,
  RFC 1918, link-local, shared (100.64/10), benchmark, reserved, multicast or IPv6 ULA (IPv4
  inside IPv6 judged by its IPv4); no internal-only name (`localhost`, single labels, `.local`,
  `.internal`, `.svc`, `.cluster.local`, …); no credentials in the URL. A host whose last label
  is a number is an address, read as `inet_aton` reads it (`127.1`, `10.1`, `0x7f.1`,
  `2130706433` and `017700000001` are all internal), and one no resolver reads as a single
  address is refused;
- every address a name resolves to, at connect time (`GuardedDns`), so a DNS rebind is caught:
  one internal address in the answer refuses the name;
- every socket checks the address it connects to (`GuardedSocketFactory`), whichever path got
  there: OkHttp never asks `Dns` about a host that looks like an IPv4 literal;
- bodies are bounded (`BodyCapInterceptor`): 32 MB for an answer, which bounds a streamed page,
  16 KB for an error body. A call to the AI endpoint never outlives the print's 438 s;
- a reader's relay socket reads at most 16 MB per 10 s (`ReadBudget`). OkHttp has no limit on an
  inbound WebSocket message and buffers a whole one before handing it on, so this is what bounds
  one relay's memory; the index relays, Brainstorm's own, aren't held to it;
- `.onion` names are refused before any resolver sees them (the cluster's DNS would pass them on);
- no redirects at all, and no proxy (through a proxy, the proxy resolves the name and the
  address check would never see it). Outbound HTTPS from the worker must therefore be direct.

brainstorm_server itself is reached with a separate, unguarded client.

## Configuration

| Variable | Default | |
|---|---|---|
| `BRAINSTORM_SERVER_URL` | (required) | brainstorm_server's cluster address, scheme and host only, e.g. `http://brainstorm-server`. Never the public host |
| `OBSERVER_RUNNER_PORT` | `8001` (or the URL's own port) | brainstorm_server's internal API port, the same as its `OBSERVER_RUNNER_PORT`. Port 8000 (the public API's) is refused at startup |
| `OBSERVER_RUNNER_TOKEN` | (required) | the same value as brainstorm_server's `OBSERVER_RUNNER_TOKEN`, at least 32 characters (`openssl rand -hex 32`). A secret: from a k8s Secret, never in a manifest |
| `OBSERVER_RUNNER_POLL_SECONDS` | `300` | seconds between polls (at least 30) |
| `OBSERVER_RUNNER_GRACE_SECONDS` | `498` | on SIGTERM, how long the print under way may still take |
| `OBSERVER_SEARCH_RELAY` | `wss://search.brainstorm.world` | the ranked relay the press reads |
| `OBSERVER_INDEX_RELAYS` | the search relay (Brainstorm's index relay is the same) | comma-separated; where schedules are looked for first, and where relay lists, 10063, 10040 and kind 0 are found. Public relays only |
| `OBSERVER_RUNNER_STATE` | none (memory) | the run records file; the image sets `/var/lib/observer-runner/runs.json`. Pending papers go in `pending/` beside it, the lock in `runs.json.lock` |

## Deploying beside brainstorm_server

    docker build -f runner/Dockerfile -t observer-runner .

- Run it on the same internal network as brainstorm_server, as its own Deployment with one
  replica and **`strategy: type: Recreate`**: a rolling update would run the old and the new
  pod at once, and both would print the same morning. No Service and no Ingress: nothing
  connects to it.
- It takes an exclusive lock on `<state>.lock` at startup and exits (status 3) when another
  process holds it, so two workers on one volume can't run at once whatever the strategy. The
  lock is a file of its own because the records file is replaced by rename on every write.
- On SIGTERM it starts no new run (a run waiting for its turn doesn't start either) and lets
  the print under way finish, for up to `OBSERVER_RUNNER_GRACE_SECONDS`. Set the pod's `terminationGracePeriodSeconds` above that
  (for example 540).
- `/internal/observerRunner/...` is served only on brainstorm_server's internal port
  (`OBSERVER_RUNNER_PORT`, 8001); nothing the ingress routes to may expose that port. The
  public port answers 404 to every `/internal/` path, token or not.
- Give both the same `OBSERVER_RUNNER_TOKEN` from one Secret. With it unset or shorter than 32
  characters on the server, the internal API is off and every call is a 404; the worker logs
  that the token is refused.
- Mount a volume at `/var/lib/observer-runner` (ReadWriteOnce).
- Egress: HTTPS to the reader's AI endpoints and Blossom servers, WSS to relays, directly (see
  SSRF above), and HTTP to brainstorm_server's internal port inside the cluster.

## The contract

The HTTP shapes, as brainstorm_server builds them (its `app/routers/observer_runner/`), at
`http://<brainstorm_server>:<OBSERVER_RUNNER_PORT>`, with no trailing slashes: every response
is `{code, data, message}`; auth is `Authorization: Bearer <token>`.

- `GET /internal/observerRunner/assistants?after=<owner hex>&limit=500` →
  `{items: [{owner, assistant}], next: <owner hex> | null}`.
- `POST /internal/observerRunner/decrypt` `{owner, event, schedule?}` (whole signed events) →
  `{plaintext}`. Only a kind 34817 by the owner with exactly one `p`, that owner's Assistant
  (decrypted with the owner as peer), or a kind 12000 by the Assistant (itself as peer), which
  opens only with `schedule`: the owner's signed 34817 being printed, checked the same way.
  Anything else is 403. There is no bare-ciphertext mode.
- `POST /internal/observerRunner/sign` `{owner, event: {kind, created_at, tags, content}}` →
  the signed event, by the owner's Assistant. Only 35128 with exactly one `d=observer` and one
  `p` = the owner, and 24242 with `t=upload`, an `expiration` at most 10 minutes ahead in ASCII
  digits and no `p` naming the Assistant; `created_at` at most 10 minutes ahead. Every other
  kind or shape is 403. The `client` tag is replaced with `["client","Brainstorm"]`.
- An owner without an Assistant: 404 with `X-Reason: no-assistant`. A wrong or unset token: a
  plain 404.
