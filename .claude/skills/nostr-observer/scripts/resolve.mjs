#!/usr/bin/env node
// The half of `Sanitizer.kt` the page actually depends on, and nothing else.
//
// The editorial brief tells the writer to use `<img src="art-3">` and says the
// id "is replaced with the real URL afterwards". Something has to be that
// afterwards, or every picture on a page written to the brief is broken. This
// is it.
//
// THE ID IS THE WHOLE POINT, and it is why this step exists rather than just
// telling the writer to paste URLs. If the writer picked art by writing URLs,
// an invented URL would be indistinguishable from a real one. Handing over ids
// and resolving them here makes a fabricated image reference structurally
// impossible instead of merely detectable.
//
// IT REPORTS EVERYTHING IT CHANGES, loudly, and that is not decoration. A
// dropped figure or an unwrapped link is the visible edge of somebody trying
// to edit a newspaper they do not work for. A sanitizer that cleans up in
// silence would hide exactly the event worth seeing. So: strip, then say so.
//
// Usage: node resolve.mjs <page.html> [--corpus corpus.json] [--out page.html]

import { readFileSync, writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { corpusIndex, eventNamed, toEventLink, toPersonLink } from './validate.mjs'
import { toHex, isAddressable } from './nostr.mjs'
import { tags, attributes } from './html.mjs'

/**
 * A Brainstorm link is a source citation, not a page the paper should be
 * replaced by.
 * `noopener` stops the opened tab from reaching `window.opener`.
 */
export function openInNewTab (raw) {
  let tag = raw
  const attrs = attributes(raw)
  if ((attrs.target || '').toLowerCase() !== '_blank') {
    tag = 'target' in attrs
      ? tag.replace(/(\btarget\s*=\s*)("[^"]*"|'[^']*'|[^\s>]+)/i, '$1"_blank"')
      : tag.replace(/^<a\b/i, '<a target="_blank"')
  }
  const rel = new Set((attrs.rel || '').split(/\s+/).filter(Boolean))
  rel.add('noopener')
  rel.add('noreferrer')
  const next = [...rel].join(' ')
  tag = 'rel' in attributes(tag)
    ? tag.replace(/(\brel\s*=\s*)("[^"]*"|'[^']*'|[^\s>]+)/i, `$1"${next}"`)
    : tag.replace(/^<a\b/i, `<a rel="${next}"`)
  return tag
}

// The forms a writer may hand in. Each is lenient about what follows the id
// (a stray path, query or fragment), because what comes out is rebuilt from
// the corpus anyway; validate.mjs is the strict half.
const BRAINSTORM_EVENT = /^https:\/\/brainstorm\.world\/e\/([0-9a-z]+)(?:[/?#].*)?$/i
const BRAINSTORM_PERSON = /^https:\/\/brainstorm\.world\/p\/([0-9a-z]+)(?:[/?#].*)?$/i
// Accepted for one release, so an older edition or an old habit still
// resolves: the jumble.social citation the paper used before Brainstorm,
// hex or nevent. Rewritten to Brainstorm, never kept.
const LEGACY_JUMBLE = /^https:\/\/jumble\.social\/notes\/([0-9a-f]{64}|nevent1[0-9a-z]+)(?:[/?#].*)?$/i

/**
 * What a link on the page points at, read through the corpus:
 * `{ event, legacy }` for a citation, `{ pubkey }` for a byline, or null for
 * anything the paper does not link to.
 *
 * A byline is `/p/<64-hex id of one of that person's posts>`, resolved to the
 * author of that post. The hex is an EVENT id, never a pubkey: the digest
 * prints event ids and never a pubkey, and reading the same 64 characters
 * as either would let one stand in for the other. `/p/<npub1…>` is kept only
 * if that npub wrote something on a desk, so resolving twice changes nothing.
 */
export function linkTarget (href, corpus) {
  const event = BRAINSTORM_EVENT.exec(href)
  if (event) {
    const found = eventNamed(event[1], corpus)
    return found ? { event: found, legacy: false } : null
  }
  const person = BRAINSTORM_PERSON.exec(href)
  if (person) {
    const ref = person[1].toLowerCase()
    if (/^[0-9a-f]{64}$/.test(ref)) {
      const post = corpusIndex(corpus).byId.get(ref)
      return post ? { pubkey: String(post.pubkey).toLowerCase() } : null
    }
    if (ref.startsWith('npub1')) {
      try {
        const pubkey = toHex(ref)
        return corpusIndex(corpus).authors.has(pubkey) ? { pubkey } : null
      } catch { return null }
    }
    return null
  }
  const jumble = LEGACY_JUMBLE.exec(href)
  if (jumble) {
    const found = eventNamed(jumble[1], corpus)
    return found ? { event: found, legacy: true } : null
  }
  return null
}

/** The canonical link for a target and the change kind it counts as, or null if it has none. */
function canonicalFor (target) {
  try {
    if (target.pubkey) return { url: toPersonLink(target.pubkey), kind: 'person' }
    return { url: toEventLink(target.event), kind: isAddressable(target.event.kind) ? 'address' : 'permalink' }
  } catch {
    // An addressable event with no d tag, or a pubkey that is not one: there
    // is no honest address to print, so the link is unwrapped below.
    return null
  }
}

function arg (name, fallback = null) {
  const at = process.argv.indexOf(name)
  return at > -1 ? process.argv[at + 1] : fallback
}

/** The first `</a>` at or after `from`, or null. */
function closeAnchor (html, from) {
  const close = /<\/a\s*>/gi
  close.lastIndex = from
  const match = close.exec(html)
  return match ? { start: match.index, end: match.index + match[0].length } : null
}

/** Drop the `<figure>` around `at`, or just the tag if there is no figure. */
function dropFigure (html, start, end) {
  const before = html.lastIndexOf('<figure', start)
  if (before !== -1) {
    const after = html.indexOf('</figure>', end)
    // Only if this figure really encloses the image — a `<figure` earlier in
    // the document that has already closed is not our parent.
    if (after !== -1 && html.slice(before, start).indexOf('</figure>') === -1) {
      return html.slice(0, before) + html.slice(after + '</figure>'.length)
    }
  }
  return html.slice(0, start) + html.slice(end)
}

/**
 * Resolve art ids, drop unknown ones, unwrap links to the open web.
 *
 * Returns the new html and every change made, so the caller can print them.
 */
export function resolve (html, corpus) {
  const byId = new Map((corpus.art || []).map((a) => [a.id, a]))
  const changes = []
  let out = html

  // --- art ids -------------------------------------------------------------
  // Rescanned from the top after each edit because dropping a figure moves
  // every offset after it. Element lookup goes through the quoting-aware
  // scanner: a caption containing `>` used to hide the whole `<img>`, so the
  // id was never resolved and `src="art-3"` shipped as a broken picture that
  // the boundary could not see either.
  for (let guard = 0; guard < 1000; guard++) {
    const img = tags(out, 'img').find((t) => /^art-\d+$/.test(attributes(t.raw).src || ''))
    if (!img) break
    const id = attributes(img.raw).src
    const art = byId.get(id)
    if (art) {
      out = out.slice(0, img.start)
        + img.raw.replace(/(\bsrc\s*=\s*)("art-\d+"|'art-\d+'|art-\d+)/i, `$1"${art.url}"`)
        + out.slice(img.end)
      changes.push({ kind: 'resolved', detail: `${id} -> ${art.url}` })
    } else {
      out = dropFigure(out, img.start, img.end)
      changes.push({ kind: 'dropped', detail: `${id} is not on the shortlist; its figure was removed` })
    }
  }

  // --- links to the open web ----------------------------------------------
  // The paper prints addresses; it does not make them clickable — except
  // Brainstorm pages for the events it read and for the people who wrote
  // them. A citation (`/e/<hex>`) is re-encoded to its nevent, or to its naddr
  // for an addressable kind: a stream, a listing, a meetup or a recipe is
  // replaceable, and an nevent would freeze one revision of it. A byline
  // (`/p/<hex event id>`) becomes the author's npub. An old jumble.social
  // citation is rewritten the same way. Everything else is unwrapped to its
  // own text. Rebuilt back to front so each edit leaves earlier offsets
  // untouched.
  const anchors = tags(out, 'a').reverse()
  for (const anchor of anchors) {
    const url = attributes(anchor.raw).href || ''
    if (!/^https?:/i.test(url)) continue
    // Not `out.toLowerCase().indexOf(…)`: that copied the whole document once
    // per anchor, which on a 102 KB page with 528 anchors is 54 MB of string
    // nobody reads twice. A sticky case-insensitive search costs nothing and
    // also accepts `</a >`, which the literal missed.
    const closing = closeAnchor(out, anchor.end)
    if (!closing) continue
    const target = linkTarget(url, corpus)
    const canonical = target && canonicalFor(target)
    if (canonical) {
      // Kept links point at their canonical address and open in a new tab:
      // the paper stays put, and a writer who forgets target=_blank still
      // gets it.
      let tag = anchor.raw
      if (url !== canonical.url) {
        tag = tag.replace(/(\bhref\s*=\s*)("[^"]*"|'[^']*'|[^\s>]+)/i, `$1"${canonical.url}"`)
      }
      tag = openInNewTab(tag)
      if (tag !== anchor.raw) {
        out = out.slice(0, anchor.start) + tag + out.slice(anchor.end)
        if (url !== canonical.url) {
          changes.push({ kind: canonical.kind, detail: `${url} -> ${canonical.url}`, ...(target.legacy ? { legacy: true } : {}) })
        }
      }
      continue
    }
    out = out.slice(0, anchor.start) + out.slice(anchor.end, closing.start) + out.slice(closing.end)
    changes.push({ kind: 'unwrapped', detail: url.slice(0, 120) })
  }
  changes.reverse()

  return { html: out, changes }
}

function main () {
  const page = process.argv[2]
  if (!page || page.startsWith('--')) {
    console.error('Usage: node resolve.mjs <page.html> [--corpus corpus.json] [--out page.html]')
    process.exit(2)
  }
  const corpus = JSON.parse(readFileSync(arg('--corpus', 'corpus.json'), 'utf8'))
  const { html, changes } = resolve(readFileSync(page, 'utf8'), corpus)
  writeFileSync(arg('--out', page), html)

  const counts = changes.reduce((acc, c) => ({ ...acc, [c.kind]: (acc[c.kind] || 0) + 1 }), {})
  console.log('')
  console.log(`  Resolved ${counts.resolved || 0} art id(s).`)
  if (counts.permalink) console.log(`  Encoded ${counts.permalink} citation(s) to Brainstorm nevent pages.`)
  if (counts.address) console.log(`  Encoded ${counts.address} citation(s) of addressable events to Brainstorm naddr pages.`)
  if (counts.person) console.log(`  Encoded ${counts.person} byline(s) to Brainstorm profiles.`)
  const legacy = changes.filter((c) => c.legacy).length
  if (legacy) console.log(`  Rewrote ${legacy} old jumble.social citation(s) to Brainstorm. Cite https://brainstorm.world/e/<id> next time.`)
  if (counts.dropped || counts.unwrapped) {
    console.log('')
    console.log('  CHANGES WORTH READING - each of these is the page trying to do something')
    console.log('  the paper does not do. Look at them before you ship:')
    console.log('')
    for (const change of changes.filter((c) => c.kind === 'dropped' || c.kind === 'unwrapped')) {
      console.log(`  ${change.kind.toUpperCase()}: ${change.detail}`)
    }
  }
  console.log('')
}

// Importable by the tests; runs only when it is the thing that was invoked.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
