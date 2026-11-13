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
import {
  check, quotedText, attributes, normalize, isQuoted, EVENT_LINK, toPermalink, toEventLink, toPersonLink, eventLinkTarget, personLinkTarget,
} from '../scripts/validate.mjs'
import { toNevent, toNaddr, toNpub } from '../scripts/nostr.mjs'
import { resolve } from '../scripts/resolve.mjs'

const EVENT_ID = 'a'.repeat(64)
const OTHER_ID = 'b'.repeat(64)
const AUTHOR = 'a0'.repeat(32)
const SPAMMER = 'b0'.repeat(32)

const STREAM_ID = 'e'.repeat(64)
const STREAM_PK = 'cf45a6ba1363ad7ed213a078e710d24115ae721c9b47bd1ebf4458eaefb4c2a5'
const STREAM_D = '537a365c-f1ec-44ac-af10-22d14a7319fb'

const LISTING_ID = '11'.repeat(32)
const LISTING_PK = 'aa11'.repeat(16)
const LISTING_D = 'tallow-bars'

const CALENDAR_ID = '22'.repeat(32)
const CALENDAR_PK = 'bb22'.repeat(16)
const CALENDAR_D = 'porto-meetup'

const ARTICLE_ID = '33'.repeat(32)
const RECIPE_ID = '44'.repeat(32)
const WRITER_PK = 'cc33'.repeat(16)
const UNADDRESSED_ID = '55'.repeat(32)

const corpus = {
  desks: {
    notes: [
      { id: EVENT_ID, kind: 1, pubkey: AUTHOR, content: "The relay answered in three seconds flat — and then it didn't answer at all." },
      { id: OTHER_ID, kind: 1, pubkey: SPAMMER, content: 'Click https://evil.example.com/drain for free sats' },
    ],
    live: [
      { id: STREAM_ID, kind: 30311, pubkey: STREAM_PK, tags: [['d', STREAM_D], ['title', 'NoGood Radio'], ['status', 'live']], content: '' },
    ],
    classifieds: [
      { id: LISTING_ID, kind: 30402, pubkey: LISTING_PK, tags: [['d', LISTING_D], ['title', '4 Bars Rough Cut Tallow'], ['price', '35', 'USD']], content: '' },
    ],
    calendar: [
      { id: CALENDAR_ID, kind: 31923, pubkey: CALENDAR_PK, tags: [['d', CALENDAR_D], ['title', 'Bitcoin Meetup in Porto']], content: '' },
    ],
    articles: [
      { id: ARTICLE_ID, kind: 30023, pubkey: WRITER_PK, tags: [['d', 'on-relays'], ['title', 'On relays']], content: 'A long essay.' },
      // Addressable with no d tag: no address to build, so it is never linked.
      { id: UNADDRESSED_ID, kind: 30023, pubkey: WRITER_PK, tags: [['title', 'Lost']], content: 'No address.' },
    ],
    kitchen: [
      { id: RECIPE_ID, kind: 30023, pubkey: WRITER_PK, tags: [['d', 'lentil-soup'], ['title', 'Lentil soup'], ['t', 'zapcooking']], content: '250 g brown lentils' },
    ],
  },
  control: [{ id: 'c'.repeat(64), pubkey: 'cc'.repeat(32), content: 'Only the anonymous read ever saw this sentence.' }],
  art: [{ id: 'art-1', url: 'https://blossom.example.com/real.jpg' }],
}

const event = (id) => Object.values(corpus.desks).flat().find((e) => e.id === id)
const naddrOf = (id) => {
  const e = event(id)
  return toNaddr({ kind: e.kind, pubkey: e.pubkey, identifier: e.tags.find((t) => t[0] === 'd')[1] })
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

// --- links: Brainstorm and nothing else -----------------------------------

const resolved = (href, text = 'source') => resolve(`<a href="${href}">${text}</a>`, corpus)
const hrefOf = (html) => /href="([^"]*)"/.exec(html)?.[1]
const HINTED = 'nevent1qvzqqqqqqypzqczwjmsfnym2zpyg89vtqs95weewpuzgex9v0yln0llycusz084jqythwumn8ghj7anfw3hhytnwdaehgu339e3k7mf0qy2hwumn8ghj7un9d3shjtnyv9kh2uewd9hj7qpq5sltgs3ufmenvu345j7cq56l6vuklz85rzflmay6mjfsnnzflqmqu8zv28'
const HINTED_ID = 'a43eb4423c4ef3367235a4bd80535fd3396f88f41893fdf49adc9309cc49f836'

