// bech32, both directions.
//
// Decoding is how the npub becomes the `observer:<pubkey>` token — get it
// wrong and the relay ranks for somebody else, or for nobody. Encoding is how
// the paper names a person who published no name, and the brief's rule is that
// a raw hex string never reaches the page.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { toHex, toNpub, shortNpub, tagValue, tagsNamed } from '../scripts/nostr.mjs'

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

test('tag readers', () => {
  const event = { tags: [['status', 'live'], ['r', 'wss://a'], ['r', 'wss://b']] }
  assert.equal(tagValue(event, 'status'), 'live')
  assert.equal(tagValue(event, 'absent'), null)
  assert.equal(tagValue(null, 'status'), null)
  assert.equal(tagsNamed(event, 'r').length, 2)
})
