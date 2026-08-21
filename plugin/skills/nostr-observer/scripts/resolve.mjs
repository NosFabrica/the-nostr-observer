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
import { PERMALINK } from './validate.mjs'

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
  // every offset after it. The page is tens of kilobytes; correctness first.
  for (let guard = 0; guard < 500; guard++) {
    const img = /<img\b[^>]*?\bsrc\s*=\s*"(art-\d+)"[^>]*>/i.exec(out)
    if (!img) break
    const art = byId.get(img[1])
    if (art) {
      out = out.slice(0, img.index)
        + img[0].replace(/\bsrc\s*=\s*"art-\d+"/i, `src="${art.url}"`)
        + out.slice(img.index + img[0].length)
      changes.push({ kind: 'resolved', detail: `${img[1]} -> ${art.url}` })
    } else {
      out = dropFigure(out, img.index, img.index + img[0].length)
      changes.push({ kind: 'dropped', detail: `${img[1]} is not on the shortlist; its figure was removed` })
    }
  }

  // --- links to the open web ----------------------------------------------
  // The paper prints addresses; it does not make them clickable. A permalink
  // back to an event we actually read survives; everything else is unwrapped
  // to its own text, which is what a printed newspaper does with a URL.
  out = out.replace(/<a\b([^>]*)>([\s\S]*?)<\/a>/gi, (whole, attrs, inner) => {
    const href = /\bhref\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+))/i.exec(attrs)
    const url = href ? (href[2] ?? href[3] ?? href[4] ?? '') : ''
    if (!/^https?:/i.test(url)) return whole
    const id = PERMALINK.exec(url)?.[1]?.toLowerCase()
    if (id && eventIds.has(id)) return whole
    changes.push({ kind: 'unwrapped', detail: url.slice(0, 120) })
    return inner
  })

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
  if (counts.dropped || counts.unwrapped) {
    console.log('')
    console.log('  CHANGES WORTH READING - each of these is the page trying to do something')
    console.log('  the paper does not do. Look at them before you ship:')
    console.log('')
    for (const change of changes.filter((c) => c.kind !== 'resolved')) {
      console.log(`  ${change.kind.toUpperCase()}: ${change.detail}`)
    }
  }
  console.log('')
}

// Importable by the tests; runs only when it is the thing that was invoked.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) main()
