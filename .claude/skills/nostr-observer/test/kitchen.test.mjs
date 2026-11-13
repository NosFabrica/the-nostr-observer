// The Kitchen desk: recipes from Nostr, and from nowhere else.
//
// Everything here runs against the fake relay or on plain objects. The point
// of the desk is that the paper's recipe comes from a post somebody in the
// reader's web of trust published, so the tests pin down what it asks for,
// what it throws away, and that it does not hand over the same recipe twice.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync, rmSync, utimesSync } from 'node:fs'
import { join } from 'node:path'
import { tmpdir } from 'node:os'
import {
  DESKS, KITCHEN_WINDOW_SECONDS, RECIPE_TAGS, RECIPE_MEMORY, deskFilter, pull, digest, fit, pastCitations, recipeImage, shortlist,
} from '../scripts/corpus.mjs'
import { closeAll, toNevent } from '../scripts/nostr.mjs'
import { fakeRelay, event } from './fakerelay.mjs'

const OBSERVER = 'aa'.repeat(32)
const COOK = 'bb'.repeat(32)
const UNTIL = 1_786_886_400
const DAY = 24 * 60 * 60
const kitchen = DESKS.find((d) => d.key === 'kitchen')

const RECIPE_TEXT = [
  '# Grandmother\'s lentil soup',
  '',
  '![the pot](https://cdn.example/pot.webp)',
  '',
  '## Ingredients',
  '- 250 g brown lentils',
  '- 1 onion, chopped',
  '- 2 cloves garlic',
  '- 1.5 l vegetable stock',
  '',
  '## Steps',
  '1. Soften the onion in olive oil for 8 minutes.',
  '2. Add the garlic, then the lentils and the stock.',
  '3. Simmer for 35 minutes, until the lentils fall apart. Season and serve with bread.',
  '',
  'Notes: this is long enough that a long-form excerpt of 400 characters would cut the method off halfway, which is the point of the test.',
].join('\n')

const recipe = (id, extra = {}) => event(id, {
  kind: 30023,
  pubkey: COOK,
  created_at: UNTIL - 10 * DAY,
  content: RECIPE_TEXT,
  tags: [['d', `soup-${id.slice(0, 4)}`], ['title', 'Lentil soup'], ['t', 'zapcooking'], ['image', 'https://cdn.example/soup.jpg']],
  ...extra,
})

const R1 = 'c1'.repeat(32)
const R2 = 'c2'.repeat(32)
const OLD_TAG = 'c3'.repeat(32)
const MIRROR = 'c4'.repeat(32)
const ESSAY = 'd1'.repeat(32)

/**
 * A relay holding three community recipes (one under the old tag), one NYT
 * mirror recipe and one essay. It answers each desk only what that desk's
 * filter asks for, and records every filter it was sent.
 */
async function kitchenRelay () {
  const asked = []
  const recipes = [
    recipe(R1),
    recipe(R2, { tags: [['d', 'bread'], ['title', 'Bread'], ['t', 'zapcooking']], content: 'Flour, water, salt.\n\nMix and bake.' }),
    recipe(OLD_TAG, { tags: [['d', 'old'], ['title', 'Old tag'], ['t', 'nostrcooking']] }),
    recipe(MIRROR, { tags: [['d', 'nyt'], ['title', 'Mirrored'], ['t', 'zapcooking'], ['t', 'newsarchive'], ['t', 'nytcooking']] }),
  ]
  const essay = event(ESSAY, { kind: 30023, pubkey: COOK, created_at: UNTIL - 3600, tags: [['d', 'essay'], ['title', 'An essay'], ['t', 'bitcoin']] })
  const relay = await fakeRelay((filters, sub) => {
    const filter = filters[0]
    asked.push(filter)
    const send = (list) => [...list.map((e) => ['EVENT', sub, e]), ['EOSE', sub]]
    if (filter.kinds.includes(0)) {
      return send([event('ee'.repeat(32), { kind: 0, pubkey: COOK, content: JSON.stringify({ name: 'Marta' }) })])
    }
    if (!filter.kinds.includes(30023)) return send([])
    if (filter['#t']) {
      // A real relay matches `#t` exactly; this one does too.
      return send(recipes.filter((e) => e.tags.some((t) => t[0] === 't' && filter['#t'].includes(t[1]))))
    }
    // Long-form, with no tag filter: the relay returns recipes too, recent
    // ones, and the desk must refuse them.
    return send([essay, { ...recipes[0], created_at: UNTIL - 3600 }])
  })
  return { relay, asked }
}