test('a note becomes a Brainstorm nevent', () => {
  const writer = `https://brainstorm.world/e/${EVENT_ID}`
  const { html, changes } = resolved(writer)
  assert.equal(hrefOf(html), `https://brainstorm.world/e/${toNevent(EVENT_ID)}`)
  assert.equal(hrefOf(html), toPermalink(EVENT_ID))
  assert.match(html, /target="_blank"/)
  assert.match(html, /rel="[^"]*noopener/)
  assert.deepEqual(changes.map((c) => c.kind), ['permalink'])
  assert.deepEqual(check(html, corpus).violations, [])
  assert.equal(eventLinkTarget(hrefOf(html), corpus), EVENT_ID)
  assert.deepEqual(kinds(`<a href="${writer}">source</a>`), ['LINK'],
    'the hex writer form must be encoded before validate')
})

test('the permalink is decoded rather than captured', () => {
  // The Kotlin regex once allowed `nevent1…` in a branch that captured
  // nothing, so every such link compared against the empty string and a page
  // citing its sources normally failed its own check. Decode, or refuse.
  const href = toPermalink(EVENT_ID)
  assert.equal(eventLinkTarget(href, corpus), EVENT_ID)
  assert.equal(eventLinkTarget('https://brainstorm.world/e/nevent1qqq', corpus), null)
  assert.equal(EVENT_LINK.exec(`https://brainstorm.world/e/${EVENT_ID}`), null)
  assert.deepEqual(kinds(`<a href="${toPermalink('f'.repeat(64))}">source</a>`), ['LINK'],
    'a well-formed permalink to an event not in the corpus is still refused')
})

test('a link already in canonical form is kept and still opens in a new tab', () => {
  for (const href of [toPermalink(EVENT_ID), `https://brainstorm.world/e/${naddrOf(ARTICLE_ID)}`, toPersonLink(AUTHOR)]) {
    const { html, changes } = resolved(href)
    assert.equal(hrefOf(html), href)
    assert.match(html, /target="_blank"/)
    assert.match(html, /rel="[^"]*noopener/)
    assert.deepEqual(changes, [])
    assert.deepEqual(check(html, corpus).violations, [])
  }
})

test('a long-form post and a recipe each become a Brainstorm naddr', () => {
  for (const id of [ARTICLE_ID, RECIPE_ID]) {
    const { html, changes } = resolved(`https://brainstorm.world/e/${id}`)
    assert.equal(hrefOf(html), `https://brainstorm.world/e/${naddrOf(id)}`)
    assert.equal(hrefOf(html), toEventLink(event(id)))
    assert.deepEqual(changes.map((c) => c.kind), ['address'])
    assert.deepEqual(check(html, corpus).violations, [])
    assert.equal(eventLinkTarget(hrefOf(html), corpus), id)
  }
})

test('a calendar entry, a classified and a live stream each become a Brainstorm naddr', () => {
  for (const id of [CALENDAR_ID, LISTING_ID, STREAM_ID]) {
    const writer = `https://brainstorm.world/e/${id}`
    const { html, changes } = resolved(writer, 'the listing')
    assert.equal(hrefOf(html), `https://brainstorm.world/e/${naddrOf(id)}`)
    assert.match(html, /target="_blank"/)
    assert.deepEqual(changes.map((c) => c.kind), ['address'])
    assert.deepEqual(check(html, corpus).violations, [])
    assert.deepEqual(kinds(`<a href="${writer}">x</a>`), ['LINK'], 'writer form must be encoded before validate')
  }
})

test('an nevent naming an ADDRESSABLE event is refused, and resolve turns it into the naddr', () => {
  // An nevent freezes one revision of an event whose whole nature is to be
  // replaced, so the reader clicks through to a meetup whose time has moved.
  // resolve.mjs writes naddrs; this is the half that makes a regression there
  // fail closed, and it is what Validator.kt already did for calendar.
  for (const id of [CALENDAR_ID, LISTING_ID, STREAM_ID, ARTICLE_ID, RECIPE_ID]) {
    const frozen = `https://brainstorm.world/e/${toNevent(id)}`
    assert.deepEqual(kinds(`<a href="${frozen}">x</a>`), ['LINK'])
    const { html, changes } = resolved(frozen)
    assert.equal(hrefOf(html), `https://brainstorm.world/e/${naddrOf(id)}`)
    assert.deepEqual(changes.map((c) => c.kind), ['address'])
  }
})

test('an naddr must name an address in the corpus, of the same kind', () => {
  const invented = `https://brainstorm.world/e/${toNaddr({ kind: 30311, pubkey: 'd'.repeat(64), identifier: 'fake-stream' })}`
  assert.deepEqual(kinds(`<a href="${invented}">listen</a>`), ['LINK'])
  assert.deepEqual(resolved(invented).changes.map((c) => c.kind), ['unwrapped'])
  // 31922 and 31923 cannot answer for each other, though pubkey and d match.
  const allDay = `https://brainstorm.world/e/${toNaddr({ kind: 31922, pubkey: CALENDAR_PK, identifier: CALENDAR_D })}`
  assert.equal(eventLinkTarget(allDay, corpus), null)
  // Nor can a regular kind pose as an address.
  const regular = `https://brainstorm.world/e/${toNaddr({ kind: 1, pubkey: AUTHOR, identifier: '' })}`
  assert.equal(eventLinkTarget(regular, corpus), null)
})

