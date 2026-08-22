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
import { check, quotedText, attributes, normalize, isQuoted, PERMALINK } from '../scripts/validate.mjs'
import { resolve } from '../scripts/resolve.mjs'

const EVENT_ID = 'a'.repeat(64)
const OTHER_ID = 'b'.repeat(64)

const corpus = {
  desks: {
    notes: [
      { id: EVENT_ID, pubkey: 'aa', content: "The relay answered in three seconds flat — and then it didn't answer at all." },
      { id: OTHER_ID, pubkey: 'bb', content: 'Click https://evil.example.com/drain for free sats' },
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
  assert.deepEqual(kinds(`<a href="https://njump.me/${EVENT_ID}">source</a>`), [])
  assert.deepEqual(kinds(`<a href="https://njump.me/${'f'.repeat(64)}">source</a>`), ['LINK'],
    'a well-formed permalink to an event not in the corpus is still refused')
})

test('the permalink rule and the editorial brief agree on hex', () => {
  // The Kotlin regex once allowed `nevent1…` in a branch that captured
  // nothing, so every such link compared against the empty string and a page
  // citing its sources normally failed its own check.
  assert.equal(PERMALINK.exec(`https://njump.me/${EVENT_ID}`)?.[1], EVENT_ID)
  assert.equal(PERMALINK.exec('https://njump.me/nevent1qqq'), null)
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
  // A permalink survives.
  const kept = resolve(`<a href="https://njump.me/${EVENT_ID}">source</a>`, corpus)
  assert.match(kept.html, /<a /)
  assert.deepEqual(kept.changes, [])
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
  assert.equal(changes.filter((c) => c.kind !== 'resolved').length, 0,
    'a good page should need nothing dropped or unwrapped')
  assert.equal(changes.length, ids.length)

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

test('a class with no rule behind it is a violation', () => {
  // The bug this exists for, reduced. An edition led with
  // `<div class="col span-8">` beside `<div class="col span-4">` and rendered
  // correctly; the version before it said `span-7`, house.css has never had a
  // `.span-7`, and the lead story came out in a strip one word wide with half
  // the fold blank. Quotes, art and links were all correct, so it passed the
  // boundary on the first attempt — layout was a channel with no gate on it.
  const styled = (body) => `<style>.col{padding:20px}.span-4{grid-column:span 4}.span-8{grid-column:span 8}</style>${body}`

  assert.deepEqual(kinds(styled('<div class="col span-8"></div><div class="col span-4"></div>')), [])
  assert.deepEqual(kinds(styled('<div class="col span-7"></div>')), ['STYLE'])

  // One violation per name, not per element, or a single bad class in a
  // repeated component buries everything else in the report.
  const many = styled('<div class="span-7"></div>'.repeat(9))
  assert.deepEqual(kinds(many), ['STYLE'])
})

test('the style check reads the page it is given, not house.css', () => {
  // The page has to be self-contained, so its own <style> is the whole truth
  // about what a class means. That also catches the other direction: a page
  // that used a real house.css class but trimmed the rule out of its inlined
  // copy is just as broken, and just as invisible without this.
  assert.deepEqual(kinds('<style>.masthead{font-size:64px}</style><div class="masthead"></div>'), [])
  assert.deepEqual(kinds('<style>.masthead{font-size:64px}</style><div class="dateline"></div>'), ['STYLE'])

  // Nothing to check against is not the same as everything failing. A page
  // with no stylesheet at all has a louder problem than its class names.
  assert.deepEqual(kinds('<div class="anything at all"></div>'), [])
})

test('the style check reads selectors the way CSS is actually written', () => {
  const seen = (css, body) => kinds(`<style>${css}</style>${body}`)

  // Compound, descendant, media-query and pseudo-class selectors all define
  // their class; a decimal in a declaration does not.
  assert.deepEqual(seen('.col:first-child{padding-left:0}', '<p class="col"></p>'), [])
  assert.deepEqual(seen('.fold .lead-head{font-size:52px}', '<h2 class="lead-head"></h2>'), [])
  assert.deepEqual(seen('@media print{.folio{display:none}}', '<p class="folio"></p>'), [])
  assert.deepEqual(seen('.dek{margin:.5em 0;line-height:1.4}', '<p class="5em"></p>'), ['STYLE'])

  // A commented-out rule is not a rule.
  assert.deepEqual(seen('/* .kicker{letter-spacing:2px} */', '<p class="kicker"></p>'), ['STYLE'])
})