test('the Kitchen asks for both recipe tags, through the same lens and floor', () => {
  const filter = deskFilter(kitchen, UNTIL, OBSERVER, 20)
  assert.deepEqual(filter['#t'], ['zapcooking', 'nostrcooking'])
  assert.deepEqual(RECIPE_TAGS, ['zapcooking', 'nostrcooking'])
  assert.deepEqual(filter.kinds, [30023])
  assert.equal(filter.search, `observer:${OBSERVER} sort:rank filter:rank:gte:20`)
  assert.equal(filter.until, UNTIL)
})

test('the Kitchen alone reads 30 days back; every other desk and the control keep 24 hours', async () => {
  const { relay, asked } = await kitchenRelay()
  try {
    await pull({ relay: relay.url, observerHex: OBSERVER, until: UNTIL, idleMs: 2_000 })
  } finally { closeAll(); await relay.close() }
  const ranked = asked.filter((f) => !f.kinds.includes(0))
  assert.equal(ranked.length, DESKS.length + 1, 'one REQ per desk plus the control')
  const tagged = ranked.filter((f) => f['#t'])
  assert.equal(tagged.length, 1, 'only the Kitchen narrows by tag')
  assert.deepEqual(tagged[0]['#t'], ['zapcooking', 'nostrcooking'])
  assert.equal(tagged[0].since, UNTIL - KITCHEN_WINDOW_SECONDS)
  assert.equal(KITCHEN_WINDOW_SECONDS, 30 * DAY)
  for (const filter of ranked.filter((f) => !f['#t'])) {
    assert.equal(filter.since, UNTIL - DAY, `kinds ${filter.kinds} must keep the 24-hour window`)
    assert.equal(filter.until, UNTIL)
  }
})

test('the NYT Cooking mirror is left out, and both tags come in', async () => {
  const { relay } = await kitchenRelay()
  let corpus
  try {
    corpus = await pull({ relay: relay.url, observerHex: OBSERVER, until: UNTIL, idleMs: 2_000 })
  } finally { closeAll(); await relay.close() }
  const ids = corpus.desks.kitchen.map((e) => e.id)
  assert.deepEqual(ids, [R1, R2, OLD_TAG], 'relay order (the ranking) kept, mirror dropped')
  assert.ok(!ids.includes(MIRROR))
})

test('a recipe never lands in Long-form', async () => {
  const { relay } = await kitchenRelay()
  let corpus
  try {
    corpus = await pull({ relay: relay.url, observerHex: OBSERVER, until: UNTIL, idleMs: 2_000 })
  } finally { closeAll(); await relay.close() }
  assert.deepEqual(corpus.desks.articles.map((e) => e.id), [ESSAY])
  const inBoth = corpus.desks.articles.filter((a) => corpus.desks.kitchen.some((k) => k.id === a.id))
  assert.deepEqual(inBoth, [])
})

