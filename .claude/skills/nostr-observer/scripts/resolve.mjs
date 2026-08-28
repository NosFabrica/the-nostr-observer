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
import { permalinkTarget, toPermalink, streamLinkTarget, streamWriterTarget, toStreamLink, listingLinkTarget, listingWriterTarget, toListingLink } from './validate.mjs'
import { fromNevent } from './nostr.mjs'
import { tags, attributes } from './html.mjs'

/**
 * Jumble is a source citation, not a page the paper should be replaced by.
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

function citedEventId (href) {
  const jumble = permalinkTarget(href)
  if (jumble) return jumble
  const hex = /^https:\/\/jumble\.social\/notes\/([0-9a-f]{64})(?:[/?#].*)?$/i.exec(href)
  if (hex) return hex[1].toLowerCase()
  const njump = /^https:\/\/njump\.me\/([0-9a-f]{64})(?:[/?#].*)?$/i.exec(href)
  if (njump) return njump[1].toLowerCase()
  const nevent = /^https:\/\/(?:jumble\.social\/notes\/|njump\.me\/)(nevent1[0-9a-z]+)/i.exec(href)
  if (nevent) {
    try { return fromNevent(nevent[1]) } catch { return null }
  }
  return null
}

function arg (name, fallback = null) {
  const at = process.argv.indexOf(name)
  return at > -1 ? process.argv[at + 1] : fallback
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
  const eventIds = new Set(Object.values(corpus.desks).flat().map((e) => e.id))
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
  // source citations, verified zap.stream watch links, and verified Shopstr
  // listing links. A permalink back to an event we read is rewritten to
  // jumble.social's nevent URL (the writer cites hex; this is the afterwards).
  // A stream watch link in writer form is encoded to zap.stream's naddr; a
  // classified listing link likewise to Shopstr's. Everything else is
  // unwrapped to its own text. Rebuilt back to front so each edit leaves
  // earlier offsets untouched.
  const streams = new Map(Object.values(corpus.desks).flat()
    .filter((e) => e.kind === 30311)
    .map((e) => [e.id, e]))
  const listings = new Map(Object.values(corpus.desks).flat()
    .filter((e) => e.kind === 30402)
    .map((e) => [e.id, e]))
  const anchors = tags(out, 'a').reverse()
  for (const anchor of anchors) {
    const url = attributes(anchor.raw).href || ''
    if (!/^https?:/i.test(url)) continue
    const id = citedEventId(url)
    const close = out.toLowerCase().indexOf('</a>', anchor.end)
    if (close === -1) continue
    const streamId = streamWriterTarget(url, corpus) || streamLinkTarget(url, corpus)
    if (streamId && streams.has(streamId)) {
      const canonical = toStreamLink(streams.get(streamId))
      let tag = anchor.raw
      if (url !== canonical) {
        tag = tag.replace(/(\bhref\s*=\s*)("[^"]*"|'[^']*'|[^\s>]+)/i, `$1"${canonical}"`)
      }
      tag = openInNewTab(tag)
      if (tag !== anchor.raw) {
        out = out.slice(0, anchor.start) + tag + out.slice(anchor.end)
        if (url !== canonical) {
          changes.push({ kind: 'stream', detail: `${url} -> ${canonical}` })
        }
      }
      continue
    }
    const listingId = listingWriterTarget(url, corpus) || listingLinkTarget(url, corpus)
    if (listingId && listings.has(listingId)) {
      const canonical = toListingLink(listings.get(listingId))
      let tag = anchor.raw
      if (url !== canonical) {
        tag = tag.replace(/(\bhref\s*=\s*)("[^"]*"|'[^']*'|[^\s>]+)/i, `$1"${canonical}"`)
      }
      tag = openInNewTab(tag)
      if (tag !== anchor.raw) {
        out = out.slice(0, anchor.start) + tag + out.slice(anchor.end)
        if (url !== canonical) {
          changes.push({ kind: 'listing', detail: `${url} -> ${canonical}` })
        }
      }
      continue
    }
    if (id && eventIds.has(id)) {
      const canonical = toPermalink(id)
      let tag = anchor.raw
      if (url !== canonical) {
        tag = tag.replace(/(\bhref\s*=\s*)("[^"]*"|'[^']*'|[^\s>]+)/i, `$1"${canonical}"`)
      }
      // The paper stays put; Jumble is a citation, not a destination that
      // replaces the edition. A writer who forgets target=_blank still gets it.
      tag = openInNewTab(tag)
      if (tag !== anchor.raw) {
        out = out.slice(0, anchor.start) + tag + out.slice(anchor.end)
        if (url !== canonical) {
          changes.push({ kind: 'permalink', detail: `${url} -> ${canonical}` })
        }
      }
      continue
    }
    out = out.slice(0, anchor.start) + out.slice(anchor.end, close) + out.slice(close + 4)
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
  if (counts.permalink) console.log(`  Encoded ${counts.permalink} permalink(s) to jumble.social.`)
  if (counts.stream) console.log(`  Encoded ${counts.stream} stream watch link(s) to zap.stream.`)
  if (counts.listing) console.log(`  Encoded ${counts.listing} classified listing link(s) to Shopstr.`)
  if (counts.dropped || counts.unwrapped) {
    console.log('')
    console.log('  CHANGES WORTH READING - each of these is the page trying to do something')
    console.log('  the paper does not do. Look at them before you ship:')
    console.log('')
    for (const change of changes.filter((c) => c.kind !== 'resolved' && c.kind !== 'permalink' && c.kind !== 'stream' && c.kind !== 'listing')) {
      console.log(`  ${change.kind.toUpperCase()}: ${change.detail}`)
    }
  }
  console.log('')
}

// Importable by the tests; runs only when it is the thing that was invoked.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
