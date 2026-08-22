// The relay client, against a relay that behaves badly on purpose.
//
// Every case here is a hazard recorded in AGENTS.md as something that was
// found the hard way. They are tests rather than comments because each one
// fails SILENTLY in production: the symptom is an empty list, which looks
// exactly like a quiet day.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { req, closeAll, bytesRead, resetBytesRead, MAX_REQ_BYTES, MAX_SUB_BYTES } from '../scripts/nostr.mjs'
import { fakeRelay, event } from './fakerelay.mjs'

test('an AUTH challenge sent before the answer is not read as the answer', async () => {
  // "search-staging sends an AUTH challenge before answering, even though
  // auth_required is false. Anything resolving on the first non-EVENT frame
  // reads the challenge as the answer."
  const relay = await fakeRelay((filters, sub) => [
    ['AUTH', 'challenge-string'],
    ['EVENT', sub, event('a1')],
    ['EVENT', sub, event('a2')],
    ['EOSE', sub],
  ])
  try {
    const { events } = await req(relay.url, { kinds: [1] })
    assert.equal(events.length, 2, 'the AUTH frame must not end the read')
  } finally { await relay.close() }
})

test('a NOTICE mid-stream does not end the read either', async () => {
  const relay = await fakeRelay((filters, sub) => [
    ['EVENT', sub, event('b1')],
    ['NOTICE', 'restricted: some of your filters were ignored'],
    ['EVENT', sub, event('b2')],
    ['EOSE', sub],
  ])
  try {
    const { events } = await req(relay.url, { kinds: [1] })
    assert.equal(events.length, 2)
  } finally { await relay.close() }
})

test('frames for another subscription are ignored', async () => {
  const relay = await fakeRelay((filters, sub) => [
    ['EVENT', 'somebody-elses-sub', event('c1')],
    ['EVENT', sub, event('c2')],
    ['EOSE', sub],
  ])
  try {
    const { events } = await req(relay.url, { kinds: [1] })
    assert.deepEqual(events.map((e) => e.id), ['c2'])
  } finally { await relay.close() }
})

test('duplicate events are collapsed', async () => {
  const relay = await fakeRelay((filters, sub) => [
    ['EVENT', sub, event('d1')],
    ['EVENT', sub, event('d1')],
    ['EOSE', sub],
  ])
  try {
    const { events } = await req(relay.url, { kinds: [1] })
    assert.equal(events.length, 1)
  } finally { await relay.close() }
})

test('a subscription that says nothing gives up, and says why', async () => {
  // The oversized-REQ failure looks like this from the client side: the frame
  // is dropped with no NOTICE and no CLOSED, and the subscription sits open.
  const relay = await fakeRelay(() => ['silence'])
  try {
    const started = Date.now()
    const { events, note } = await req(relay.url, { kinds: [1] }, { idleMs: 300 })
    assert.deepEqual(events, [])
    assert.equal(note, 'idle')
    assert.ok(Date.now() - started < 3_000, 'must not wait on the wall-clock guard')
  } finally { await relay.close() }
})

test('the idle window is not a deadline: a slow relay still finishes', async () => {
  const relay = await fakeRelay((filters, sub) => [['EVENT', sub, event('e1')]])
  const trickle = await fakeRelay((filters, sub) => [
    ['EVENT', sub, event('f1')], ['EVENT', sub, event('f2')],
    ['EVENT', sub, event('f3')], ['EOSE', sub],
  ])
  try {
    const { events } = await req(trickle.url, { kinds: [1] }, { idleMs: 300 })
    assert.equal(events.length, 3)
    // And one that stops talking mid-stream keeps what it already sent.
    const partial = await req(relay.url, { kinds: [1] }, { idleMs: 300 })
    assert.equal(partial.events.length, 1)
    assert.equal(partial.note, 'idle')
  } finally { await relay.close(); await trickle.close() }
})

test('CLOSED carries the relay\'s own sentence back', async () => {
  const relay = await fakeRelay((filters, sub) => [
    ['CLOSED', sub, 'error: bad filter'],
  ])
  try {
    const { events, note } = await req(relay.url, { kinds: [1] }, { idleMs: 300 })
    assert.deepEqual(events, [])
    assert.match(note, /bad filter/)
  } finally { await relay.close() }
})

test('an oversized REQ is refused here rather than dropped there', async () => {
  // The relay discards it in silence, so this is the only place it can be
  // noticed at all. Loudly, and before it is sent.
  const huge = { kinds: [1], authors: Array.from({ length: 6000 }, (_, i) => String(i).padStart(64, '0')) }
  assert.ok(JSON.stringify([huge]).length > MAX_REQ_BYTES, 'fixture must actually be oversized')
  await assert.rejects(() => req('ws://127.0.0.1:1', huge), /over the .* budget/)
})

test('a relay that will not connect resolves empty instead of throwing', async () => {
  const { events, note } = await req('ws://127.0.0.1:1', { kinds: [1] }, { idleMs: 200 })
  assert.deepEqual(events, [])
  assert.ok(note)
})