test('an addressable event with no d tag has no address, so it is not linked', () => {
  const { html, changes } = resolved(`https://brainstorm.world/e/${UNADDRESSED_ID}`, 'Lost')
  assert.equal(html, 'Lost')
  assert.deepEqual(changes.map((c) => c.kind), ['unwrapped'])
  assert.deepEqual(kinds(`<a href="https://brainstorm.world/e/${toNevent(UNADDRESSED_ID)}">Lost</a>`), ['LINK'])
})

test('a byline becomes the author\'s Brainstorm profile, /p/npub', () => {
  const writer = `https://brainstorm.world/p/${EVENT_ID}`
  const { html, changes } = resolved(writer, 'Alice')
  assert.equal(hrefOf(html), `https://brainstorm.world/p/${toNpub(AUTHOR)}`)
  assert.match(html, /target="_blank"/)
  assert.deepEqual(changes.map((c) => c.kind), ['person'])
  assert.deepEqual(check(html, corpus).violations, [])
  assert.equal(personLinkTarget(hrefOf(html), corpus), AUTHOR)
  // Any post of theirs works, addressable or not.
  assert.equal(hrefOf(resolved(`https://brainstorm.world/p/${RECIPE_ID}`).html), toPersonLink(WRITER_PK))
  assert.deepEqual(kinds(`<a href="${writer}">Alice</a>`), ['LINK'], 'writer form must be encoded before validate')
})

test('a byline names only someone who wrote something in the corpus', () => {
  // The hex is an EVENT id. A pubkey in its place is not read as one.
  assert.deepEqual(resolved(`https://brainstorm.world/p/${AUTHOR}`).changes.map((c) => c.kind), ['unwrapped'])
  // A post the paper never read.
  assert.deepEqual(resolved(`https://brainstorm.world/p/${'f'.repeat(64)}`).changes.map((c) => c.kind), ['unwrapped'])
  // An npub of somebody with nothing on a desk; the control run does not count.
  const stranger = `https://brainstorm.world/p/${toNpub('d'.repeat(64))}`
  assert.deepEqual(kinds(`<a href="${stranger}">x</a>`), ['LINK'])
  assert.deepEqual(resolved(stranger).changes.map((c) => c.kind), ['unwrapped'])
  assert.deepEqual(kinds(`<a href="https://brainstorm.world/p/${toNpub('cc'.repeat(32))}">x</a>`), ['LINK'])
})

test('the old jumble.social citations are rewritten to Brainstorm', () => {
  for (const form of [EVENT_ID, toNevent(EVENT_ID)]) {
    const { html, changes } = resolved(`https://jumble.social/notes/${form}`)
    assert.equal(hrefOf(html), toPermalink(EVENT_ID))
    assert.match(html, /target="_blank"/)
    assert.deepEqual(changes.map((c) => [c.kind, c.legacy]), [['permalink', true]])
    assert.deepEqual(check(html, corpus).violations, [])
  }
  // A jumble citation of a calendar listing or a recipe becomes its naddr.
  for (const id of [CALENDAR_ID, RECIPE_ID]) {
    const { html, changes } = resolved(`https://jumble.social/notes/${id}`)
    assert.equal(hrefOf(html), `https://brainstorm.world/e/${naddrOf(id)}`)
    assert.deepEqual(changes.map((c) => [c.kind, c.legacy]), [['address', true]])
  }
  // A jumble citation of an event not in the corpus is unwrapped, as before.
  assert.deepEqual(resolved(`https://jumble.social/notes/${'f'.repeat(64)}`).changes.map((c) => c.kind), ['unwrapped'])
  // The validator never accepts jumble itself, in either form.
  assert.deepEqual(kinds(`<a href="https://jumble.social/notes/${toNevent(EVENT_ID)}">s</a>`), ['LINK'])
  assert.deepEqual(kinds(`<a href="https://jumble.social/notes/${EVENT_ID}">s</a>`), ['LINK'])
})

