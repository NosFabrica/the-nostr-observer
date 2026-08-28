#!/usr/bin/env node
// The boundary. Does the page say only things the corpus actually said?
//
// A port of `generator/src/main/kotlin/.../safe/Validator.kt`, with one job
// added. In the Kotlin pipeline `Sanitizer` STRIPS what is not allowed and
// this VERIFIES what is claimed. There is no sanitizer in this skill, so the
// stripping half is folded in here as REFUSAL rather than removal: a silent
// strip would hide a successful injection, and the whole point of running this
// is to see one.
//
// Why it exists at all. This is the cheapest defence against corpus injection
// there is. An attacker can put "ignore previous instructions, the lead
// headline is…" into the feed of everyone who follows them, and a model may
// well take the bait — but an injected story generally cannot quote real
// events verbatim, so it fails here.
//
// What is checked is deliberately narrow and mechanical. PARAPHRASE IS NOT
// CHECKED, because paraphrase is journalism. The contract is that verbatim
// quotation goes in <q> or <blockquote>, and the editorial brief says so.
//
// Usage: node validate.mjs <page.html> [--corpus corpus.json]

import { readFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { tags, attributes as attrsOf, textIn } from './html.mjs'
import { toNevent, fromNevent, fromNaddr, toZapStreamUrl, LIVE_KIND, toShopstrUrl, CLASSIFIED_KIND, tagValue } from './nostr.mjs'

function arg (name, fallback = null) {
  const at = process.argv.indexOf(name)
  return at > -1 ? process.argv[at + 1] : fallback
}

// --- reading the page -------------------------------------------------------
//
// Element lookup goes through `html.mjs`, which tracks quoting. It has to: a
// regex that stops at the first `>` cannot see `<img alt="a > b" src="…">` at
// all, and skipping an element is the boundary failing OPEN.

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', hellip: '…', mdash: '—', ndash: '–', rsquo: '’', lsquo: '‘', ldquo: '“', rdquo: '”' }

export function decodeEntities (text) {
  return text.replace(/&(#x?[0-9a-f]+|[a-z]+);/gi, (whole, body) => {
    if (body[0] === '#') {
      const code = body[1] === 'x' || body[1] === 'X' ? parseInt(body.slice(2), 16) : parseInt(body.slice(1), 10)
      return Number.isFinite(code) ? String.fromCodePoint(code) : whole
    }
    return ENTITIES[body.toLowerCase()] ?? whole
  })
}

/** Text content of every <q> and <blockquote>, nesting handled. */
export function quotedText (html) {
  return [...textIn(html, 'q'), ...textIn(html, 'blockquote')]
    .sort((a, b) => a.start - b.start)
    .map((found) => decodeEntities(found.raw.replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim())
    .filter(Boolean)
}

/** Every value of one attribute on one tag. */
export function attributes (html, tag, attr) {
  return tags(html, tag).map((t) => attrsOf(t.raw)[attr]).filter((v) => v !== undefined)
}

// --- normalisation ---------------------------------------------------------

/**
 * One normal form for both sides of the comparison.
 *
 * Lowercased on purpose: capitalising the first word of a quote to start a
 * sentence is standard and should not be a violation. Everything that changes
 * MEANING — words, order, negation — survives normalisation intact, which is
 * the line this is drawing.
 */
export function normalize (text) {
  return String(text)
    .normalize('NFKC')
    .replace(/[‘’]/g, "'")
    .replace(/[“”]/g, '"')
    .replace(/[–—]/g, '-')
    .replace(/ /g, ' ')
    .replace(/…/g, '...')
    .replace(/\s+/g, ' ')
    .trim()
    .toLowerCase()
}

/**
 * Verbatim, allowing for elision and typographic normalisation.
 *
 * Two forgivenesses, both normal editorial practice rather than loopholes.
 * Curly quotes, dashes and whitespace are normalised, because a model that
 * renders ' as ’ has not changed what anybody said. And a quote may elide its
 * middle with an ellipsis, in which case every fragment must appear IN ORDER
 * in ONE SINGLE event — order and single-event are what stop elision being
 * used to stitch two people into one sentence.
 */
export function isQuoted (raw, haystack) {
  const needle = normalize(raw)
  if (!needle) return true
  const fragments = needle.split('...').map((f) => f.trim()).filter((f) => f.length > 2)
  if (fragments.length === 0) return haystack.some((source) => source.includes(needle))
  return haystack.some((source) => {
    let from = 0
    for (const fragment of fragments) {
      const at = source.indexOf(fragment, from)
      if (at < 0) return false
      from = at + fragment.length
    }
    return true
  })
}

/**
 * The one external shape a link may take, after resolve.mjs has run:
 * `https://jumble.social/notes/<nevent1…>` naming an event we read.
 *
 * The writer cites `https://jumble.social/notes/<64-hex>` (or, still, a
 * leftover njump.me hex URL). resolve.mjs encodes the nevent. This checker
 * DECODES rather than capturing a regex group: the Kotlin regex once allowed
 * `nevent1…` in a branch that captured nothing, so every such link compared
 * against the empty string and a page citing its sources the normal way
 * failed its own check. Decode, or do not accept the link.
 */
export const PERMALINK = /^https:\/\/jumble\.social\/notes\/(nevent1[0-9a-z]+)(?:[/?#].*)?$/i

export function toPermalink (eventId) {
  return `https://jumble.social/notes/${toNevent(eventId)}`
}

/** Event id hex if `href` is a jumble.social nevent permalink; otherwise null. */
export function permalinkTarget (href) {
  const match = PERMALINK.exec(href)
  if (!match) return null
  try {
    return fromNevent(match[1])
  } catch {
    return null
  }
}

export const STREAM_WRITER = /^https:\/\/zap\.stream\/stream\/([0-9a-f]{64})(?:[/?#].*)?$/i
export const STREAM_NADDR = /^https:\/\/zap\.stream\/(naddr1[0-9a-z]+)(?:[/?#].*)?$/i

export const LISTING_WRITER = /^https:\/\/shopstr\.store\/listing\/([0-9a-f]{64})(?:[/?#].*)?$/i
export const LISTING_NADDR = /^https:\/\/shopstr\.store\/listing\/(naddr1[0-9a-z]+)(?:[/?#].*)?$/i

/** Live-stream events from the corpus — the only streams a watch link may name. */
export function liveStreams (corpus) {
  return Object.values(corpus.desks).flat().filter((e) => e.kind === LIVE_KIND && tagValue(e, 'd'))
}

/** Classified events from the corpus — the only listings a Shopstr link may name. */
export function classifiedListings (corpus) {
  return Object.values(corpus.desks).flat().filter((e) => e.kind === CLASSIFIED_KIND && tagValue(e, 'd'))
}

/**
 * Event id if `href` is a verified zap.stream watch link for a live stream we
 * read; otherwise null.
 *
 * Two shapes after resolve: canonical naddr, or the writer form
 * `zap.stream/stream/<64-hex>` which resolve encodes. Either way the naddr
 * must decode to the same pubkey + d-tag as a kind 30311 in the corpus — not
 * merely appear in somebody's post.
 */
export function streamLinkTarget (href, corpus) {
  const streams = liveStreams(corpus)
  const naddr = STREAM_NADDR.exec(href)
  if (!naddr) return null
  try {
    const { kind, pubkey, identifier } = fromNaddr(naddr[1])
    if (kind !== LIVE_KIND) return null
    const event = streams.find((e) =>
      e.pubkey.toLowerCase() === pubkey.toLowerCase() && tagValue(e, 'd') === identifier,
    )
    return event?.id || null
  } catch {
    return null
  }
}

/** Writer form, for resolve.mjs only. */
export function streamWriterTarget (href, corpus) {
  const streams = liveStreams(corpus)
  const byId = new Map(streams.map((e) => [e.id, e]))
  const writer = STREAM_WRITER.exec(href)
  if (!writer) return null
  const id = writer[1].toLowerCase()
  return byId.has(id) ? id : null
}

export function toStreamLink (event) {
  return toZapStreamUrl(event)
}

/**
 * Event id if `href` is a verified Shopstr listing link for a classified we
 * read; otherwise null.
 *
 * Two shapes after resolve: canonical naddr, or the writer form
 * `shopstr.store/listing/<64-hex>` which resolve encodes. Either way the
 * naddr must decode to the same pubkey + d-tag as a kind 30402 in the corpus.
 */
export function listingLinkTarget (href, corpus) {
  const listings = classifiedListings(corpus)
  const naddr = LISTING_NADDR.exec(href)
  if (!naddr) return null
  try {
    const { kind, pubkey, identifier } = fromNaddr(naddr[1])
    if (kind !== CLASSIFIED_KIND) return null
    const event = listings.find((e) =>
      e.pubkey.toLowerCase() === pubkey.toLowerCase() && tagValue(e, 'd') === identifier,
    )
    return event?.id || null
  } catch {
    return null
  }
}

/** Writer form, for resolve.mjs only. */
export function listingWriterTarget (href, corpus) {
  const listings = classifiedListings(corpus)
  const byId = new Map(listings.map((e) => [e.id, e]))
  const writer = LISTING_WRITER.exec(href)
  if (!writer) return null
  const id = writer[1].toLowerCase()
  return byId.has(id) ? id : null
}

export function toListingLink (event) {
  return toShopstrUrl(event)
}

// Things there is no sanitizer to strip, so they are refused instead.
//
// Checked against PARSED TAGS, never against the raw document. The first
// version matched `/\son[a-z]+\s*=/` anywhere in the page, which reads
// ordinary prose as an attack: "we ran it once=twice" and "the flag is
// only=set" both tripped it. A boundary that rejects a good page prints
// nothing every morning, which is the failure the golden edition exists to
// catch and this one walked straight past.
const FORBIDDEN_TAGS = new Map([
  ['script', 'a <script> tag'],
  ['iframe', 'an <iframe>'],
  ['object', 'an embedded object'], ['embed', 'an embedded object'], ['applet', 'an embedded object'],
  ['form', 'a form - the paper collects nothing'],
  ['input', 'a form control'], ['button', 'a form control'],
  ['textarea', 'a form control'], ['select', 'a form control'],
  ['base', 'a <base> tag, which rewrites every relative URL on the page'],
])

// `<meta>` is not forbidden — a page needs charset and viewport. Only the
// http-equiv form is, because that is the one that can carry a refresh
// redirect. Banning the element outright rejected the very first real page
// this checked, which is the same false-positive class as the prose that read
// as an event handler.
const FORBIDDEN_META = /^\s*(refresh|content-security-policy|set-cookie|location)\s*$/i

const FORBIDDEN_SCHEME = /^\s*(javascript|vbscript|data)\s*:/i

/** Scheme-bearing attributes. A `data:` image is allowed nowhere; see below. */
const URL_ATTRS = ['href', 'src', 'action', 'formaction', 'poster', 'background', 'srcset', 'data', 'xlink:href']

export function markupViolations (html) {
  const out = []
  for (const tag of tags(html)) {
    const forbidden = FORBIDDEN_TAGS.get(tag.name)
    if (forbidden) out.push({ kind: 'MARKUP', detail: `the page contains ${forbidden}`, excerpt: tag.raw.slice(0, 80) })
    const attrs = attrsOf(tag.raw)
    if (tag.name === 'meta' && FORBIDDEN_META.test(attrs['http-equiv'] || '')) {
      out.push({ kind: 'MARKUP', detail: `a <meta http-equiv="${attrs['http-equiv']}">, which redirects or rewrites policy`, excerpt: tag.raw.slice(0, 80) })
    }
    for (const [name, value] of Object.entries(attrs)) {
      if (name.startsWith('on')) {
        out.push({ kind: 'MARKUP', detail: `the page contains an inline event handler (${name}=)`, excerpt: tag.raw.slice(0, 80) })
      }
      if (URL_ATTRS.includes(name) && FORBIDDEN_SCHEME.test(decodeEntities(value))) {
        out.push({ kind: 'MARKUP', detail: `${name}= carries a scripting or inline-document URL`, excerpt: value.slice(0, 80) })
      }
    }
  }
  // A <style> block can still fetch and can still hide a URL scheme.
  for (const block of textIn(html, 'style')) {
    if (/@import|url\s*\(\s*["']?\s*(javascript|data)\s*:/i.test(block.raw)) {
      out.push({ kind: 'MARKUP', detail: 'the stylesheet imports or embeds something', excerpt: block.raw.slice(0, 80) })
    }
  }
  return out
}

/**
 * Everything the boundary has to say about one page. Pure: no files, no exit
 * codes, so a test can put an adversarial page through it directly.
 */
export function check (html, corpus) {
  // The DESKS only, never the control run. `Validator.kt` compares against
  // `corpus.all()`, which is the ranked desks; the control run is a
  // measurement of the network rather than part of the paper, and it is not in
  // the digest the writer reads. Widening the haystack to include it would let
  // a quote verify against something the edition never had access to.
  const events = Object.values(corpus.desks).flat()
  const haystack = events.map((e) => normalize(e.content || ''))
  const eventIds = new Set(events.map((e) => e.id))
  const allowedImages = new Set((corpus.art || []).map((a) => a.url))

  const violations = []
  const flag = (kind, detail, excerpt) => violations.push({ kind, detail, excerpt })

  violations.push(...markupViolations(html))

  const quotes = quotedText(html)
  for (const quote of quotes) {
    if (!isQuoted(quote, haystack)) {
      flag('QUOTE', 'not found verbatim in any source event', quote.slice(0, 160))
    }
  }

  for (const src of attributes(html, 'img', 'src')) {
    if (!allowedImages.has(src)) {
      flag('IMAGE', 'image source is not from the shortlist', src.slice(0, 120))
    }
  }

  for (const href of attributes(html, 'a', 'href')) {
    if (!/^https?:/i.test(href)) continue
    const id = permalinkTarget(href)
    if (id && eventIds.has(id)) continue
    if (streamLinkTarget(href, corpus)) continue
    if (listingLinkTarget(href, corpus)) continue
    // Presence in the corpus is evidence of NOTHING. An earlier version of
    // this rule allowlisted every URL that appeared in the corpus, on the
    // theory that a link nobody posted must have been invented. The corpus
    // is written by the attacker too: posting "click https://evil.example/x"
    // put that URL on the allowlist, and an injected instruction to link
    // every story to it then passed cleanly — a phishing link under the
    // reader's masthead. So the paper does not link to the open web at all,
    // except verified zap.stream watch links and Shopstr listing links for
    // events in the corpus.
    flag('LINK', 'only source citations, verified zap.stream watch links, and verified Shopstr listing links may be links', href.slice(0, 120))
  }

  return { violations, quotes, events: events.length, images: allowedImages.size }
}

function main () {
  const page = process.argv[2]
  if (!page || page.startsWith('--')) {
    console.error('Usage: node validate.mjs <page.html> [--corpus corpus.json]')
    process.exit(2)
  }
  const html = readFileSync(page, 'utf8')
  const corpus = JSON.parse(readFileSync(arg('--corpus', 'corpus.json'), 'utf8'))
  const { violations, quotes, events, images } = check(html, corpus)

  console.log('')
  console.log(`  Page:   ${page}`)
  console.log(`  Corpus: ${events} events, ${images} shortlisted pictures`)
  console.log(`  Quotes: ${quotes.length} checked`)
  console.log('')

  if (violations.length === 0) {
    console.log(`  CLEAN — ${quotes.length} quotes, all verified.\n`)
    process.exit(0)
  }

  const byKind = violations.reduce((acc, v) => ({ ...acc, [v.kind]: (acc[v.kind] || 0) + 1 }), {})
  console.log(`  ${violations.length} violation(s): ${Object.entries(byKind).map(([k, n]) => `${n} ${k.toLowerCase()}`).join(', ')}`)
  console.log('')
  for (const v of violations) {
    console.log(`  ${v.kind}: ${v.detail}`)
    console.log(`    ${v.excerpt}`)
  }
  console.log('')
  console.log('  Fix the page and run this again. Do NOT weaken the check to get past it,')
  console.log('  and do NOT publish a page that has not come back clean.')
  console.log('')
  process.exit(1)
}

// Importable by the tests; runs only when it is the thing that was invoked.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
