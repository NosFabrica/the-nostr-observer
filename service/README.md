# observer-service: the Brainstorm Assistant's Observer task service

Prints the Observer for readers whose schedule names their Brainstorm Assistant as its runner
(NIP-FB, "Runner"; Quartz `quartz/plans/2026-11-11-observer-site.md`, "The Assistant as
runner"). It runs on Brainstorm's servers, beside brainstorm_server, as its own container. It is
the task service [assistant-runner](https://github.com/NosFabrica/assistant-runner) wakes: the
runner finds a due occurrence and sends `POST /v1/run`; this service does the whole job.

**brainstorm_server is never called.** The service reads the Assistant's key itself, from the
server's `brainstorm_nsec` table and Fernet key file (its own `keys` package: `Fernet`,
`FernetKeyFile`, `AssistantKeys`), decrypts and signs as the Assistant with the same allowlist the
server enforced when it did that work (`LocalAssistant`), and keeps its run records and unpublished
papers in its own `observer` schema of the same Postgres (the `state` package: `Migrations`,
`RunStore`, `PendingStore`).

**It shares no code with the runner that wakes it**, and depends on nothing of it. The two share only what
both talk to: the wake and its JSON (the vectors, copied from the runner's `contract/v1/vectors/`
into `src/test/resources/contract/v1/vectors/`), the four `brainstorm_nsec` columns read with the
server's `_plaintext_nsec` rule, and the Fernet key file. The runner reads none of this service's
tables: it learns that an occurrence is settled from the `200` this service answers.

## What one wake does

`POST /v1/run` `{"v": 1, "task": "observer", "owner": "<64-hex>", "occurrence": <unix s>}`
(the trigger contract, v1: assistant-runner's `contract/v1/`; parsed here by this service's own
`WakeRequest`).

1. **The answer**, at once:
   - `400 {"why": …}` for a body that isn't a v1 wake (`WakeRequest.parse`), or a `task` that
     isn't `observer`;
   - `202` when a run for the owner is already under way here: the wake **joins** it, and no
     second print starts (`{"joined": true}`);
   - `200 {"outcome": …}` when `observer.runs` already has a row for that occurrence: an
     ended one answers with its outcome (`PRINTED`, `PUBLISH_PENDING`, `WRITER_FAILED`, …), a
     `started` one nobody here runs (another replica's, or one that died) with `ATTEMPTED`. One
     cheap read, no relay. The runner takes a `200` as settled and doesn't wake that occurrence
     again (until it restarts, which costs one more wake, answered the same);
   - `429` with `Retry-After: 60` when all `PARALLELISM` slots are busy, or the service is
     stopping;
   - otherwise `202` (`{"joined": false}`), and the owner's path runs in the background.

   The occurrence in the wake is a hint: the run decides the occurrence itself, from the
   schedule it reads.
2. **The Assistant.** The owner's row in `brainstorm_nsec`, its key opened with the Fernet key
   file. No row (or no `assistant_pubkey`): `NO_ASSISTANT`. A token no key opens, or an nsec
   that isn't the stored Assistant's: `KEY_UNREADABLE`. Nothing is read from a relay before.
3. **The schedule**, kind 34817 `d=observer` **by author** (never `#p`), from the owner's NIP-65
   write relays and the index relays, newest first. A `#p` read would never see the version an
   owner published to take printing back. None found: `NO_SCHEDULE`. More than half of the
   owner's write relays must have answered (`NO_QUORUM` otherwise); only the first 8 in their
   list, after vetting, are read (anyone's list, everywhere). The index relays stand in
   only for an owner an index relay answered for in full and found no NIP-65 list for: a list
   read that failed (CLOSED, a timeout, a cap), or a list whose every write relay is refused
   (`ws`, an internal host), is `NO_QUORUM`.
4. Every event it reads must verify (id and signature) and be dated at most 10 minutes ahead,
   or it is ignored: anyone can hand a relay an event with the owner's or the Assistant's pubkey
   on it, checked before copies are deduplicated (a forged copy under a real event's id can't hide
   it). Every relay is asked on its own subscription and gets at most 20 s and 2,000 events for
   one read (never fewer than the read's `limit`), whatever it sends; past either it answered for
   nobody. The NIP-65 lists are read in chunks of owners: a full page (counted as sent, before any
   check), or one the event cap cut off, is asked on rather than dropped; a CLOSED chunk is asked
   again in halves, never one person twice, and nothing more after `rate-limited:` or `blocked:`.
5. Requires that newest version to carry exactly one public `p`, the owner's Assistant
   (`ScheduledTaskClaims.checkRunner(event, assistant, owner)`). No `p`: the owner took printing
   back (`NOT_NAMED`). Two: `RUNNER_CONFLICT`.
6. **Papers already paid for go out first** (below), before anything else is decided.
7. Decrypts the schedule as the Assistant (the plaintext is kept in memory, by owner and event
   id), skips one with a param it doesn't understand (`ObserverParams.understands`: `model`,
   `key` and `effort`), and decides with `ScheduledTaskClaims.decideAsRunner`: due, not paused,
   after the time it was set, no `runs` row for the occurrence, no live claim by one of the
   owner's devices (`CLAIMED_BY_DEVICE`).
8. Reads both sites, the owner's and the Assistant's (`ObserverSite.runnerSiteFilter`), and
   skips an occurrence `ObserverSite.alreadyPrinted(readerEditions, runnerEditions, …)` finds.
   If the Assistant's own site can't be read (none of **its** relays answered; the index
   answering doesn't count) it doesn't print, since a publish would start from nothing.
9. Checks what a print needs before spending: the owner's Blossom servers (kind 10063), the
   Assistant's write relays, the owner's public rank provider (kind 10040).
10. Reads the Assistant's own kind 12000 with the same majority rule (`NO_QUORUM` otherwise),
    decrypts it beside the schedule being printed, picks the key with `ObserverParams.key` (the
    `key` param: URL + last 4 characters) and the model with `ObserverParams.model`, else the
    best model the endpoint lists (asked for at most 20 s). When the `key` param names an
    endpoint the Assistant's list doesn't have, the run fails with `KEY_NOT_GIVEN`, with or
    without a `model` param: the reader chose to spend there, and no other provider's key is
    used instead. It is looked at again only when the Assistant's kind 12000 changes.
11. Refuses a key URL that is loopback, private or internal (`REFUSED_URL`) or a Tor onion
    (`UNREACHABLE_ONION`), and never tries another key. For a Routstr key, checks its balance
    first (below).
12. Reads the owner's schedule once more, with the same quorum, right before spending: if it
    changed (`SCHEDULE_CHANGED`) the next wake decides on the new one.
13. **Claims the occurrence**: `INSERT … ON CONFLICT DO NOTHING` into `observer.runs`.
    Only the inserter prints, so two replicas (or a restart) never pay for one morning twice. A
    claim that can't be written is no start (`RECORDS_UNAVAILABLE`, nothing spent); one another
    process holds is `ATTEMPTED`.
14. Prints with the engine (`generator/`, Amethyst's press and page checks), at most 438 s
    (what Amethyst's scheduled run leaves a device's print). A print is a plan call, then the
    page from the digest and the plan's posts in full, then up to three repairs; a plan call that
    fails leaves one pass from the digest alone. Every call carries `max_tokens`, and the
    schedule's `effort=deep` asks for the provider's high reasoning effort. The page's citations
    and bylines are Brainstorm's, `https://brainstorm.world/e/<nevent|naddr>` and
    `https://brainstorm.world/p/<npub>`. The writer is told the edition's number (the distinct
    days on both sites and in the `runs` rows, plus one), the last five headlines from both
    sites' `editions.json`, the masthead, motto and sections the newest page announces, and the
    date and time on the schedule's zone.
15. **Stores the paper** in `observer.pending` (the edition's fields in `meta`, the page
    as a file) before its first publish.
16. Uploads the page, `archive.html` and `editions.json` to the **owner's** Blossom servers only,
    each with a kind 24242 the Assistant signs (`t=upload`, one `x`, 5 minutes to expiry, no
    `p`).
17. Publishes the **Assistant's** Observer site (35128, `d=observer`, `["p", owner]`), signed by
    the Assistant (with `["client","Brainstorm"]`), to the Assistant's write relays, checked to
    verify and to be the Assistant's site for that owner. The edition is dated by the occurrence
    it is for, on the schedule's calendar, never by when the print finished. Out: the paper's
    row is deleted.
18. **Ends the run** with its outcome (`PRINTED`, or `PUBLISH_PENDING` when the publish failed).

A run that reached the model is ended and never run again on its own, whatever came of it: it
may have spent the Assistant's money. A run that stopped before (the ranking not projected yet, a
node's provider down) forgets its row and is tried again on a later wake. A row left `started`
(the process died mid-run) is never taken up again: nobody can tell whether it spent.

An outcome the owner can fix (no 10063, no 10040, no key, no model, a low balance) writes no row,
so the runner keeps waking; for an hour (until the occurrence ends, for one that can't change)
such a wake reads the key and the schedule again but stops there, settled in memory, and one that
turned on the Assistant's keys is looked at again as soon as its kind 12000 changes.

## The papers still waiting

A paper that didn't publish (Blossom down, a relay refusing) stays in `observer.pending`
for 12 hours, never printed again. Every `PENDING_SECONDS` (300) the service drops the papers past
their time, then, one owner at a time in a free slot (never beside a run for the same owner),
publishes each owner's again (`ObserverRunner.republish`): only while the owner's newest schedule
still names the Assistant, with the same quorum and checks as a wake. An owner with no Assistant
any more has theirs dropped. An older paper still waiting goes out with a newer one, in the same
site event; it is never replaced by it.

## Routstr keys

A key is a Routstr key only when its own URL says so: its `/v1/models` lists `sats_pricing`, or
its `/v1/info` answers with an `npub` and `mints` (Amethyst's rule). Only custom `https` `/v1`
endpoints are asked, never another provider's preset, and the answer is kept per endpoint. The
service reads no kind 38421: every call goes to the key's own URL, never to an endpoint an
announcement names.

- **Before printing**, `GET /v1/balance/info` with the key. Under twice the model's
  `sats_pricing.max_cost`, or under one `max_cost` plus Amethyst's estimate of the edition in
  sats (`RoutstrPricing.editionSats`: the plan and the page over a full digest, Deep's reasoning
  when the schedule asks for it), whichever is more, there is no print (nothing when the node
  gives neither): `INSUFFICIENT_BALANCE`, nothing spent, looked at again an hour later that
  occurrence (settled in memory until then). A balance the node doesn't give lets the print go
  ahead.
- **During the print**: a 402 is `INSUFFICIENT_BALANCE`, a 503 `payment_settlement_failed` is
  `CHARGED_NO_ANSWER` (the node charged and withheld the page), both ended and never resent. A
  424 before any answer (the node's own provider down) spent nothing: it is forgotten and tried
  again on a wake an hour later (`UPSTREAM_UNAVAILABLE`). After the first answer, any failure is ended.
- An `.onion` node is `UNREACHABLE_ONION`.

## Consent

The service publishes the Assistant's public Observer site for an owner whenever that owner's
own signed schedule names the Assistant. It asks nothing else: **consent stays with
Brainstorm**, which gets the reader's consent to a public site before it ever names the
Assistant in their schedule (Amethyst can only clear a runner, never name one). A wake only
makes the service do what that schedule asks, which it reads and checks itself, so the endpoint
needs no token. An owner who takes printing back publishes a schedule without the `p`, and the
service stops at the next wake or retry pass that reads it (step 5), a pending paper included.

## Cost per wake

A wake answered from its `runs` row costs one indexed read of Postgres. Otherwise, for the owner:
their key's row and a Fernet decrypt, their NIP-65 list (cached an hour), one schedule REQ over
their write relays and the index, and a decrypt per new schedule version. An occurrence that is
not due, or settled in memory, stops there.

A due one adds: the two sites (one REQ each), the editions file from Blossom, the 10063, 10040
and kind 0, the 12000 and its decrypt, the re-read of the schedule, the claim; for a Routstr key,
its `/v1/models` (or `/v1/info` once) and `/v1/balance/info`. Then the print and the publish.

## Never logged

The AI key, the Assistant's nsec (plain or Fernet), the Fernet keys, any decrypted schedule or
key list, any ciphertext. Lines carry the owner, the outcome (`RunOutcome`) and a detail of the
service's own (an enum name, a count, a refusal reason). A writer failure is logged by its kind
and HTTP status only, since a provider's reply can echo the request. A database or IO failure is
logged by its class only. The tests check this on captured logs.

## SSRF

The service is inside Brainstorm's network, and every URL it dials for a reader came from that
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
- no WebSocket is compressed (`NoWebSocketCompression`): OkHttp offers `permessage-deflate` and
  inflates a whole message in memory, so a 65 KB frame, all the budget counts, could be 64 MB on
  the heap. The offer is taken out of the upgrade, and a relay that compresses anyway is dropped;
- `.onion` names are refused before any resolver sees them (the cluster's DNS would pass them on);
- no redirects at all, and no proxy (through a proxy, the proxy resolves the name and the
  address check would never see it). Outbound HTTPS from the service must therefore be direct.

Postgres is reached by JDBC, outside the guard: its address is the operator's, never a reader's.

## Configuration

| Variable | Default | |
|---|---|---|
| `DATABASE_URL` | (required) | JDBC URL of brainstorm_server's Postgres, `jdbc:postgresql://host:5432/db`. Every call is bounded: 10 s to connect, 15 s to log in, 30 s for an answer, 30 s per statement; don't set `options`, `socketTimeout` or `loginTimeout` in the URL, which would replace them |
| `DATABASE_USER`, `DATABASE_PASSWORD` | | the `observer_service` role (below). The password is a secret: from a k8s Secret |
| `NSEC_KEYS_FILE` | `/run/secrets/nsec_encryption_keys` | brainstorm_server's Fernet key file, mounted read-only |
| `PORT` | `8080` | where `POST /v1/run` is served |
| `PARALLELISM` | `4` | owners' runs at once (1 to 64); a wake past them is a `429` |
| `PENDING_SECONDS` | `300` | seconds between passes over the papers still waiting (30 to 86,400) |
| `GRACE_SECONDS` | `498` | on SIGTERM, how long the prints under way may still take |
| `OBSERVER_SEARCH_RELAY` | `wss://search.brainstorm.world` | the ranked relay the press reads |
| `INDEX_RELAYS` | the search relay (Brainstorm's index relay is the same) | comma-separated; where schedules, relay lists, 10063, 10040 and kind 0 are found beside the owner's own relays. Public relays only |

## Deploying beside brainstorm_server

CI (`.github/workflows/build.yml`) builds and pushes the image on every push, after the tests
pass: `ghcr.io/nosfabrica/the-nostr-observer/observer-service`, tagged with the branch name,
`sha-<short>`, a `v*` tag's version, and `latest` on `main`. Staging pins one of those tags. By
hand, from the repository root:

    docker build -f service/Dockerfile -t observer-service .

- A Deployment with a ClusterIP Service on `PORT`, and **no Ingress**: the endpoint has no
  token and is for assistant-runner alone (its `TASK_URL_OBSERVER=http://observer-service:8080`).
  A NetworkPolicy that admits only the runner's pods is a good second fence.
- Replicas may be more than one, and a rolling update is fine: the claim in
  `observer.runs` is what keeps two processes from printing one morning, and the papers
  waiting are in Postgres, not on a volume. There is no volume.
- At startup it applies its own `observer` schema's migrations (versioned SQL in
  `src/main/resources/observer/`, recorded in `observer.schema_version`, under an advisory lock
  of its own, so whichever of its processes starts first does it) and exits with status 3 when it
  can't, or can't listen on `PORT`; with status 2 on a bad setting.
- Postgres: a role of its own, not the runner's (which has the column grant alone):

      CREATE ROLE observer_service LOGIN PASSWORD '…';
      GRANT SELECT (pubkey, assistant_pubkey, encrypted_nsec, nsec) ON brainstorm_nsec TO observer_service;
      CREATE SCHEMA IF NOT EXISTS observer AUTHORIZATION observer_service;

  The service reads those four columns of brainstorm_server's `brainstorm_nsec` and nothing
  else of it, and is **coupled to them**: a server migration that renames or drops one breaks it.
  It owns the `observer` schema, so it needs no CREATE on the database. (The schema was
  `assistant_runner` before; a database migrated under that name keeps those rows there, unread.)
- Mount brainstorm_server's Fernet key file read-only at `NSEC_KEYS_FILE`. The server writes and
  rotates it; the service re-reads it when it changes.
- On SIGTERM it stops taking wakes (the listener closes), starts no new print, and lets the runs
  under way finish for up to `GRACE_SECONDS`; a paper they print is stored before it is
  published, so one cut off mid-publish goes out from the retry loop of the next process. Set
  the pod's `terminationGracePeriodSeconds` above that (for example 540).
- Egress: Postgres; HTTPS to the reader's AI endpoints and Blossom servers and WSS to relays,
  directly (see SSRF above).
