// The relay's auth gate, reproduced, with the whole readiness chain run
// through it.
//
// Measured 2026-08-30: search-staging CLOSES any REQ or COUNT whose `search`
// carries neither `observer:<hex>` nor `include:spam`. The failure mode is the
// usual one — a CLOSED subscription yields an empty list, which looks exactly
// like a reader who has published nothing — so the chain would report
// `no-relay-list` to a reader whose relay list is sitting right there. This
// pins that every question the chain asks clears the gate.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { closeAll } from '../scripts/nostr.mjs'
import { gather, storage } from '../scripts/readiness.mjs'
import { fakeRelay, event } from './fakerelay.mjs'

const OBSERVER = 'aa'.repeat(32)
const SERVICE = 'bb'.repeat(32)

const REFUSAL = 'auth-required: this relay answers through a web of trust and has no house '
  + 'observer to lend you. Sign in (NIP-42), or name whose trust ranks this read with the '
  + 'NIP-50 `observer:<64-hex pubkey>` token, or ask for the whole corpus unranked with `include:spam`.'

/** Answers like search-staging: the gate first, then by kind. */
function gatedRelay () {
  let refused = 0
  const relay = fakeRelay((filters, sub) => {
    const search = String(filters[0]?.search || '')
    if (!/observer:[0-9a-f]{64}/.test(search) && !search.includes('include:spam')) {
      refused++
      return [['CLOSED', sub, REFUSAL]]
    }
    const kind = filters[0]?.kinds?.[0]
    const answer = {
      10002: [event('e-10002', { kind: 10002, pubkey: OBSERVER, tags: [['r', 'wss://one.example']] })],
      10040: [event('e-10040', { kind: 10040, pubkey: OBSERVER, tags: [['30382:rank', SERVICE, 'wss://hint.example']] })],
      30382: [event('e-30382', { kind: 30382, pubkey: SERVICE, tags: [] })],
      10063: [event('e-10063', { kind: 10063, pubkey: OBSERVER, tags: [['server', 'https://blossom.example']] })],
      1: [event(`e-1-${sub}`, { kind: 1 })],
    }[kind] || []
    return [...answer.map((e) => ['EVENT', sub, e]), ['EOSE', sub]]
  })
  return { relay, get refused () { return refused } }
}

test('every question the readiness chain asks clears the auth gate', async () => {
  const gate = gatedRelay()
  const relay = await gate.relay
  try {
    const since = 1_786_800_000
    const [facts, hosting] = await Promise.all([
      gather(OBSERVER, relay.url, since),
      storage(OBSERVER, relay.url),
    ])
    // Each of these is false, null or 0 if its query was CLOSED at the gate.
    assert.equal(facts.relayListSeen, true, 'the 10002 lookup was refused')
    assert.equal(facts.scoreListSeen, true, 'the 10040 lookup was refused')
    assert.equal(facts.scoresHere, true, 'the 30382 lookup was refused')
    assert.ok(facts.probeObserved > 0, 'the observed probe was refused')
    assert.ok(facts.probeAnonymous > 0, 'the anonymous probe was refused')
    assert.equal(hosting.seen, true, 'the 10063 lookup was refused')
    // Belt and braces over the per-fact checks above: nothing was refused and
    // quietly recovered from either.
    assert.equal(gate.refused, 0, `${gate.refused} query(ies) hit the gate tokenless`)
  } finally { closeAll(); await relay.close() }
})

test('a refused read degrades to absent, not to a crash', async () => {
  // What the gate does to a tokenless query, end to end: the CLOSED reason
  // arrives, the read resolves empty, and the chain reports honestly instead
  // of hanging. This is the shape of the outage this file exists to prevent.
  const relay = await fakeRelay((filters, sub) => [['CLOSED', sub, REFUSAL]])
  try {
    const facts = await gather(OBSERVER, relay.url, 1_786_800_000)
    assert.equal(facts.relayListSeen, false)
    assert.deepEqual(facts.writeRelays, [])
  } finally { closeAll(); await relay.close() }
})
