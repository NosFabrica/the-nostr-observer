import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, existsSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import {
  parseName,
  headlineOf,
  formatDate,
  matchSelectors,
  papersIn,
  add,
  remove,
  check,
  writeSite,
  renderIndex,
  withFavicon,
  localToday,
} from '../scripts/site.mjs'

function paper (file, headline) {
  return `<!doctype html><html><head><title>The Nostr Observer</title></head>
<body><h1>The Nostr Observer</h1><h2 class="main-head">${headline}</h2></body></html>`
}

function stage () {
  const root = mkdtempSync(join(tmpdir(), 'observer-pages-'))
  const editions = join(root, 'editions')
  const dist = join(root, 'dist')
  mkdirSync(editions)
  mkdirSync(dist)
  writeFileSync(join(editions, 'observer-2026-08-27-1EAF35.html'), paper('x', 'In Goma, Life Without Banks Turns to Bitcoin'))
  writeFileSync(join(editions, 'observer-2026-08-25-16946F.html'), paper('x', 'First paper of the 25th'))
  writeFileSync(join(editions, 'observer-2026-08-25-183A1C.html'), paper('x', 'Second paper of the 25th'))
  writeFileSync(join(editions, 'corpus.json'), '{"events":[]}')
  writeFileSync(join(editions, 'digest.md'), '# digest')
  return { root, editions, dist }
}

test('parseName accepts only observer-date-code.html', () => {
  assert.deepEqual(parseName('observer-2026-08-27-1EAF35.html'), {
    file: 'observer-2026-08-27-1EAF35.html',
    date: '2026-08-27',
    code: '1EAF35',
  })
  assert.equal(parseName('corpus.json'), null)
  assert.equal(parseName('index.html'), null)
  assert.equal(parseName('observer-today.html'), null)
})

test('headlineOf takes the first h2, not the masthead', () => {
  assert.equal(
    headlineOf(paper('x', 'In Goma, Life Without Banks Turns to Bitcoin')),
    'In Goma, Life Without Banks Turns to Bitcoin',
  )
})

test('formatDate is UTC, so evening in the Americas does not slip the day', () => {
  assert.equal(formatDate('2026-08-27'), 'Thursday, August 27, 2026')
})

test('list ignores the corpus sitting next to the papers', () => {
  const { editions } = stage()
  const listed = papersIn(editions).map((p) => p.file)
  assert.deepEqual(listed, [
    'observer-2026-08-27-1EAF35.html',
    'observer-2026-08-25-183A1C.html',
    'observer-2026-08-25-16946F.html',
  ])
})

test('a date that printed twice selects both papers', () => {
  const { editions } = stage()
  const { papers, missing } = matchSelectors(papersIn(editions), ['2026-08-25'])
  assert.equal(missing.length, 0)
  assert.deepEqual(papers.map((p) => p.code).sort(), ['16946F', '183A1C'])
})

test('today is the local calendar date, not UTC', () => {
  const { editions } = stage()
  const now = new Date(2026, 7, 27, 22, 0, 0)
  assert.equal(localToday(now), '2026-08-27')
  const { papers } = matchSelectors(papersIn(editions), ['today'], now)
  assert.equal(papers.length, 1)
  assert.equal(papers[0].code, '1EAF35')
})

test('add copies only the named HTML into dist', () => {
  const { editions, dist } = stage()
  add(editions, dist, ['1EAF35'])
  assert.equal(existsSync(join(dist, 'observer-2026-08-27-1EAF35.html')), true)
  assert.equal(existsSync(join(dist, 'corpus.json')), false)
  assert.equal(existsSync(join(dist, 'observer-2026-08-25-16946F.html')), false)
  assert.equal(existsSync(join(dist, 'index.html')), true)
  assert.equal(existsSync(join(dist, 'vercel.json')), true)
})

test('add refuses a selector that is not an edition', () => {
  const { editions, dist } = stage()
  assert.throws(() => add(editions, dist, ['corpus.json']), /not in editions/)
})

test('check fails closed if the corpus landed in dist', () => {
  const { dist } = stage()
  writeFileSync(join(dist, 'corpus.json'), '{}')
  const result = check(dist)
  assert.equal(result.ok, false)
  assert.ok(result.junk.includes('corpus.json'))
})

test('check accepts a shelf of papers plus the generated furniture', () => {
  const { editions, dist } = stage()
  add(editions, dist, ['1EAF35'])
  const result = check(dist)
  assert.equal(result.ok, true)
  assert.equal(result.papers.length, 1)
})

test('index lists newest first and escapes a headline', () => {
  const html = renderIndex([
    { file: 'observer-2026-08-27-1EAF35.html', date: '2026-08-27', code: '1EAF35', headline: 'We <ran> it "once" & twice' },
    { file: 'observer-2026-08-22-D8C3EA.html', date: '2026-08-22', code: 'D8C3EA', headline: 'Older' },
  ])
  assert.ok(html.indexOf('We &lt;ran&gt; it &quot;once&quot; &amp; twice') > -1)
  assert.ok(html.indexOf('href="observer-2026-08-27-1EAF35.html"') > -1)
  assert.ok(html.indexOf('Thursday, August 27, 2026') > -1)
  assert.ok(html.indexOf('The shelf is empty') === -1)
})

test('index and a stamped edition both point at the site favicon', () => {
  const html = renderIndex([])
  assert.ok(html.includes('href="/favicon.svg"'))
  const stamped = withFavicon('<!doctype html><html><head><title>T</title></head><body></body></html>')
  assert.ok(stamped.includes('href="/favicon.svg"'))
  assert.equal(withFavicon(stamped), stamped, 'stamping twice must not duplicate the link')
})

test('writeSite copies the favicon into dist', () => {
  const { dist } = stage()
  writeSite(dist)
  assert.equal(existsSync(join(dist, 'favicon.svg')), true)
  const result = check(dist)
  assert.equal(result.ok, true)
})

test('index is newsprint, not the browser dark scheme', () => {
  const html = renderIndex([])
  assert.equal(html.includes('prefers-color-scheme'), false)
  assert.ok(html.includes('color-scheme: light'))
})

test('an empty shelf still writes a page, not a blank directory listing', () => {
  const html = renderIndex([])
  assert.ok(html.includes('The shelf is empty'))
  assert.match(html, /<title>The Nostr Observer/)
})

test('remove takes a paper off the shelf and leaves the others', () => {
  const { editions, dist } = stage()
  add(editions, dist, ['2026-08-25'])
  assert.equal(papersIn(dist).length, 2)
  remove(dist, ['16946F'])
  assert.deepEqual(papersIn(dist).map((p) => p.code), ['183A1C'])
  const index = readFileSync(join(dist, 'index.html'), 'utf8')
  assert.ok(index.includes('183A1C'))
  assert.ok(!index.includes('16946F'))
})

test('writeSite on an empty dist is a valid empty shelf', () => {
  const { dist } = stage()
  writeSite(dist)
  assert.ok(readFileSync(join(dist, 'index.html'), 'utf8').includes('The shelf is empty'))
})
