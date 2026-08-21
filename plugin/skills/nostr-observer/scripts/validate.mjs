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

function arg (name, fallback = null) {
  const at = process.argv.indexOf(name)
  return at > -1 ? process.argv[at + 1] : fallback
}

// --- the smallest HTML reader that can answer these three questions --------

const ENTITIES = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: ' ', hellip: '…', mdash: '—', ndash: '–', rsquo: '’', lsquo: '‘', ldquo: '“', rdquo: '”' }

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
  const found = []
  const open = /<(q|blockquote)(\s[^>]*)?>/gi
  let match
  while ((match = open.exec(html)) !== null) {
    const tag = match[1].toLowerCase()
    let depth = 1
    let at = open.lastIndex
    const scan = new RegExp(`<(/?)${tag}(\\s[^>]*)?>`, 'gi')
    scan.lastIndex = at
    let close = -1
    let inner
    while ((inner = scan.exec(html)) !== null) {
      depth += inner[1] === '/' ? -1 : 1
      if (depth === 0) { close = inner.index; break }
    }
    if (close === -1) continue
    const raw = html.slice(at, close)
    const text = decodeEntities(raw.replace(/<[^>]*>/g, ' ')).replace(/\s+/g, ' ').trim()
    if (text) found.push(text)
  }
  return found
}

/** Every value of one attribute on one tag. */
export function attributes (html, tag, attr) {
  const out = []
  const re = new RegExp(`<${tag}\\b[^>]*?\\b${attr}\\s*=\\s*("([^"]*)"|'([^']*)'|([^\\s>]+))`, 'gi')
  let match
  while ((match = re.exec(html)) !== null) out.push(decodeEntities(match[2] ?? match[3] ?? match[4] ?? ''))
  return out
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
 * The one external shape a link may take: a permalink to an event we read.
 *
 * BARE HEX ONLY, which is stricter than the Kotlin (it also decodes nevent1
 * and note1). Narrower on purpose: the Kotlin's regex once allowed nevent1 in
 * a branch that captured nothing, so every such link compared against the
 * empty string, and an edition citing its sources the normal way failed its
 * own check and was never offered for publication. Two halves of one rule
 * disagreeing. Here the editorial brief says hex and this accepts hex, so they
 * cannot drift apart.
 */
export const PERMALINK = /^https:\/\/njump\.me\/([0-9a-f]{64})(?:[/?#].*)?$/i

// Things there is no sanitizer to strip, so they are refused instead.
export const FORBIDDEN = [
  [/<script\b/i, 'a <script> tag'],
  [/<iframe\b/i, 'an <iframe>'],
  [/<object\b|<embed\b|<applet\b/i, 'an embedded object'],
  [/<form\b|<input\b|<button\b/i, 'a form control — the paper collects nothing'],
  [/\son[a-z]+\s*=/i, 'an inline event handler (on…=)'],
  [/javascript\s*:/i, 'a javascript: URL'],
  [/\sdata\s*:\s*text\/html/i, 'a data:text/html URL'],
]

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

  for (const [pattern, what] of FORBIDDEN) {
    const hit = html.match(pattern)
    if (hit) flag('MARKUP', `the page contains ${what}`, hit[0].slice(0, 80))
  }

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
    const id = PERMALINK.exec(href)?.[1]?.toLowerCase()
    if (!id || !eventIds.has(id)) {
      // Presence in the corpus is evidence of NOTHING. An earlier version of
      // this rule allowlisted every URL that appeared in the corpus, on the
      // theory that a link nobody posted must have been invented. The corpus
      // is written by the attacker too: posting "click https://evil.example/x"
      // put that URL on the allowlist, and an injected instruction to link
      // every story to it then passed cleanly — a phishing link under the
      // reader's masthead. So the paper does not link to the open web at all.
      flag('LINK', 'only permalinks back to a source event may be links', href.slice(0, 120))
    }
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
