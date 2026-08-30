// Query construction and the two rules a filter cannot express.
//
// The search string is the entire product: `observer:<pk> sort:rank` with a
// floor is the paper, and bare `sort:rank` is the control. Getting either
// wrong produces a page that looks completely normal and is not the product.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { DESKS, DEFAULT_TRUST_FLOOR, filterFor, belongs, parseImeta, shortlist } from '../scripts/corpus.mjs'

const OBSERVER = 'aa'.repeat(32)
const SOMEBODY = 'bb'.repeat(32)
const SINCE = 1_786_800_000
const UNTIL = 1_786_886_400

test('the observed query carries the observer, the sort and the floor', () => {
  const filter = filterFor([1], SINCE, UNTIL, 400, OBSERVER, 20)
  assert.equal(filter.search, `observer:${OBSERVER} sort:rank filter:rank:gte:20`)
})

test('the control run has NO observer and NO floor', () => {
  // Filtering the anonymous read would destroy the only comparison this
  // project makes. `include:spam` is the relay's auth-gate token, not a
  // filter: without it a bare `sort:rank` is CLOSED outright, and with it the
  // query is still the anonymous ranking (measured 2026-08-30).
  const filter = filterFor([1], SINCE, UNTIL, 400, null, 20)
  assert.equal(filter.search, 'include:spam sort:rank')
  assert.doesNotMatch(filter.search, /observer:|filter:rank/)
})

test('the window has BOTH ends', () => {
  // `until` was once carried all the way through and never put into a filter,
  // so a backdated run asked for "the 24 hours ending last Tuesday" and got
  // everything from last Monday to now instead.
  const filter = filterFor([1], SINCE, UNTIL, 400, OBSERVER, 20)
  assert.equal(filter.since, SINCE)
  assert.equal(filter.until, UNTIL)
})

test('the trust floor is 20, and it is not the same lever as limit', () => {
  assert.equal(DEFAULT_TRUST_FLOOR, 20)
  const tight = filterFor([1], SINCE, UNTIL, 10, OBSERVER, 50)
  assert.match(tight.search, /filter:rank:gte:50/)
  assert.equal(tight.limit, 10)
})

test('a live stream that has already ended is not a listing', () => {
  // Measured: of 18 in a 24-hour window, 11 were live and 7 had ended. A
  // filter cannot express "and the status tag says live".
  const live = DESKS.find((d) => d.key === 'live')
  const running = { id: '1', pubkey: SOMEBODY, tags: [['status', 'live']] }
  const finished = { id: '2', pubkey: SOMEBODY, tags: [['status', 'ended']] }
  assert.deepEqual(belongs(live, OBSERVER, [running, finished]).map((e) => e.id), ['1'])
})

test('the reader\'s own posts are not the news', () => {
  const notes = DESKS.find((d) => d.key === 'notes')
  const mine = { id: '1', pubkey: OBSERVER, tags: [] }
  const theirs = { id: '2', pubkey: SOMEBODY, tags: [] }
  assert.deepEqual(belongs(notes, OBSERVER, [mine, theirs]).map((e) => e.id), ['2'])
})

test('video asks for the deprecated kinds too, because that is where the video is', () => {
  // Measured on one 24-hour window at floor 20: kind 21 -> 0, kind 34235 -> 6;
  // kind 22 -> 0, kind 34236 -> 37. Asking only the current kinds prints none.
  assert.deepEqual(DESKS.find((d) => d.key === 'videos').kinds, [21, 34235])
  assert.deepEqual(DESKS.find((d) => d.key === 'shorts').kinds, [22, 34236])
  // Both halves of NIP-52: 31922 is all-day, 31923 is timed.
  assert.deepEqual(DESKS.find((d) => d.key === 'calendar').kinds, [31922, 31923])
})

test('no two desks share a kind', () => {
  // While the desks shared one REQ, two desks claiming the same kind collided
  // and the anonymous control run was filed as news.
  const seen = new Map()
  for (const desk of DESKS) {
    for (const kind of desk.kinds) {
      assert.equal(seen.has(kind), false, `kind ${kind} is on both ${seen.get(kind)} and ${desk.key}`)
      seen.set(kind, desk.key)
    }
  }
})

