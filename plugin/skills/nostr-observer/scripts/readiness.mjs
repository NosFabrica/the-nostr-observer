#!/usr/bin/env node
// Can this relay rank for this reader — and if not, which link is missing?
//
// A port of `generator/src/main/kotlin/.../nostr/Readiness.kt` and its probe.
// Three properties of the original are load-bearing and none of them are
// obvious, so they are preserved here on purpose:
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
// WHAT THE NO-COUNT RULE COSTS. The Kotlin asks NIP-45 COUNT for link 3 and
// reports an import percentage from it. This asks a REQ with `limit: 1`
// instead, which answers the only question that BLOCKS — "does this relay hold
// any of that service's cards" — and cannot answer "what fraction". So this
// never prints a percentage and never reports `importing`. That is the
// existing contract, not a new one: `Readiness.fraction` already returns null
// when there is no honest denominator, and callers must draw nothing rather
// than estimate.
//
// Usage: node readiness.mjs <npub> [--relay wss://…] [--json out.json]

import { req, one, toHex, toNpub, tagsNamed } from './nostr.mjs'
import { writeFileSync } from 'node:fs'

const DEFAULT_RELAY = 'wss://search-staging.brainstorm.world'
const WINDOW_SECONDS = 24 * 60 * 60

const KIND_RELAY_LIST = 10002 // NIP-65
const KIND_TRUST_PROVIDERS = 10040 // NIP-85
const KIND_CONTACT_CARD = 30382 // NIP-85 trust assertion
const KIND_BLOSSOM_SERVERS = 10063 // BUD-03
const RANK_SERVICE = '30382:rank'

function arg (name, fallback = null) {
  const at = process.argv.indexOf(name)
  return at > -1 ? process.argv[at + 1] : fallback
}

/**
 * NIP-65 write relays.
 *
 * An `r` tag with NO marker means BOTH — the rule that, read wrong, reports
 * "no write relays" for the majority of real relay lists and sends the reader
 * off to fix something that works. Non-websocket entries are dropped because
 * we dial these, and a real 10002 in the wild carries `https://` entries.
 */
