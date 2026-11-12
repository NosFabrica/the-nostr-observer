# observer-runner: the Brainstorm Assistant's print worker

Prints the Observer for readers whose schedule names their Brainstorm Assistant as its runner
(NIP-FB, "Runner"; Quartz `quartz/plans/2026-11-11-observer-site.md`, "The Assistant as
runner"). It runs on Brainstorm's servers, beside brainstorm_server, as its own container.

**It holds no nsec.** Every decryption and every signature is brainstorm_server's, through
`/internal/observerRunner/...`, and the server answers only for the owner's own Assistant.

## What one poll does

1. Pages `GET /internal/observerRunner/assistants` for every (owner, Assistant).
2. Reads kind 34817 `d=observer` by those owners, `#p` = their Assistants, from each owner's
   NIP-65 write relays (found through the index relays) and the index relays.
3. Drops every schedule that isn't exactly one public `p`, then checks the rest with
   `ScheduledTaskClaims.checkRunner(event, assistant, owner)`: the author must be the owner the
   server maps to that Assistant, and the event must verify. Being tagged is not enough.
4. Has the server decrypt the schedule (`decrypt` with the whole signed event), skips one with a
   param it doesn't understand (`ObserverParams.understands`), and decides with
   `ScheduledTaskClaims.decideAsRunner`: due, not paused, after the time it was set, not already
   ended here.
5. Reads both sites, the owner's and the Assistant's (`ObserverSite.runnerSiteFilter`), and
   skips an occurrence `ObserverSite.alreadyPrinted(readerEditions, runnerEditions, …)` finds.
   If the Assistant's own site can't be read it doesn't print (a publish would start from
   nothing).
6. Checks what a print needs before spending: the owner's Blossom servers (kind 10063), the
   Assistant's write relays, the owner's public rank provider (kind 10040).
7. Has the server decrypt the Assistant's own kind 12000, picks the key with
   `ObserverParams.key` (the `key` param: URL + last 4 characters) and the model with
   `ObserverParams.model`, else the best model the endpoint lists.
8. Refuses a key URL that is loopback, private or internal (below), and never tries another key.
9. Prints with the engine (`generator/`, Amethyst's press and page checks).
10. Uploads the page, `archive.html` and `editions.json` to the **owner's** Blossom servers only,
    each with a kind 24242 the server signs (`t=upload`, one `x`, 5 minutes to expiry).
11. Publishes the **Assistant's** Observer site (35128, `d=observer`, `["p", owner]`), signed by
    the server, to the Assistant's write relays. What it publishes is the event the server
    returned (the server replaces the `client` tag), checked to verify and to be the
    Assistant's site for that owner.

A run that reached the model is recorded as ended and never run again on its own, whatever came
of it: it may have spent the Assistant's money. A run that stopped before (the ranking not
projected yet) is forgotten and tried again on the next poll. The records are a JSON file
(`OBSERVER_RUNNER_STATE`); keep it on a volume.

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
  `.internal`, `.svc`, `.cluster.local`, …); no credentials in the URL;
- every address a name resolves to, at connect time (`GuardedDns`), so a DNS rebind is caught:
  one internal address in the answer refuses the name;
- no redirects at all, and no proxy (through a proxy, the proxy resolves the name and the
  address check would never see it). Outbound HTTPS from the worker must therefore be direct.

brainstorm_server itself is reached with a separate, unguarded client.

## Configuration

| Variable | Default | |
|---|---|---|
| `BRAINSTORM_SERVER_URL` | (required) | brainstorm_server's internal address, e.g. `http://brainstorm-server:8000` |
| `OBSERVER_RUNNER_TOKEN` | (required) | the same value as brainstorm_server's `OBSERVER_RUNNER_TOKEN`. A secret: from a k8s Secret, never in a manifest |
| `OBSERVER_RUNNER_POLL_SECONDS` | `300` | seconds between polls (at least 30) |
| `OBSERVER_SEARCH_RELAY` | `wss://search.brainstorm.world` | the ranked relay the press reads |
| `OBSERVER_INDEX_RELAYS` | the search relay, `wss://purplepag.es` | comma-separated; where relay lists, 10063, 10040 and kind 0 are found. Public relays only |
| `OBSERVER_RUNNER_STATE` | none (memory) | the run records file; the image sets `/var/lib/observer-runner/runs.json` |

## Deploying beside brainstorm_server

    docker build -f runner/Dockerfile -t observer-runner .

- Run it on the same internal network as brainstorm_server, as its own Deployment with one
  replica (two replicas would each print the same morning). No Service and no Ingress: nothing
  connects to it.
- `/internal/observerRunner/...` must **never** be routed by the public ingress. The worker
  reaches brainstorm_server by its cluster address; the ingress for the public API must not
  forward `/internal/` (brainstorm_server also answers 404 without the token, as if the route
  didn't exist).
- Give both the same `OBSERVER_RUNNER_TOKEN` from one Secret. With it unset on the server,
  every internal call is a 404 and the worker logs that the token is refused.
- Mount a volume at `/var/lib/observer-runner`.
- Egress: HTTPS to the reader's AI endpoints and Blossom servers, WSS to relays, directly (see
  SSRF above), and HTTP to brainstorm_server inside the cluster.

## The contract

The HTTP shapes, as brainstorm_server builds them (its `app/routers/observer_runner/`):
every response is `{code, data, message}`; auth is `Authorization: Bearer <token>`.

- `GET /internal/observerRunner/assistants?after=<owner hex>&limit=500` →
  `{items: [{owner, assistant}], next: <owner hex> | null}`.
- `POST /internal/observerRunner/decrypt` `{owner, event}` (a whole signed event) →
  `{plaintext}`. Only a kind 34817 by the owner with exactly one `p`, that owner's Assistant
  (decrypted with the owner as peer), or a kind 12000 by the Assistant (itself as peer). Anything
  else is 403. There is no bare-ciphertext mode.
- `POST /internal/observerRunner/sign` `{owner, event: {kind, created_at, tags, content}}` →
  the signed event, by the owner's Assistant. Only 35128 with exactly one `d=observer` and one
  `p` = the owner, and 24242 with `t=upload` and an `expiration` at most 10 minutes ahead;
  `created_at` at most 10 minutes ahead. Every other kind or shape is 403. The `client` tag is
  replaced with `["client","Brainstorm"]`.
- An owner without an Assistant: 404 with `X-Reason: no-assistant`. A wrong or unset token: a
  plain 404.