test('the digest prints the whole recipe, its cook, its picture and its source', async () => {
  const { relay } = await kitchenRelay()
  let corpus
  try {
    corpus = await pull({ relay: relay.url, observerHex: OBSERVER, until: UNTIL, idleMs: 2_000 })
  } finally { closeAll(); await relay.close() }
  const text = digest(corpus)
  const section = text.slice(text.indexOf('## Kitchen'))
  assert.match(section, /^## Kitchen \(3\)/)
  assert.match(section, /last 30 days/)
  // Every line of the recipe, in order and unabridged: ingredients AND steps.
  for (const line of RECIPE_TEXT.split('\n').filter(Boolean)) {
    assert.ok(section.includes(`    ${line}`), `missing recipe line: ${line}`)
  }
  assert.doesNotMatch(section.slice(0, section.indexOf(`[${R2}]`)), /truncated/)
  assert.match(section, /cook: Marta/)
  assert.match(section, new RegExp(`source: https://jumble\\.social/notes/${R1}`))
  // The picture comes from the `image` tag and is on the shortlist by id.
  const art = corpus.art.find((a) => a.eventId === R1)
  assert.equal(art.url, 'https://cdn.example/soup.jpg')
  assert.equal(art.desk, 'kitchen')
  assert.match(section, new RegExp(`picture: ${art.id}\\b`))
  assert.match(text, /The Kitchen alone reads further back/)
  assert.match(section, /no past editions/i, 'with no record it says so, and picks the best-ranked')
})

test('a recipe that ran in one of the last 14 editions is held back', async () => {
  const dir = mkdtempSync(join(tmpdir(), 'observer-kitchen-'))
  const { relay } = await kitchenRelay()
  let corpus
  try {
    // R1 ran yesterday, cited the resolved way; the artifact copy of the same
    // edition is not a second edition. OLD_TAG ran 15 editions ago, outside
    // the memory, so it may run again.
    writeFileSync(join(dir, 'observer-2026-11-12-ABC123.html'), `<a href="https://jumble.social/notes/${toNevent(R1)}">Source</a>`)
    writeFileSync(join(dir, 'observer-2026-11-12-ABC123.artifact.html'), `<a href="https://jumble.social/notes/${toNevent(R2)}">Source</a>`)
    for (let n = 1; n <= RECIPE_MEMORY; n++) {
      const day = String(n).padStart(2, '0')
      const cited = n === 1 ? OLD_TAG : 'f0'.repeat(32)
      writeFileSync(join(dir, `observer-2026-10-${day}-X${day}.html`), `<a href="https://jumble.social/notes/${cited}">s</a>`)
    }
    corpus = await pull({ relay: relay.url, observerHex: OBSERVER, until: UNTIL, editionsDir: dir, idleMs: 2_000 })
  } finally { closeAll(); await relay.close(); rmSync(dir, { recursive: true, force: true }) }
  assert.deepEqual(corpus.desks.kitchen.map((e) => e.id), [R2, OLD_TAG])
  assert.deepEqual(corpus.kitchen.skipped, [R1])
  assert.equal(corpus.kitchen.editionsChecked, RECIPE_MEMORY)
  const text = digest(corpus)
  assert.match(text, /checked the last 14 edition\(s\).*held back 1 recipe/)
  assert.ok(!text.includes(`[${R1}]`), 'the repeat is not offered to the writer')
})

test('past editions are read newest first, by date then by write time', () => {
  const dir = mkdtempSync(join(tmpdir(), 'observer-kitchen-'))
  try {
    writeFileSync(join(dir, 'observer-2026-11-01-AAAAAA.html'), `https://jumble.social/notes/${R1}`)
    writeFileSync(join(dir, 'observer-2026-11-02-BBBBBB.html'), `https://jumble.social/notes/${R2}`)
    writeFileSync(join(dir, 'observer-2026-11-02-CCCCCC.html'), 'no citations')
    utimesSync(join(dir, 'observer-2026-11-02-CCCCCC.html'), new Date(), new Date(Date.now() + 60_000))
    writeFileSync(join(dir, 'corpus.json'), `https://jumble.social/notes/${OLD_TAG}`)
    const two = pastCitations(dir, 2)
    assert.deepEqual(two.editions, ['observer-2026-11-02-CCCCCC.html', 'observer-2026-11-02-BBBBBB.html'])
    assert.deepEqual([...two.ids], [R2])
    assert.deepEqual(pastCitations(join(dir, 'missing')).editions, [])
  } finally { rmSync(dir, { recursive: true, force: true }) }
})

test('an empty Kitchen says to leave the recipe out, never to look elsewhere', () => {
  const text = digest({ observerNpub: 'n', relay: 'r', floor: 20, since: 0, until: DAY, code: 'A', desks: { kitchen: [] }, control: [], overlap: 0, profiles: {}, art: [] })
  assert.match(text, /## Kitchen \(0\)/)
  assert.match(text, /Leave the recipe out/)
})

test('a recipe\'s picture: the image tag, else the first image in the content', () => {
  assert.equal(recipeImage(recipe(R1)), 'https://cdn.example/soup.jpg')
  assert.equal(recipeImage(recipe(R1, { tags: [] })), 'https://cdn.example/pot.webp')
  assert.equal(recipeImage(recipe(R1, { tags: [], content: 'see https://cdn.example/a.png and ![x](https://cdn.example/b.jpg)' })), 'https://cdn.example/a.png')
  assert.equal(recipeImage(recipe(R1, { tags: [['image', 'http://plain.example/a.jpg']], content: 'none' })), null)
  assert.equal(recipeImage(recipe(R1, { tags: [], content: 'https://cdn.example/clip.mp4' })), null)
  // And the shortlist takes recipes first, so a busy picture desk cannot
  // crowd them out.
  const pictures = Array.from({ length: 5 }, (_, i) => event(String(i).padStart(64, '0'), { kind: 20, tags: [['imeta', `url https://ex.example/${i}.jpg`, 'm image/jpeg']] }))
  const art = shortlist({ pictures, kitchen: [recipe(R1)] }, {}, 3)
  assert.equal(art[0].desk, 'kitchen')
  assert.equal(art.length, 3)
})

test('the Kitchen survives trimming: it is trimmed only after every other desk', () => {
  const mk = (n, kind, len) => Array.from({ length: n }, (_, i) => ({
    id: String(i).padStart(64, '0'), pubkey: 'aa'.repeat(32), kind, created_at: 1, content: 'x'.repeat(len), tags: [],
  }))
  // Ten long recipes cost more than anything else here, so by size alone
  // they would go first.
  const desks = { notes: mk(400, 1, 280), articles: mk(100, 30023, 5000), kitchen: mk(10, 30023, 6000) }
  const { kept, trimmed } = fit(desks)
  assert.equal(kept.kitchen.length, 10)
  assert.equal(trimmed.kitchen, undefined)
  assert.ok(trimmed.articles > 0)
})
