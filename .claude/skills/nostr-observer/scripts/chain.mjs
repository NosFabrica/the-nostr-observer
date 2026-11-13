// The readiness decision, with no network in it.
//
// Split from the half that talks to relays for the same reason
// `Readiness.kt` is split from `ReadinessProbe.kt`: this half is pure, so
// every state it can reach is reachable from a test, including the ones a live
// relay will almost never hand you.
//
// Three properties are load-bearing and none of them are obvious:
//
//  1. THE FIRST UNMET LINK WINS. Every link below it reports `waiting`, never
//     a second failure. A column of red crosses says four things are wrong
//     when one is, and sends the reader off to fix three that are fine.
//
//  2. THE RANKED PROBE IS NOT REDUNDANT with the link above it. Cards can be
//     present and not yet PROJECTED. Only asking the observed and anonymous
//     reads the same question catches that — and asking both is also what
//     stops an empty corpus reading as a broken lens.
//
//  3. "YOUR OWN POSTS ARE BEHIND" IS AN ASIDE, NOT A LINK. It is dropped here
//     entirely: it needed a COUNT, ranking is complete without it, and the fix
//     for it is nothing at all.
//
// A `null` fact means NOT ASKED YET and yields `checking`. It must never be
// read as a negative answer: "this relay holds none of that service's cards"
// and "we have not looked" are different sentences with different next steps.

// `toNpub` is pure bech32, so importing it here keeps this module testable
// without a network and keeps hex off the screen. `INCLUDE_SPAM` is the token
// the search relay's auth gate requires on any query that names no observer.
import { toNpub, INCLUDE_SPAM } from './nostr.mjs'

export const KIND_RELAY_LIST = 10002 // NIP-65
export const KIND_TRUST_PROVIDERS = 10040 // NIP-85
export const KIND_CONTACT_CARD = 30382 // NIP-85 trust assertion
export const KIND_BLOSSOM_SERVERS = 10063 // BUD-03
export const RANK_SERVICE = '30382:rank'

const tagsNamed = (event, name) => (event?.tags || []).filter((t) => t[0] === name)

/**
 * NIP-65 write relays.
 *
 * An `r` tag with NO marker means BOTH — the rule that, read wrong, reports
 * "no write relays" for the majority of real relay lists and sends the reader
 * off to fix something that works. Non-websocket entries are dropped because
 * we dial these, and a real 10002 in the wild carries `https://` entries.
 */
export function writeRelays (event) {
  if (!event) return null
  return tagsNamed(event, 'r')
    .filter((t) => t[1] && (t.length < 3 || !t[2] || t[2] === 'write'))
    .map((t) => String(t[1]).trim().replace(/\/+$/, ''))
    .filter((url) => url.startsWith('wss://') || url.startsWith('ws://'))
}

/**
 * The `30382:rank` service and the relay it publishes to.
 *
 * ALL THREE FIELDS ARE REQUIRED. A 10040 naming only `30382:followers` can
 * ORDER a list but cannot RANK one, and an entry with no relay hint resolves
 * to nothing in the store's provider map. Both are a BROKEN link rather than a
 * missing one, and both are rejected right here.
 */
export function rankProvider (event) {
  if (!event) return null
  const tag = (event.tags || []).find((t) => t[0] === RANK_SERVICE && t[1] && t[2])
  return tag ? { service: tag[1], relay: tag[2] } : null
}

/**
 * https only. A Blossom PUT is an HTTPS call and the sanitizer allows no other
 * scheme, so a plain-http entry is a server we cannot use.
 */
export function blossomServers (event) {
  if (!event) return null
  const servers = tagsNamed(event, 'server')
    .map((t) => String(t[1] || '').trim().replace(/\/+$/, ''))
    .filter((url) => url.toLowerCase().startsWith('https://'))
  return [...new Set(servers)]
}

/**
 * The same question asked twice, once through the lens and once without.
 *
 * `since` IS REQUIRED, and leaving it off is not a tidier filter but a broken
 * probe: measured against search-staging, this search returns immediately with
 * a 24-hour `since` and times out with none at all. Both sides then come back
 * zero, which reads as a quiet window rather than a broken lens — so the link
 * passes every time while testing nothing. It shipped that way once.
 *
 * NO TRUST FLOOR HERE, deliberately. The floor is what the desks send; this
 * probe must be able to SEE the silent degradation to anonymous ranking, and a
 * floor would hide it by returning nothing either way.
 *
 * The anonymous side carries `include:spam` because the relay's auth gate
 * CLOSES a bare `sort:rank` outright now (measured 2026-08-30). The token
 * does not change what the probe measures: `include:spam sort:rank` is still
 * the anonymous ranking — measured the same day, its top 100 shares 0 events
 * with the plain-recency `include:spam` cut, so `sort:rank` is still doing
 * the sorting and the token only opens the door.
 */
export function rankedProbe (observerHex, since) {
  return {
    kinds: [1],
    since,
    search: observerHex ? `observer:${observerHex} sort:rank` : `${INCLUDE_SPAM} sort:rank`,
    limit: 12,
  }
}

