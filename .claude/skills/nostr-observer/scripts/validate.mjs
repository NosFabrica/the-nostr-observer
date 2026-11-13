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
import { toHex, fromNevent, fromNaddr, tagValue, isAddressable, eventUrl, personUrl } from './nostr.mjs'

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
 * The two external shapes a link may take, after resolve.mjs has run:
 *
 *  - `https://brainstorm.world/e/<nevent1…|naddr1…>` naming an event we read:
 *    an naddr for an addressable kind, an nevent for everything else;
 *  - `https://brainstorm.world/p/<npub1…>` naming the author of an event we
 *    read.
 *
 * The writer cites `https://brainstorm.world/e/<64-hex>` and bylines
 * `https://brainstorm.world/p/<64-hex id of one of their posts>`; resolve.mjs
 * encodes both. This checker DECODES rather than capturing a regex group: the
 * Kotlin regex once allowed `nevent1…` in a branch that captured nothing, so
 * every such link compared against the empty string and a page citing its
 * sources the normal way failed its own check. Decode, or do not accept the
 * link. Then it re-encodes what it found and requires the page to carry
 * exactly that: no relay hints the corpus never spoke to, no path or query
 * after the id, and never an nevent for an addressable event.
 */
export const EVENT_LINK = /^https:\/\/brainstorm\.world\/e\/((?:nevent1|naddr1)[0-9a-z]+)$/i
export const PERSON_LINK = /^https:\/\/brainstorm\.world\/p\/(npub1[0-9a-z]+)$/i

/** The canonical Brainstorm link for an event: what resolve writes and validate accepts. */
export function toEventLink (event) {
  return eventUrl(event)
}

/** The canonical Brainstorm nevent link for a non-addressable event id. */
export function toPermalink (eventId) {
  return eventUrl({ id: eventId })
}

/** The canonical Brainstorm link for a person. */
export function toPersonLink (pubkey) {
  return personUrl(pubkey)
}

/**
 * The corpus, indexed once per run instead of once per link.
 *
 * The desk-link lists used to be rebuilt on every call — a `.flat()` over
 * every desk, then a filter over every event — and `resolve.mjs` asks about
 * every anchor on the page. Measured on a busy window (4,800 ranked events,
 * 528 anchors) that was 585 ms in resolve, of which about 440 ms was scans
 * running for URLs that did not even match the helper's own regex. Nothing
 * reads the corpus until it has been parsed from disk and nothing writes to it
 * afterwards, so it is indexed once and keyed on the object itself; a caller
 * that mutates a corpus mid-run gets a stale index, and no caller does.
 *
 * `byAddress` exists because the naddr lookup runs per matching link, and a
 * linear `find` over every desk is the same mistake one level down. The kind
 * is part of the key, so 31922 and 31923 cannot answer for each other even
 * when a pubkey reuses a d-tag across both. `authors` is everyone who wrote an
 * event on a desk: the only people a byline may link to.
 */
const INDEX = new WeakMap()

const lower = (value) => String(value || '').toLowerCase()
export const addressKey = (kind, pubkey, identifier) => `${kind}:${lower(pubkey)}:${identifier}`

function indexOf (corpus) {
  const found = INDEX.get(corpus)
  if (found) return found
  const events = Object.values(corpus.desks).flat()
  const built = {
    byId: new Map(events.map((e) => [lower(e.id), e])),
    byAddress: new Map(events
      .filter((e) => isAddressable(e.kind) && tagValue(e, 'd') !== null)
      .map((e) => [addressKey(e.kind, e.pubkey, tagValue(e, 'd')), e])),
    authors: new Set(events.map((e) => lower(e.pubkey)).filter((pk) => /^[0-9a-f]{64}$/.test(pk))),
  }
  INDEX.set(corpus, built)
  return built
}

/** The indexed corpus, for resolve.mjs — so it does not build its own copy. */
export function corpusIndex (corpus) {
  return indexOf(corpus)
}

/**
 * The corpus event that `ref` (hex id, `nevent1…` or `naddr1…`) names, or null.
 * Lenient about form, for resolve.mjs: it is what turns any of these into the
 * one canonical link. validate.mjs does not use it to accept anything.
 */
export function eventNamed (ref, corpus) {
  const value = lower(ref)
  const index = indexOf(corpus)
  if (/^[0-9a-f]{64}$/.test(value)) return index.byId.get(value) || null
  try {
    if (value.startsWith('nevent1')) return index.byId.get(fromNevent(value)) || null
    if (value.startsWith('naddr1')) {
      const { kind, pubkey, identifier } = fromNaddr(value)
      if (!isAddressable(kind)) return null
      return index.byAddress.get(addressKey(kind, pubkey, identifier)) || null
    }
  } catch { /* not bech32 that names anything */ }
  return null
}

/**
 * Event id if `href` is the canonical Brainstorm link to an event we read;
 * otherwise null.
 *
 * An naddr must decode to the same kind + pubkey + d-tag as an event in the
 * corpus — not merely appear in somebody's post. An nevent naming an
 * ADDRESSABLE event is refused even though the event is in the corpus and the
 * citation is well formed: it freezes ONE revision of something whose whole
 * nature is to be replaced, so the reader clicks through to a meetup whose
 * time has since moved. resolve.mjs writes the naddr; refusing the nevent here
 * is what makes a regression in that step fail closed. The writer form (hex)
 * is not accepted either, for the same reason.
 */
export function eventLinkTarget (href, corpus) {
  // The regex first: a URL that is not this shape is the common case on any
  // page, and it used to cost a full pass over the corpus to find out.
  const match = EVENT_LINK.exec(href)
  if (!match) return null
  const event = eventNamed(match[1], corpus)
  if (!event) return null
  try {
    return toEventLink(event) === href ? lower(event.id) : null
  } catch {
    return null
  }
}

/** Pubkey hex if `href` is the canonical Brainstorm link to the author of an event we read; otherwise null. */
export function personLinkTarget (href, corpus) {
  const match = PERSON_LINK.exec(href)
  if (!match) return null
  try {
    const pubkey = toHex(match[1])
    if (!indexOf(corpus).authors.has(pubkey)) return null
    return toPersonLink(pubkey) === href ? pubkey : null
  } catch {
    return null
  }
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
    if (eventLinkTarget(href, corpus)) continue
    if (personLinkTarget(href, corpus)) continue
    // Presence in the corpus is evidence of NOTHING. An earlier version of
    // this rule allowlisted every URL that appeared in the corpus, on the
    // theory that a link nobody posted must have been invented. The corpus
    // is written by the attacker too: posting "click https://evil.example/x"
    // put that URL on the allowlist, and an injected instruction to link
    // every story to it then passed cleanly — a phishing link under the
    // reader's masthead. So the paper does not link to the open web at all,
    // except Brainstorm pages for events in the corpus and for their authors,
    // each one derived by resolve.mjs and decoded back here.
    flag('LINK', 'only Brainstorm links to an event in the corpus (/e/<nevent1|naddr1>) or to its author (/p/<npub1>) may be links', href.slice(0, 120))
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