test('imeta is parsed as space-separated key/value inside each tag element', () => {
  const meta = parseImeta(['imeta', 'url https://ex.example/a.jpg', 'm image/jpeg', 'dim 1200x800', 'alt a cat'])
  assert.equal(meta.url, 'https://ex.example/a.jpg')
  assert.equal(meta.m, 'image/jpeg')
  assert.equal(meta.dim, '1200x800')
  assert.equal(meta.alt, 'a cat')
})

test('the shortlist filters on the declared MIME, not the URL suffix', () => {
  // imeta carries VIDEO as often as stills, and plenty of `.jpg` in a URL is a
  // redirect to something else.
  const events = {
    pictures: [
      { id: 'p1', kind: 20, pubkey: SOMEBODY, content: 'a still', tags: [['imeta', 'url https://ex.example/still.jpg', 'm image/jpeg', 'alt a still']] },
      { id: 'p2', kind: 20, pubkey: SOMEBODY, content: 'a clip', tags: [['imeta', 'url https://ex.example/clip.jpg', 'm video/mp4']] },
      { id: 'p3', kind: 20, pubkey: SOMEBODY, content: 'insecure', tags: [['imeta', 'url http://ex.example/plain.jpg', 'm image/jpeg']] },
    ],
  }
  const art = shortlist(events, {})
  assert.deepEqual(art.map((a) => a.url), ['https://ex.example/still.jpg'])
  assert.equal(art[0].id, 'art-1', 'ids are what the writer cites')
  assert.equal(art[0].alt, 'a still', 'alt is the difference between a caption and a gap')
})

test('the same URL is shortlisted once', () => {
  const tag = ['imeta', 'url https://ex.example/a.jpg', 'm image/jpeg']
  const art = shortlist({ pictures: [
    { id: 'p1', kind: 20, pubkey: SOMEBODY, content: '', tags: [tag] },
    { id: 'p2', kind: 20, pubkey: SOMEBODY, content: '', tags: [tag] },
  ] }, {})
  assert.equal(art.length, 1)
})

// --- the digest is the reader's context, and they pay for it every run -----

test('the digest is bounded, and says what it held back', async () => {
  const { fit, digest, DEFAULT_DIGEST_BUDGET } = await import('../scripts/corpus.mjs')
  const mk = (n, kind, len) => Array.from({ length: n }, (_, i) => ({
    id: String(i).padStart(64, '0'), pubkey: 'aa'.repeat(32), kind, created_at: 1_786_900_000, content: 'x'.repeat(len), tags: [],
  }))
  const desks = { notes: mk(400, 1, 280), articles: mk(100, 30023, 5000), calendar: mk(100, 31923, 300) }
  const { kept, trimmed } = fit(desks)

  assert.ok(Object.keys(trimmed).length > 0, 'a busy window should be trimmed')
  assert.ok(kept.notes.length >= 300, `notes must keep their floor, kept ${kept.notes.length}`)
  assert.ok(kept.articles.length < 100, 'long-form gives way before the notes do')

  const text = digest({ observerNpub: 'n', relay: 'r', floor: 20, since: 0, until: 1, code: 'A', desks, control: [], overlap: 0, profiles: {}, art: [] })
  // NO SILENT CAPS: a digest that quietly drops half the long-form reads as a
  // quiet day for long-form, and a thin honest paper is supposed to mean one.
  for (const key of Object.keys(trimmed)) {
    assert.match(text, new RegExp(`${key}: showing \\d+ of \\d+`), `${key} was trimmed without saying so`)
  }
  assert.ok(text.length < DEFAULT_DIGEST_BUDGET * 1.1, `digest was ${text.length} characters`)
})

test('a quiet day is never trimmed', async () => {
  const { fit } = await import('../scripts/corpus.mjs')
  const quiet = { notes: Array.from({ length: 30 }, (_, i) => ({ id: String(i).padStart(64, '0'), pubkey: 'a', content: 'short', tags: [] })) }
  assert.deepEqual(fit(quiet).trimmed, {})
})