test('reads to one relay SHARE a socket', async () => {
  // Six reads for the readiness chain and sixteen for a corpus pull used to
  // mean that many TCP and TLS handshakes to a host AGENTS.md says not to
  // hammer — and which advertises a subscription limit of fifty.
  const relay = await fakeRelay((filters, sub) => [['EVENT', sub, event(`x${sub}`)], ['EOSE', sub]])
  try {
    const reads = await Promise.all(Array.from({ length: 8 }, () => req(relay.url, { kinds: [1] })))
    assert.equal(reads.length, 8)
    assert.ok(reads.every((r) => r.events.length === 1), 'every read gets its own answer')
    assert.equal(relay.connections, 1, `dialled ${relay.connections} times, should be once`)
  } finally { closeAll(); await relay.close() }
})

test('concurrent subscriptions do not cross-contaminate', async () => {
  // Each answer must arrive already attributed. Merging them is the bug that
  // filed the anonymous control run as ranked news.
  const relay = await fakeRelay((filters, sub) => [
    ['EVENT', sub, event(`for-${sub}`, { kind: filters[0].kinds[0] })],
    ['EOSE', sub],
  ])
  try {
    const [ones, twenties] = await Promise.all([req(relay.url, { kinds: [1] }), req(relay.url, { kinds: [20] })])
    assert.deepEqual(ones.events.map((e) => e.kind), [1])
    assert.deepEqual(twenties.events.map((e) => e.kind), [20])
  } finally { closeAll(); await relay.close() }
})

test('an AUTH frame does not advance any waiting subscription\'s idle clock', async () => {
  // Two subscriptions, one answered and one left hanging while the relay
  // chatters. The chatter must not be mistaken for the hanging one's answer.
  const relay = await fakeRelay((filters, sub) => (
    filters[0].kinds[0] === 1
      ? [['EVENT', sub, event('answered')], ['EOSE', sub]]
      : [['AUTH', 'challenge']]
  ))
  try {
    const [answered, hanging] = await Promise.all([
      req(relay.url, { kinds: [1] }, { idleMs: 300 }),
      req(relay.url, { kinds: [20] }, { idleMs: 300 }),
    ])
    assert.equal(answered.events.length, 1)
    assert.deepEqual(hanging.events, [])
    assert.equal(hanging.note, 'idle', 'the AUTH frame is not an answer')
  } finally { closeAll(); await relay.close() }
})

test('the byte budget is measured in BYTES', async () => {
  // `.length` under-reports every non-ASCII character, so a filter up to twice
  // the cap passed and was then dropped by the relay in silence.
  const wide = { kinds: [1], search: '\u00e9'.repeat(130_000) } // one char, two bytes
  const frame = JSON.stringify([wide])
  assert.ok(frame.length < MAX_REQ_BYTES, 'fixture must look small by character count')
  assert.ok(Buffer.byteLength(frame) > MAX_REQ_BYTES, 'and be oversized by byte count')
  await assert.rejects(() => req('ws://127.0.0.1:1', wide), /over the .* budget/)
})

// --- how much a read may bring back ------------------------------------------

test('one runaway subscription is cut off, and says so', async () => {
  // MAX_REQ_BYTES guards what we send; nothing guarded what comes back. The
  // idle window bounds time, not volume, so a relay streaming hard for
  // twenty-five seconds could fill the disk. `--relay` takes any URL.
  const fat = 'x'.repeat(200_000)
  const relay = await fakeRelay((filters, sub) => {
    const out = []
    for (let i = 0; i < 200; i++) out.push(['EVENT', sub, event(`fat${i}`, { content: fat })])
    out.push(['EOSE', sub])
    return out
  })
  try {
    resetBytesRead()
    const { events, note } = await req(relay.url, { kinds: [1] }, { idleMs: 2_000 })
    assert.match(note, /^truncated/, `expected truncation, got ${JSON.stringify(note)}`)
    // KEPT, not dropped: an empty desk reads as a quiet day for that desk.
    assert.ok(events.length > 0, 'what already arrived is kept')
    assert.ok(events.length < 200, 'and the rest is not')
    assert.ok(bytesRead() > MAX_SUB_BYTES, 'the budget was actually reached')
  } finally { closeAll(); await relay.close() }
})

test('an ordinary read is nowhere near the budget', async () => {
  // Measured on a live window: the largest single subscription was 452 KB and
  // the whole run 1.75 MB, against a 16 MB cap. The guard must not be near
  // anything real.
  const relay = await fakeRelay((filters, sub) => [
    ...Array.from({ length: 50 }, (_, i) => ['EVENT', sub, event(`n${i}`, { content: 'x'.repeat(500) })]),
    ['EOSE', sub],
  ])
  try {
    resetBytesRead()
    const { events, note } = await req(relay.url, { kinds: [1] })
    assert.equal(events.length, 50)
    assert.equal(note, null, 'a normal read is not truncated')
    assert.ok(bytesRead() < MAX_SUB_BYTES / 100, `read ${bytesRead()} bytes, should be a rounding error`)
  } finally { closeAll(); await relay.close() }
})

test('bytesRead accumulates across subscriptions', async () => {
  const relay = await fakeRelay((filters, sub) => [['EVENT', sub, event(`e${sub}`)], ['EOSE', sub]])
  try {
    resetBytesRead()
    assert.equal(bytesRead(), 0)
    await req(relay.url, { kinds: [1] })
    const one = bytesRead()
    assert.ok(one > 0)
    await req(relay.url, { kinds: [20] })
    assert.ok(bytesRead() > one, 'the run budget is shared, not per-read')
  } finally { closeAll(); await relay.close() }
})
