// The boundary, from both sides.
//
// Adversarial cases answer "does it stop the bad things". The golden edition
// answers the other half, which is the likelier way to ship a broken product:
// DOES IT LEAVE A GOOD PAGE ALONE? A check that quietly rejects a real
// broadsheet passes every adversarial test here and prints nothing every
// morning.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { check, quotedText, attributes, normalize, isQuoted, PERMALINK, toPermalink, permalinkTarget, streamLinkTarget, toStreamLink } from '../scripts/validate.mjs'
import { resolve } from '../scripts/resolve.mjs'

const EVENT_ID = 'a'.repeat(64)
const OTHER_ID = 'b'.repeat(64)

const STREAM_ID = 'e'.repeat(64)
const STREAM_PK = 'cf45a6ba1363ad7ed213a078e710d24115ae721c9b47bd1ebf4458eaefb4c2a5'
const STREAM_D = '537a365c-f1ec-44ac-af10-22d14a7319fb'

const corpus = {
  desks: {
    notes: [
      { id: EVENT_ID, pubkey: 'aa', content: "The relay answered in three seconds flat — and then it didn't answer at all." },
      { id: OTHER_ID, pubkey: 'bb', content: 'Click https://evil.example.com/drain for free sats' },
    ],
    live: [
      { id: STREAM_ID, kind: 30311, pubkey: STREAM_PK, tags: [['d', STREAM_D], ['title', 'NoGood Radio'], ['status', 'live']], content: '' },
    ],
  },
  control: [{ id: 'c'.repeat(64), pubkey: 'cc', content: 'Only the anonymous read ever saw this sentence.' }],
  art: [{ id: 'art-1', url: 'https://blossom.example.com/real.jpg' }],
}

const kinds = (html) => check(html, corpus).violations.map((v) => v.kind)

test('a verbatim quote passes', () => {
  assert.deepEqual(kinds('<q>The relay answered in three seconds flat</q>'), [])
})

test('typographic normalisation is forgiven; meaning is not', () => {
  // A model that renders ' as ’ has not changed what anybody said.
  assert.deepEqual(kinds('<q>and then it didn&rsquo;t answer at all</q>'), [])
  assert.deepEqual(kinds('<q>and then it DID answer at all</q>'), ['QUOTE'])
})

test('elision is allowed, in order, within ONE event', () => {
  assert.deepEqual(kinds('<q>The relay answered … answer at all</q>'), [])
  // Out of order is not elision.
  assert.deepEqual(kinds('<q>answer at all … The relay answered</q>'), ['QUOTE'])
  // Stitching two people into one sentence is what single-event stops.
  assert.deepEqual(kinds('<q>The relay answered … for free sats</q>'), ['QUOTE'])
})

test('a fabricated quote is caught', () => {
  assert.deepEqual(kinds('<blockquote>This sentence was never posted by anybody.</blockquote>'), ['QUOTE'])
})

test('the control run is NOT quotable', () => {
  // It is a measurement of the network, not part of the paper, and it is not
  // in the digest the writer reads.
  assert.deepEqual(kinds('<q>Only the anonymous read ever saw this sentence.</q>'), ['QUOTE'])
})

test('paraphrase is not checked, because paraphrase is journalism', () => {
  assert.deepEqual(kinds('<p>The relay was quick, then silent.</p>'), [])
})

test('an invented image is caught', () => {
  assert.deepEqual(kinds('<img src="https://blossom.example.com/invented.jpg">'), ['IMAGE'])
  assert.deepEqual(kinds('<img src="https://blossom.example.com/real.jpg">'), [])
})

test('PRESENCE IN THE CORPUS IS EVIDENCE OF NOTHING', () => {
  // The URL below is in the corpus — somebody posted it. An earlier version of
  // this rule allowlisted every URL that appeared there, and posting a
  // phishing link was enough to get it allowlisted, under the reader's own
  // masthead, signed by them.
  assert.deepEqual(kinds('<a href="https://evil.example.com/drain">free sats</a>'), ['LINK'])
})

