// The readiness decision. Pure, so every state it can reach is reachable here
// — including the ones a live relay will hand you about once a year.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { assess, REMEDY, writeRelays, rankProvider, blossomServers, rankedProbe } from '../scripts/chain.mjs'

const OBSERVER = 'aa'.repeat(32)
const SERVICE = 'bb'.repeat(32)

const ready = {
  writeRelays: ['wss://one.example'],
  relayListSeen: true,
  scoreListSeen: true,
  rankService: SERVICE,
  rankRelay: 'wss://hint.example',
  scoresHere: true,
  probeObserved: 12,
  probeAnonymous: 12,
}

const status = (verdict, key) => verdict.chain.find((l) => l.key === key)?.status

test('a complete chain is ready', () => {
  const verdict = assess(ready)
  assert.equal(verdict.state, 'ready')
  assert.equal(verdict.ready, true)
})

test('no relay list and an unusable relay list are different sentences', () => {
  const none = assess({ ...ready, writeRelays: [], relayListSeen: false })
  assert.equal(none.state, 'no-relay-list')
  const unusable = assess({ ...ready, writeRelays: [], relayListSeen: true })
  assert.equal(unusable.state, 'no-usable-relays')
  assert.notEqual(REMEDY['no-relay-list'].do, REMEDY['no-usable-relays'].do)
})

test('THE FIRST UNMET LINK WINS: everything below it waits', () => {
  // Four crosses would send the reader off to fix three things that are fine.
  const verdict = assess({
    writeRelays: [], relayListSeen: false,
    scoreListSeen: false, rankService: null, rankRelay: null,
    scoresHere: false, probeObserved: 0, probeAnonymous: 99,
  })
  assert.equal(verdict.state, 'no-relay-list')
  assert.equal(status(verdict, 'relayList'), 'broken')
  for (const key of ['scoreList', 'scores', 'ranked']) {
    assert.equal(status(verdict, key), 'waiting', `${key} must wait, not report a second failure`)
  }
  assert.equal(verdict.chain.filter((l) => l.status === 'broken').length, 1)
})

test('no trust provider list at all', () => {
  const verdict = assess({ ...ready, scoreListSeen: false, rankService: null, rankRelay: null })
  assert.equal(verdict.state, 'no-score-list')
  assert.match(REMEDY['no-score-list'].do, /brainstorm\.world/)
})

test('a provider list with no rank dimension is broken, not missing', () => {
  const verdict = assess({ ...ready, rankService: null })
  assert.equal(verdict.state, 'no-rank-service')
  assert.equal(status(verdict, 'scoreList'), 'broken')
})

test('cards absent on this relay blocks, whatever the provider says', () => {
  const verdict = assess({ ...ready, scoresHere: false })
  assert.equal(verdict.state, 'no-scores-yet')
  assert.equal(status(verdict, 'scores'), 'broken')
})

test('THE RANKED PROBE IS NOT REDUNDANT: stored but not projected', () => {
  // Cards are present and the anonymous read works, so links 1-3 all pass.
  // Only asking the same question both ways catches this.
  const verdict = assess({ ...ready, probeObserved: 0, probeAnonymous: 40 })
  assert.equal(verdict.state, 'projection-pending')
  assert.equal(status(verdict, 'scores'), 'ok')
  assert.equal(status(verdict, 'ranked'), 'broken')
})

test('a genuinely quiet window is ready, not broken', () => {
  // Both sides zero means the window was quiet. Reading that as a broken lens
  // would refuse to print on the one day the paper should be thin and honest.
  const verdict = assess({ ...ready, probeObserved: 0, probeAnonymous: 0 })
  assert.equal(verdict.state, 'ready')
})

test('null means NOT ASKED and yields checking, never a negative answer', () => {
  assert.equal(assess({ ...ready, writeRelays: null }).state, 'checking')
  assert.equal(assess({ ...ready, scoreListSeen: null }).state, 'checking')
  assert.equal(assess({ ...ready, scoresHere: null }).state, 'checking')
  assert.equal(assess({ ...ready, probeObserved: null }).state, 'checking')
  assert.equal(assess({ ...ready, probeAnonymous: null }).state, 'checking')
})

test('every reachable state has a remedy with something to say', () => {
  for (const state of ['no-relay-list', 'no-usable-relays', 'no-score-list', 'no-rank-service',
    'no-scores-yet', 'projection-pending', 'ready', 'checking']) {
    assert.ok(REMEDY[state]?.say, `${state} has no sentence`)
  }
})

test('an r tag with no marker means BOTH', () => {
  // Read wrong, this reports "no write relays" for most real relay lists.
  const relays = writeRelays({ tags: [
    ['r', 'wss://both.example'],
    ['r', 'wss://written.example', 'write'],
    ['r', 'wss://read-only.example', 'read'],
    ['r', 'https://not-a-socket.example'],
  ] })
  assert.deepEqual(relays, ['wss://both.example', 'wss://written.example'])
})

test('a rank entry needs all three fields', () => {
  assert.equal(rankProvider({ tags: [['30382:followers', SERVICE, 'wss://x']] }), null, 'followers cannot rank')
  assert.equal(rankProvider({ tags: [['30382:rank', SERVICE]] }), null, 'no relay hint resolves to nothing')
  assert.deepEqual(rankProvider({ tags: [['30382:rank', SERVICE, 'wss://hint.example']] }),
    { service: SERVICE, relay: 'wss://hint.example' })
})

test('Blossom servers are https only, and deduplicated', () => {
  assert.deepEqual(blossomServers({ tags: [
    ['server', 'https://one.example/'],
    ['server', 'http://insecure.example'],
    ['server', 'https://one.example'],
  ] }), ['https://one.example'])
  assert.equal(blossomServers(null), null, 'not asked is not the same as none')
})

test('the ranked probe always carries `since` and never a trust floor', () => {
  const observed = rankedProbe(OBSERVER, 1_786_900_000)
  assert.equal(observed.since, 1_786_900_000, 'without `since` this search times out and both sides read zero')
  assert.equal(observed.search, `observer:${OBSERVER} sort:rank`)
  assert.doesNotMatch(observed.search, /filter:rank:gte/, 'a floor here would hide the degradation it exists to catch')
  // A bare `sort:rank` is CLOSED by the relay's auth gate now; `include:spam`
  // opens it and leaves the anonymous ranking intact (measured 2026-08-30).
  assert.equal(rankedProbe(null, 1).search, 'include:spam sort:rank')
})