export const REMEDY = {
  checking: { say: 'Checking your web of trust.', do: null },
  'no-relay-list': {
    say: 'Your account has not said which relays it uses, so nothing about you can be found.',
    do: 'Open your usual Nostr app and publish a relay list (NIP-65, kind 10002). This is the one thing nobody can do for you.',
  },
  'no-usable-relays': {
    say: 'Your relay list names nothing we can dial - every entry is missing or is not a websocket URL.',
    do: 'Check the relay list in your usual Nostr app. Entries must be wss:// addresses.',
  },
  'no-score-list': {
    say: 'You have not chosen who works out your web of trust, so there is no lens to rank through.',
    do: 'Get a lens minted at https://brainstorm.world - it computes your web of trust and publishes the '
      + 'kind 30382 cards this reads. Neither nip85.nosfabrica.com nor scores.brainstorm.world exposes an API '
      + 'for that yet, so it is an operator step and not a button. Once your kind 10040 names a 30382:rank '
      + 'service with a relay hint, run this again.',
  },
  'no-rank-service': {
    say: 'Your trust provider list exists but does not name a usable rank service. A list with only '
      + '30382:followers can ORDER a feed but cannot RANK one, and an entry with no relay hint resolves to nothing.',
    do: 'Point it at a rank service with a relay hint - https://brainstorm.world can do this.',
  },
  'no-scores-yet': {
    say: "Your web of trust is being worked out. This relay answered and holds none of your service's cards yet.",
    do: 'Nothing for you to do - this is us waiting. Try again later.',
  },
  'projection-pending': {
    say: 'Almost there. Your cards are stored but the trust projection has not run for your service yet.',
    do: 'Nothing for you to do - this clears on its own. Try again later.',
  },
  ready: { say: 'Ready.', do: null },
}

/**
 * Facts in, verdict out.
 *
 * `facts` fields, every one of which may be null meaning "not asked":
 *   writeRelays    string[] | null   `seen` distinguishes an empty list from no list
 *   relayListSeen  boolean
 *   scoreListSeen  boolean | null
 *   rankService    string | null
 *   rankRelay      string | null
 *   scoresHere     boolean | null    present/absent; never a fraction, see readiness.mjs
 *   probeObserved  number | null     rows the observed read returned
 *   probeAnonymous number | null     rows the anonymous read returned
 */
export function assess (facts) {
  const chain = []
  const link = (key, status, detail = null) => chain.push({ key, status, detail })
  const waiting = (...keys) => keys.forEach((k) => link(k, 'waiting'))
  const verdict = (state) => ({ state, ready: state === 'ready', chain })
  const checking = () => verdict('checking')

  // --- link 1: do we know where you post? ----------------------------------
  const writes = facts.writeRelays
  if (writes == null) return checking()
  if (writes.length === 0) {
    // Two different facts, and telling a reader the wrong one sends them to fix
    // something that is not broken. NO list is permanent - nothing will ever
    // discover them. A list we cannot USE is their list being unreachable.
    link('relayList', 'broken', facts.relayListSeen ? 'list names no usable relay' : 'absent')
    waiting('scoreList', 'scores', 'ranked')
    return verdict(facts.relayListSeen ? 'no-usable-relays' : 'no-relay-list')
  }
  link('relayList', 'ok', `${writes.length} write relay(s)`)

  // --- link 2: do you name a service whose scores rank? --------------------
  if (facts.scoreListSeen == null) return checking()
  if (!facts.scoreListSeen) {
    link('scoreList', 'broken', 'absent')
    waiting('scores', 'ranked')
    return verdict('no-score-list')
  }
  if (!facts.rankService) {
    link('scoreList', 'broken', 'no rank dimension, or no relay hint')
    waiting('scores', 'ranked')
    return verdict('no-rank-service')
  }
  link('scoreList', 'ok', `${toNpub(facts.rankService).slice(0, 12)}… @ ${facts.rankRelay || 'no hint'}`)

  // --- link 3: have the scores arrived? ------------------------------------
  if (facts.scoresHere == null) return checking()
  if (!facts.scoresHere) {
    // Absent here IS a claim: this relay answered, and holds none of that
    // service's cards. A ranked read returns nothing, so this is blocked and
    // not partial, whatever the provider's own relay would say.
    link('scores', 'broken', 'no cards on this relay')
    waiting('ranked')
    return verdict('no-scores-yet')
  }
  link('scores', 'ok', 'cards present')

  // --- link 4: does a ranked read actually come back? ----------------------
  if (facts.probeObserved == null || facts.probeAnonymous == null) return checking()
  if (facts.probeAnonymous > 0 && facts.probeObserved === 0) {
    link('ranked', 'broken', `observed=0 anonymous=${facts.probeAnonymous}`)
    return verdict('projection-pending')
  }
  link('ranked', 'ok', `observed=${facts.probeObserved} anonymous=${facts.probeAnonymous}`)
  return verdict('ready')
}