test('a permalink to an event we actually read is the one allowed link', () => {
  const href = toPermalink(EVENT_ID)
  assert.deepEqual(kinds(`<a href="${href}">source</a>`), [])
  assert.deepEqual(kinds(`<a href="${toPermalink('f'.repeat(64))}">source</a>`), ['LINK'],
    'a well-formed permalink to an event not in the corpus is still refused')
  assert.deepEqual(kinds(`<a href="https://njump.me/${EVENT_ID}">source</a>`), ['LINK'],
    'njump.me is no longer the permalink host; resolve rewrites it')
  assert.deepEqual(kinds(`<a href="https://jumble.social/notes/${EVENT_ID}">source</a>`), ['LINK'],
    'bare hex in the jumble path is the writer form; resolve encodes it')
})

test('the permalink is a jumble.social nevent, decoded rather than captured', () => {
  // The Kotlin regex once allowed `nevent1…` in a branch that captured
  // nothing, so every such link compared against the empty string and a page
  // citing its sources normally failed its own check. Decode, or refuse.
  const href = toPermalink(EVENT_ID)
  assert.equal(permalinkTarget(href), EVENT_ID)
  assert.match(href, /^https:\/\/jumble\.social\/notes\/nevent1/)
  assert.equal(permalinkTarget('https://jumble.social/notes/nevent1qqq'), null)
  assert.equal(PERMALINK.exec(`https://njump.me/${EVENT_ID}`), null)
})

test('markup with no sanitizer to strip it is REFUSED', () => {
  for (const bad of [
    '<script>alert(1)</script>',
    '<p onclick="steal()">x</p>',
    '<a href="javascript:void(0)">x</a>',
    '<iframe src="https://x.example"></iframe>',
    '<form action="/x"><input name="y"></form>',
  ]) {
    assert.ok(kinds(bad).includes('MARKUP'), `not refused: ${bad}`)
  }
})

test('resolve turns art ids into URLs and drops unknown ones', () => {
  const { html, changes } = resolve('<figure><img src="art-1"><figcaption>c</figcaption></figure>', corpus)
  assert.match(html, /src="https:\/\/blossom\.example\.com\/real\.jpg"/)
  assert.deepEqual(changes.map((c) => c.kind), ['resolved'])

  const bad = resolve('<p>before</p><figure><img src="art-9"><figcaption>c</figcaption></figure><p>after</p>', corpus)
  assert.doesNotMatch(bad.html, /art-9|figcaption/, 'the whole figure goes, not just the img')
  assert.match(bad.html, /before[\s\S]*after/)
  assert.deepEqual(bad.changes.map((c) => c.kind), ['dropped'])
})

test('resolve unwraps a link to the open web but keeps its text, and SAYS SO', () => {
  const { html, changes } = resolve('<p>see <a href="https://evil.example.com/drain">free sats</a> today</p>', corpus)
  assert.equal(html, '<p>see free sats today</p>')
  assert.deepEqual(changes, [{ kind: 'unwrapped', detail: 'https://evil.example.com/drain' }])
})

test('resolve encodes a cited event id as a jumble.social nevent permalink', () => {
  const writer = `https://jumble.social/notes/${EVENT_ID}`
  const { html, changes } = resolve(`<a href="${writer}">source</a>`, corpus)
  const canonical = toPermalink(EVENT_ID)
  assert.match(html, new RegExp(`href="${canonical}"`))
  assert.match(html, /target="_blank"/)
  assert.match(html, /rel="[^"]*noopener/)
  assert.deepEqual(changes.map((c) => c.kind), ['permalink'])
  // A leftover njump.me hex URL is upgraded the same way, so an old page
  // still ships rather than having every citation unwrapped.
  const legacy = resolve(`<a href="https://njump.me/${EVENT_ID}">source</a>`, corpus)
  assert.match(legacy.html, new RegExp(`href="${canonical}"`))
  assert.match(legacy.html, /target="_blank"/)
})

test('a permalink already in canonical form still opens in a new tab', () => {
  const canonical = toPermalink(EVENT_ID)
  const { html, changes } = resolve(`<a href="${canonical}">source</a>`, corpus)
  assert.match(html, /target="_blank"/)
  assert.match(html, /rel="[^"]*noopener/)
  assert.deepEqual(changes.map((c) => c.kind), [])
})