function writeRelays (event) {
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
function rankProvider (event) {
  if (!event) return null
  const tag = (event.tags || []).find((t) => t[0] === RANK_SERVICE && t[1] && t[2])
  return tag ? { service: tag[1], relay: tag[2] } : null
}

/**
 * The same question asked twice, once through the lens and once without.
 *
 * `since` IS REQUIRED, and leaving it off is not a tidier filter but a broken
 * probe: measured against search-staging, this search returns immediately with
 * a 24-hour `since` and times out with none at all. Both sides then come back
 * zero, which reads as a quiet window rather than a broken lens — so the link
 * passes every time while testing nothing. It shipped that way once.
 */
function rankedProbe (observerHex, since) {
  return {
    kinds: [1],
    since,
    search: observerHex ? `observer:${observerHex} sort:rank` : 'sort:rank',
    limit: 12,
  }
}

const REMEDY = {
  'no-relay-list': {
    say: 'Your account has not said which relays it uses, so nothing about you can be found.',
    do: 'Open your usual Nostr app and publish a relay list (NIP-65, kind 10002). This is the one thing nobody can do for you.',
  },
  'no-usable-relays': {
    say: 'Your relay list names nothing we can dial — every entry is missing or is not a websocket URL.',
    do: 'Check the relay list in your usual Nostr app. Entries must be wss:// addresses.',
  },
  'no-score-list': {
    say: 'You have not chosen who works out your web of trust, so there is no lens to rank through.',
    do: 'Get a lens minted at https://brainstorm.world — it computes your web of trust and publishes the '
      + 'kind 30382 cards this reads. Neither nip85.nosfabrica.com nor scores.brainstorm.world exposes an API '
      + 'for that yet, so it is an operator step and not a button. Once your kind 10040 names a 30382:rank '
      + 'service with a relay hint, run this again.',
  },
  'no-rank-service': {
    say: 'Your trust provider list exists but does not name a usable rank service. A list with only '
      + '30382:followers can ORDER a feed but cannot RANK one, and an entry with no relay hint resolves to nothing.',
    do: 'Point it at a rank service with a relay hint — https://brainstorm.world can do this.',
  },
  'no-scores-yet': {
    say: 'Your web of trust is being worked out. This relay answered and holds none of your service\'s cards yet.',
    do: 'Nothing for you to do — this is us waiting. Try again later.',
  },
  'projection-pending': {
    say: 'Almost there. Your cards are stored but the trust projection has not run for your service yet.',
    do: 'Nothing for you to do — this clears on its own. Try again later.',
  },
  ready: { say: 'Ready.', do: null },
}

async function assess (observerHex, relay) {
  const until = Math.floor(Date.now() / 1000)
  const since = until - WINDOW_SECONDS
  const chain = []
  const link = (key, status, detail) => chain.push({ key, status, detail })
  const waiting = (...keys) => keys.forEach((k) => link(k, 'waiting', null))
  const verdict = (state) => ({ state, ready: state === 'ready', chain, observer: observerHex, relay, since, until })

  // Link 4's two reads do not depend on links 1-3, so they start now. They are
  // the only thing that can see a service whose cards are stored but not yet
  // projected.
  const probes = Promise.all([
    req(relay, rankedProbe(observerHex, since), { label: 'observed probe' }),
    req(relay, rankedProbe(null, since), { label: 'anonymous probe' }),
  ])

  // --- link 1: do we know where you post? ----------------------------------
  const relayListEvent = await one(relay, { kinds: [KIND_RELAY_LIST], authors: [observerHex] }, { label: 'kind 10002' })
  const writes = writeRelays(relayListEvent)
  if (!writes || writes.length === 0) {
    // Two different facts. NO list is permanent — nothing will ever discover
    // them. A list we cannot USE is their list being unreachable. Different
    // sentences, and telling a reader the wrong one sends them to fix
    // something that is not broken.
    link('relayList', 'broken', relayListEvent ? 'list names no usable relay' : 'absent')
    waiting('scoreList', 'scores', 'ranked')
    await probes
    return verdict(relayListEvent ? 'no-usable-relays' : 'no-relay-list')
  }
  link('relayList', 'ok', `${writes.length} write relay(s)`)

  // --- link 2: do you name a service whose scores rank? --------------------
  const scoreListEvent = await one(relay, { kinds: [KIND_TRUST_PROVIDERS], authors: [observerHex] }, { label: 'kind 10040' })
  if (!scoreListEvent) {
    link('scoreList', 'broken', 'absent')
    waiting('scores', 'ranked')
    await probes
    return verdict('no-score-list')
  }
  const provider = rankProvider(scoreListEvent)
  if (!provider) {
    link('scoreList', 'broken', 'no rank dimension, or no relay hint')
    waiting('scores', 'ranked')
    await probes
    return verdict('no-rank-service')
  }
  link('scoreList', 'ok', `${toNpub(provider.service).slice(0, 12)}… @ ${provider.relay}`)

  // --- link 3: have the scores arrived? ------------------------------------
  // One REQ, limit 1. Present or absent, never a percentage — see the header.
  const card = await one(relay, { kinds: [KIND_CONTACT_CARD], authors: [provider.service] }, { label: 'kind 30382' })
  if (!card) {
    // Absent here IS a claim: this relay answered, and holds none of that
    // service's cards. A ranked read returns nothing, so this is blocked and
    // not partial, whatever the provider's own relay would say.
    link('scores', 'broken', 'no cards on this relay')
    waiting('ranked')
    await probes
    return verdict('no-scores-yet')
  }
  link('scores', 'ok', 'cards present')

  // --- link 4: does a ranked read actually come back? ----------------------
  const [observed, anonymous] = await probes
  if (anonymous.events.length > 0 && observed.events.length === 0) {
    link('ranked', 'broken', `observed=0 anonymous=${anonymous.events.length}`)
    return verdict('projection-pending')
  }
  link('ranked', 'ok', `observed=${observed.events.length} anonymous=${anonymous.events.length}`)
  return verdict('ready')
}

/**
 * The second chain: can you HOST your paper?
 *
 * Two chains, not one, and they fail independently. A reader with no Blossom
 * server can still SEE their edition — they just cannot publish it, which this
 * skill does not do anyway. So this never blocks; it is checked here because
 * pre-flight is the cheap moment to learn it and publish time is the expensive
 * one.
 */
async function storage (observerHex, relay) {
  const event = await one(relay, { kinds: [KIND_BLOSSOM_SERVERS], authors: [observerHex] }, { label: 'kind 10063' })
  if (!event) return { seen: false, servers: [] }
  const servers = tagsNamed(event, 'server')
    .map((t) => String(t[1] || '').trim().replace(/\/+$/, ''))
    // https only: a Blossom PUT is an HTTPS call and the sanitizer allows no
    // other scheme, so a plain-http entry is a server we cannot use.
    .filter((url) => url.toLowerCase().startsWith('https://'))
  return { seen: true, servers: [...new Set(servers)] }
}

const MARK = { ok: '✓', broken: '✗', waiting: '·', partial: '~' }
const LABEL = {
  relayList: 'Where you post          ',
  scoreList: 'Who ranks for you       ',
  scores: 'Your scores are here    ',
  ranked: 'A ranked read comes back',
}

async function main () {
  const input = process.argv[2]
  if (!input || input.startsWith('--')) {
    console.error('Usage: node readiness.mjs <npub> [--relay wss://...] [--json out.json]')
    process.exit(2)
  }
  const relay = arg('--relay', DEFAULT_RELAY)
  let observerHex
  try {
    observerHex = toHex(input)
  } catch (error) {
    console.error(`\n  ${error.message}\n`)
    process.exit(2)
  }

  console.log(`\n  Reading for ${toNpub(observerHex)}`)
  console.log(`  through ${relay}\n`)

  const verdict = await assess(observerHex, relay)
  for (const item of verdict.chain) {
    console.log(`  ${MARK[item.status] || '?'} ${LABEL[item.key] || item.key}  ${item.detail || ''}`)
  }

  const remedy = REMEDY[verdict.state] || { say: verdict.state, do: null }
  console.log(`\n  ${remedy.say}`)
  if (remedy.do) console.log(`\n  What to do: ${remedy.do}`)

  const hosting = await storage(observerHex, relay)
  verdict.storage = hosting
  console.log('')
  if (hosting.servers.length > 0) {
    console.log(`  Aside - you have ${hosting.servers.length} Blossom server(s), so this edition could be published later.`)
  } else {
    console.log('  Aside - you have nowhere to store files (no usable kind 10063), so this edition')
    console.log('  could be read but not published. That does not block anything here.')
  }

  const out = arg('--json')
  if (out) writeFileSync(out, JSON.stringify(verdict, null, 2))

  console.log(`\n  VERDICT: ${verdict.ready ? 'READY' : 'NOT READY - ' + verdict.state}\n`)
  process.exit(verdict.ready ? 0 : 1)
}

main().catch((error) => {
  console.error(`\n  Readiness check failed: ${error.message}\n`)
  process.exit(3)
})