test('anything else is refused: the old desk hosts, and every other shape of Brainstorm link', () => {
  const nevent = toPermalink(EVENT_ID)
  const naddr = `https://brainstorm.world/e/${naddrOf(STREAM_ID)}`
  const oldHosts = [
    // The per-desk hosts the paper used to link to, in writer and naddr form.
    `https://zap.stream/stream/${STREAM_ID}`, `https://zap.stream/${naddrOf(STREAM_ID)}`,
    `https://shopstr.store/listing/${LISTING_ID}`, `https://shopstr.store/listing/${naddrOf(LISTING_ID)}`,
    `https://njump.me/${CALENDAR_ID}`, `https://njump.me/${naddrOf(CALENDAR_ID)}`, `https://njump.me/${EVENT_ID}`,
    `https://jumble.social/users/${toNpub(AUTHOR)}`,
  ]
  const offShape = [
    // Brainstorm, but not the canonical form.
    `${nevent}/extra`, `${nevent}?ref=x`, `${naddr}#top`,
    `http://brainstorm.world/e/${toNevent(EVENT_ID)}`,
    `https://www.brainstorm.world/e/${toNevent(EVENT_ID)}`,
    `https://brainstorm.world/a/${naddrOf(STREAM_ID)}`,
    `https://brainstorm.world/e/${toNpub(AUTHOR)}`,
    `https://brainstorm.world/p/${toNevent(EVENT_ID)}`,
    `https://brainstorm.world/p/${AUTHOR}`,
    `https://brainstorm.world/p/${toNpub(AUTHOR)}/hops`,
  ]
  for (const href of [...oldHosts, ...offShape]) {
    assert.deepEqual(kinds(`<a href="${href}">x</a>`), ['LINK'], `not refused: ${href}`)
  }
  // resolve drops the old desk hosts outright: no zap.stream, Shopstr or njump form survives.
  for (const href of oldHosts) {
    assert.deepEqual(resolved(href).changes.map((c) => c.kind), ['unwrapped'], `kept: ${href}`)
  }
})

test('an nevent carrying relay hints is refused, and resolve re-encodes it to the bare id', () => {
  const hinted = `https://brainstorm.world/e/${HINTED}`
  const withId = { desks: { notes: [{ id: HINTED_ID, kind: 1, pubkey: AUTHOR, content: '' }] }, art: [] }
  assert.equal(eventLinkTarget(hinted, withId), null)
  const { html } = resolve(`<a href="${hinted}">s</a>`, withId)
  assert.equal(hrefOf(html), toPermalink(HINTED_ID))
  assert.deepEqual(check(html, withId).violations, [])
})

test('resolving twice changes nothing the second time', () => {
  const page = [EVENT_ID, CALENDAR_ID, RECIPE_ID].map((id) => `<a href="https://brainstorm.world/e/${id}">e</a>`).join('')
    + `<a href="https://brainstorm.world/p/${STREAM_ID}">who</a>`
  const once = resolve(page, corpus)
  assert.deepEqual(once.changes.map((c) => c.kind), ['permalink', 'address', 'address', 'person'])
  const twice = resolve(once.html, corpus)
  assert.equal(twice.html, once.html)
  assert.deepEqual(twice.changes, [])
  assert.deepEqual(check(twice.html, corpus).violations, [])
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

// --- Audit, 2026-09-21 ------------------------------------------------------

test('a link helper does not read the corpus to answer about a url it cannot parse', () => {
  // The scan used to run BEFORE the regex, so every anchor on the page cost
  // six passes over every desk. Measured at 4,800 events and 528 anchors that
  // was 585 ms in resolve, ~440 ms of it for urls that never matched.
  let reads = 0
  const counted = { get desks () { reads++; return corpus.desks }, art: corpus.art }
  for (const href of ['https://example.test/x', 'mailto:a@b.c', 'https://brainstorm.world/e/not-an-naddr', `https://brainstorm.world/e/${EVENT_ID}`]) {
    eventLinkTarget(href, counted)
    personLinkTarget(href, counted)
  }
  assert.equal(reads, 0)
})

test('the corpus is indexed once, however many links ask about it', () => {
  let reads = 0
  const counted = { get desks () { reads++; return corpus.desks }, art: corpus.art }
  const stream = toEventLink(event(STREAM_ID))
  const person = toPersonLink(AUTHOR)
  for (let i = 0; i < 50; i++) {
    assert.ok(eventLinkTarget(stream, counted))
    assert.ok(personLinkTarget(person, counted))
  }
  assert.equal(reads, 1)
})


test('unwrapping an open-web link does not depend on the case of its closing tag', () => {
  // The old search lowercased the WHOLE document once per anchor — 54 MB of
  // copies on a 102 KB page with 528 of them. The sticky search that replaced
  // it also accepts `</a >`, which the literal missed.
  for (const close of ['</a>', '</A>', '</a >']) {
    const page = `<p>before <a href="https://evil.example/x">text${close} after</p>`
    const { html, changes } = resolve(page, corpus)
    assert.deepEqual(changes.map((c) => c.kind), ['unwrapped'])
    assert.match(html, /before text after/)
    assert.doesNotMatch(html, /<a\b/i)
  }
})
