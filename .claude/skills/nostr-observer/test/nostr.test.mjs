// bech32, both directions.
//
// Decoding is how the npub becomes the `observer:<pubkey>` token — get it
// wrong and the relay ranks for somebody else, or for nobody. Encoding is how
// the paper names a person who published no name, and the brief's rule is that
// a raw hex string never reaches the page.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { toHex, toNpub, shortNpub, toNevent, fromNevent, toNaddr, fromNaddr, toZapStreamUrl, streamWriterUrl, toShopstrUrl, classifiedWriterUrl, toNjumpCalendarUrl, calendarWriterUrl, tagValue, tagsNamed } from '../scripts/nostr.mjs'

// The NIP-19 worked example.
const HEX = '3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d'
const NPUB = 'npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6'

test('the NIP-19 worked example, both ways', () => {
  assert.equal(toNpub(HEX), NPUB)
  assert.equal(toHex(NPUB), HEX)
})

test('round-trips for arbitrary keys', () => {
  for (const byte of ['00', '01', '7f', 'ff', 'ab']) {
    const hex = byte.repeat(32)
    assert.equal(toHex(toNpub(hex)), hex)
  }
})

test('bare hex is accepted and lowercased', () => {
  assert.equal(toHex(HEX.toUpperCase()), HEX)
})

test('a mistyped npub is refused, not silently decoded', () => {
  // The failure this prevents is the worst kind: a real-looking paper about
  // somebody else's world, or about nobody's.
  assert.throws(() => toHex(NPUB.slice(0, -1) + '7'), /does not checksum/)
  assert.throws(() => toHex('nsec1' + NPUB.slice(5)), /Not an npub|checksum/)
  assert.throws(() => toHex('hello'), /Not an npub/)
  assert.throws(() => toHex(''), /Not an npub/)
  assert.throws(() => toHex('npub1qqqqq'), /checksum|wrong length/)
})

test('whitespace around a pasted npub is tolerated', () => {
  assert.equal(toHex(`  ${NPUB}\n`), HEX)
})

test('a short name is an npub, never hex', () => {
  const short = shortNpub(HEX)
  assert.match(short, /^npub1/)
  assert.doesNotMatch(short, new RegExp(HEX.slice(0, 12)))
  assert.ok(short.length < 20)
})

test('an nevent round-trips an event id, and extra TLV fields still yield the id', () => {
  const id = 'a'.repeat(64)
  assert.equal(fromNevent(toNevent(id)), id)
  // A jumble.social note URL carries relays in the nevent. The id is still
  // type 0; we must not require the encoding to be id-only.
  const jumble = 'nevent1qvzqqqqqqypzqczwjmsfnym2zpyg89vtqs95weewpuzgex9v0yln0llycusz084jqythwumn8ghj7anfw3hhytnwdaehgu339e3k7mf0qy2hwumn8ghj7un9d3shjtnyv9kh2uewd9hj7qpq5sltgs3ufmenvu345j7cq56l6vuklz85rzflmay6mjfsnnzflqmqu8zv28'
  assert.equal(fromNevent(jumble), 'a43eb4423c4ef3367235a4bd80535fd3396f88f41893fdf49adc9309cc49f836')
})

test('tag readers', () => {
  const event = { tags: [['status', 'live'], ['r', 'wss://a'], ['r', 'wss://b']] }
  assert.equal(tagValue(event, 'status'), 'live')
  assert.equal(tagValue(event, 'absent'), null)
  assert.equal(tagValue(null, 'status'), null)
  assert.equal(tagsNamed(event, 'r').length, 2)
})

test('an naddr round-trips a live stream address', () => {
  const pubkey = 'cf45a6ba1363ad7ed213a078e710d24115ae721c9b47bd1ebf4458eaefb4c2a5'
  const identifier = '537a365c-f1ec-44ac-af10-22d14a7319fb'
  const naddr = toNaddr({ kind: 30311, pubkey, identifier })
  assert.match(naddr, /^naddr1/)
  assert.deepEqual(fromNaddr(naddr), { kind: 30311, pubkey, identifier })
  const event = { kind: 30311, pubkey, tags: [['d', identifier]] }
  assert.equal(toZapStreamUrl(event), `https://zap.stream/${naddr}`)
  assert.equal(streamWriterUrl('a'.repeat(64)), `https://zap.stream/stream/${'a'.repeat(64)}`)
})

test('an naddr round-trips a classified listing address', () => {
  const pubkey = 'aa11'.repeat(16)
  const identifier = 'tallow-bars'
  const naddr = toNaddr({ kind: 30402, pubkey, identifier })
  assert.match(naddr, /^naddr1/)
  assert.deepEqual(fromNaddr(naddr), { kind: 30402, pubkey, identifier })
  const event = { kind: 30402, pubkey, tags: [['d', identifier]] }
  assert.equal(toShopstrUrl(event), `https://shopstr.store/listing/${naddr}`)
  assert.equal(classifiedWriterUrl('b'.repeat(64)), `https://shopstr.store/listing/${'b'.repeat(64)}`)
})

test('an naddr round-trips a calendar listing address', () => {
  const pubkey = 'bb22'.repeat(16)
  const identifier = 'porto-meetup'
  const naddr = toNaddr({ kind: 31923, pubkey, identifier })
  assert.match(naddr, /^naddr1/)
  assert.deepEqual(fromNaddr(naddr), { kind: 31923, pubkey, identifier })
  const event = { kind: 31923, pubkey, tags: [['d', identifier]] }
  assert.equal(toNjumpCalendarUrl(event), `https://njump.me/${naddr}`)
  assert.equal(calendarWriterUrl('c'.repeat(64)), `https://njump.me/${'c'.repeat(64)}`)
})
