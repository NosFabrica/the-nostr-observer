#!/usr/bin/env node
// Asks the relay the four questions `chain.mjs` decides on.
//
// The decision is pure and lives next door; this is the half that talks to the
// network, kept separate for exactly that reason. Every fact it cannot
// establish is left NULL rather than guessed, because null drives `checking`
// and a guess drives a confident wrong answer.
//
// NO NIP-45 COUNT ANYWHERE, and that is the one real departure from
// `ReadinessProbe.kt`. AGENTS.md records that this store sends an AUTH
// challenge before it answers a COUNT even though `auth_required` is false,
// that four concurrent COUNTs turned a three-second check into a hang, and
// that it goes through spells of not answering COUNTs at all. Every question
// here is a REQ, so none of that can happen.
//
// WHAT THAT COSTS. The Kotlin asks COUNT for link 3 and reports an import
// percentage from it. This asks a REQ with `limit: 1` instead, which answers
// the only question that BLOCKS - "does this relay hold any of that service's
// cards" - and cannot answer "what fraction". So this never prints a
// percentage and never reports `importing`. That is the existing contract, not
// a new one: `Readiness.fraction` already returns null when there is no honest
// denominator, and callers must draw nothing rather than estimate.
//
// Usage: node readiness.mjs <npub> [--relay wss://...] [--json out.json]

import { req, one, toHex, toNpub, closeAll, INCLUDE_SPAM } from './nostr.mjs'
import {
  assess, REMEDY, writeRelays, rankProvider, blossomServers, rankedProbe,
  KIND_RELAY_LIST, KIND_TRUST_PROVIDERS, KIND_CONTACT_CARD, KIND_BLOSSOM_SERVERS,
} from './chain.mjs'
import { writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'

export const DEFAULT_RELAY = 'wss://search-staging.brainstorm.world'
export const WINDOW_SECONDS = 24 * 60 * 60

function arg (name, fallback = null) {
  const at = process.argv.indexOf(name)
  return at > -1 ? process.argv[at + 1] : fallback
}

/** Every fact `assess` needs, gathered from one relay. */
export async function gather (observerHex, relay, since) {
  // Link 4's two reads do not depend on links 1-3, so they start now. They are
  // the only thing that can see a service whose cards are stored but not yet
  // projected.
  const probes = Promise.all([
    req(relay, rankedProbe(observerHex, since), { label: 'observed probe' }),
    req(relay, rankedProbe(null, since), { label: 'anonymous probe' }),
  ])

  // Independent of each other, so one round trip rather than two. Only the
  // card read below has to wait, because it needs the service from the 10040.
  // Each lookup says `include:spam` because the relay's auth gate CLOSES a
  // tokenless query — see INCLUDE_SPAM. The reader's own 10002 is exactly the
  // kind of plain read the gate refuses.
  const [relayListEvent, scoreListEvent] = await Promise.all([
    one(relay, { kinds: [KIND_RELAY_LIST], authors: [observerHex], search: INCLUDE_SPAM }, { label: 'kind 10002' }),
    one(relay, { kinds: [KIND_TRUST_PROVIDERS], authors: [observerHex], search: INCLUDE_SPAM }, { label: 'kind 10040' }),
  ])
  const provider = rankProvider(scoreListEvent)

  // Only asked when there is a service to ask about. Null, not false: we did
  // not look, which is a different sentence from "the relay holds none".
  const card = provider
    ? await one(relay, { kinds: [KIND_CONTACT_CARD], authors: [provider.service], search: INCLUDE_SPAM }, { label: 'kind 30382' })
    : null

  const [observed, anonymous] = await probes

  return {
    writeRelays: writeRelays(relayListEvent) ?? [],
    relayListSeen: relayListEvent != null,
    scoreListSeen: scoreListEvent != null,
    rankService: provider?.service ?? null,
    rankRelay: provider?.relay ?? null,
    scoresHere: provider ? card != null : null,
    probeObserved: observed.events.length,
    probeAnonymous: anonymous.events.length,
  }
}

/**
 * The second chain: can you HOST your paper?
 *
 * Two chains, not one, and they fail independently. A reader with no Blossom
 * server can still SEE their edition - they just cannot publish it, which this
 * skill does not do anyway. So this never blocks; it is checked because
 * pre-flight is the cheap moment to learn there is nowhere to put the paper,
 * and publish time is the expensive one.
 */
export async function storage (observerHex, relay) {
  const event = await one(relay, { kinds: [KIND_BLOSSOM_SERVERS], authors: [observerHex], search: INCLUDE_SPAM }, { label: 'kind 10063' })
  return { seen: event != null, servers: blossomServers(event) ?? [] }
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

  const until = Math.floor(Date.now() / 1000)
  const since = until - WINDOW_SECONDS

  console.log(`\n  Reading for ${toNpub(observerHex)}`)
  console.log(`  through ${relay}\n`)

  // The storage chain is independent of the lens chain — they fail separately
  // — so it rides along instead of costing a seventh round trip afterwards.
  const hosting = storage(observerHex, relay)
  const facts = await gather(observerHex, relay, since)
  const verdict = assess(facts)

  for (const item of verdict.chain) {
    console.log(`  ${MARK[item.status] || '?'} ${LABEL[item.key] || item.key}  ${item.detail || ''}`)
  }

  const remedy = REMEDY[verdict.state] || { say: verdict.state, do: null }
  console.log(`\n  ${remedy.say}`)
  if (remedy.do) console.log(`\n  What to do: ${remedy.do}`)

  const servers = (await hosting)
  console.log('')
  if (servers.servers.length > 0) {
    console.log(`  Aside - you have ${servers.servers.length} Blossom server(s), so this edition could be published later.`)
  } else {
    console.log('  Aside - you have nowhere to store files (no usable kind 10063), so this edition')
    console.log('  could be read but not published. That does not block anything here.')
  }

  const out = arg('--json')
  if (out) {
    writeFileSync(out, JSON.stringify({ ...verdict, observer: observerHex, relay, since, until, facts, storage: servers }, null, 2))
  }

  console.log(`\n  VERDICT: ${verdict.ready ? 'READY' : 'NOT READY - ' + verdict.state}\n`)
  closeAll()
  process.exit(verdict.ready ? 0 : 1)
}

// Importable by the tests; runs only when it is the thing that was invoked.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`\n  Readiness check failed: ${error.message}\n`)
    process.exit(3)
  })
}