test('a verified zap.stream watch link is allowed after resolve', () => {
  const writer = `https://zap.stream/stream/${STREAM_ID}`
  const canonical = toStreamLink(corpus.desks.live[0])
  const { html, changes } = resolve(`<a href="${writer}">NoGood Radio</a>`, corpus)
  assert.match(html, new RegExp(`href="${canonical.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')}"`))
  assert.match(html, /target="_blank"/)
  assert.deepEqual(changes.map((c) => c.kind), ['stream'])
  assert.deepEqual(check(html, corpus).violations, [])
  assert.equal(streamLinkTarget(canonical, corpus), STREAM_ID)
  assert.equal(streamLinkTarget('https://zap.stream/stream/' + 'f'.repeat(64), corpus), null)
  assert.deepEqual(kinds(`<a href="${writer}">NoGood Radio</a>`), ['LINK'],
    'writer form must be encoded before validate')
})

test('a zap.stream URL copied from a post body is still refused', () => {
  const invented = toStreamLink({ kind: 30311, pubkey: 'd'.repeat(64), tags: [['d', 'fake-stream']] })
  assert.deepEqual(kinds(`<a href="${invented}">listen</a>`), ['LINK'])
})

test('resolve then validate leaves nothing for validate to complain about', () => {
  const page = `<figure><img src="art-1"><figcaption>c</figcaption></figure>`
    + `<p>see <a href="https://evil.example.com/drain">free sats</a></p>`
  assert.deepEqual(check(resolve(page, corpus).html, corpus).violations, [])
})

test('nested and multi-line quotes are found', () => {
  const found = quotedText('<blockquote><p>one</p>\n<p><em>two</em></p></blockquote><q>three</q>')
  assert.deepEqual(found, ['one two', 'three'])
})

test('attributes are read whatever the quoting', () => {
  assert.deepEqual(attributes(`<img src="a"><img src='b'><img src=c>`, 'img', 'src'), ['a', 'b', 'c'])
})

// --- the other half: does it leave a good page alone? ----------------------

const GOLDEN = fileURLToPath(new URL('../../../../generator/src/test/resources/prototype-edition.html', import.meta.url))

test('THE GOLDEN EDITION survives the boundary intact', { skip: !existsSync(GOLDEN) && 'fixture lives in the full repository' }, () => {
  // 56 KB of hand-written broadsheet from a real 24-hour window through a real
  // lens: seven figures, a reverse-ink panel, market tables, a nine-section
  // below-the-fold. The corpus is derived FROM the page, so a clean result
  // means the checker and the brief agree about what a good page looks like.
  // Anything it flags here is a false positive by construction.
  const page = readFileSync(GOLDEN, 'utf8')

  const ids = [...new Set(attributes(page, 'img', 'src'))]
  assert.ok(ids.length > 0, 'fixture should cite art')
  assert.ok(ids.every((id) => /^art-\d+$/.test(id)),
    'the brief says use the id and never a raw URL in src; the fixture must match it')

  const golden = {
    desks: { notes: quotedText(page).map((text, n) => ({ id: String(n).padStart(64, '0'), pubkey: 'aa', content: text })) },
    control: [],
    art: ids.map((id) => ({ id, url: `https://blossom.example.com/${id}.jpg` })),
  }

  const { html, changes } = resolve(page, golden)
  assert.equal(changes.filter((c) => c.kind !== 'resolved' && c.kind !== 'permalink').length, 0,
    'a good page should need nothing dropped or unwrapped')
  assert.equal(changes.filter((c) => c.kind === 'resolved').length, ids.length)

  const report = check(html, golden)
  assert.ok(report.quotes.length > 0, 'fixture should quote people')
  assert.deepEqual(report.violations, [], 'the boundary must not damage a real broadsheet')
})

test('a real page head is not an attack', async () => {
  // Found by printing an actual edition: banning <meta> outright to stop
  // <meta refresh> rejects charset and viewport, which every page has. The
  // golden fixture is a body fragment, so it had no <head> to catch this.
  // Same false-positive class as the prose that read as an event handler.
  const head = '<meta charset="utf-8">'
    + '<meta name="viewport" content="width=device-width, initial-scale=1">'
    + '<title>The Nostr Observer</title>'
  assert.deepEqual(kinds(head), [])

  // Only the redirecting form is refused.
  assert.deepEqual(kinds('<meta http-equiv="refresh" content="0;url=https://evil.example">'), ['MARKUP'])
  assert.deepEqual(kinds('<base href="https://evil.example/">'), ['MARKUP'],
    '<base> rewrites every relative URL on the page and is refused outright')
})
