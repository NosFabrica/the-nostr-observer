#!/usr/bin/env node
// The artifact copy, with the photographs actually in it.
//
// The artifact viewer runs a content policy that refuses every external host.
// That is not a bug and not negotiable from inside the page: a hotlinked
// picture NEVER loads there, however correct its URL, and the first edition
// shipped three empty boxes proving it. The local file is fine; the artifact
// is what the reader sees first. So this builds a SEPARATE copy for the
// artifact with each picture fetched once and inlined as a data: URI.
//
// TWO RULES KEEP THIS HONEST:
//
//  1. THE EDITION ITSELF STAYS HOTLINKED. "Art is hotlinked, never fetched,
//     resized, re-hosted or inlined" is a settled decision about the paper —
//     the published edition points at art where its authors put it. This
//     writes `<page>.artifact.html` and never touches the validated page, so
//     the boundary's IMAGE rule still holds on the real edition.
//
//  2. ONLY SHORTLIST URLS ARE FETCHED. The corpus is where the attacker
//     writes, and "fetch whatever URL is in the page" would hand them an
//     outbound request. Every candidate is checked against `corpus.art` —
//     the same allowlist validate.mjs enforces — and anything else is left
//     alone and reported.
//
// What comes back is checked by MAGIC BYTES, not by the server's own
// Content-Type: the host is chosen by whoever posted the picture, and a
// header is a claim. Only raster formats are embedded (jpeg/png/gif/webp/
// avif) — an SVG is a document that can carry scripts, and nothing on this
// page needs one.
//
// A picture that cannot be embedded — too big, wrong bytes, host down — is
// SKIPPED, loudly, and the artifact shows its caption and alt instead. That
// degradation is the alt rule earning its keep, not a failure to hide.
//
// Usage: node embed.mjs <page.html> [--corpus corpus.json] [--out page.artifact.html]

import { readFileSync, writeFileSync } from 'node:fs'
import { pathToFileURL } from 'node:url'
import { tags, attributes } from './html.mjs'

/**
 * Budgets, against the artifact's 16 MB page cap.
 *
 * base64 inflates bytes by 4/3, so 10 MB of raw image is ~13.4 MB of data:
 * URI, which leaves room for the page itself. Per-image cap because one
 * 40-megapixel photograph should cost itself, not the whole paper.
 */
export const TOTAL_IMAGE_BYTES = 10_000_000
export const PER_IMAGE_BYTES = 5_000_000

/** What the first bytes say this is. The header is not consulted. */
export function sniff (bytes) {
  const b = bytes
  if (b.length > 3 && b[0] === 0x89 && b[1] === 0x50 && b[2] === 0x4e && b[3] === 0x47) return 'image/png'
  if (b.length > 2 && b[0] === 0xff && b[1] === 0xd8 && b[2] === 0xff) return 'image/jpeg'
  if (b.length > 3 && b[0] === 0x47 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x38) return 'image/gif'
  if (b.length > 11 && b.toString('latin1', 0, 4) === 'RIFF' && b.toString('latin1', 8, 12) === 'WEBP') return 'image/webp'
  if (b.length > 11 && b.toString('latin1', 4, 8) === 'ftyp' && /^avi[fs]/.test(b.toString('latin1', 8, 12))) return 'image/avif'
  return null
}

/**
 * Inline every shortlist picture in `html`, returning the new page and a
 * report. `fetchImpl` is injectable so the tests can serve their own bytes —
 * the no-network rule for CI holds here like everywhere else.
 */
export async function embed (html, corpus, { fetchImpl = fetch, perImage = PER_IMAGE_BYTES, total = TOTAL_IMAGE_BYTES } = {}) {
  const shortlist = new Set((corpus.art || []).map((a) => a.url))
  const report = []
  let spent = 0
  let out = html

  // Rescanned from the top after each edit, like resolve.mjs, because an
  // inlined data: URI moves every offset after it. Each URL is fetched once
  // and reused — the same picture can sit in two figures.
  const inlined = new Map()
  for (let guard = 0; guard < 1000; guard++) {
    const img = tags(out, 'img').find((t) => {
      const src = attributes(t.raw).src || ''
      return /^https:/i.test(src) && !inlined.has(src) && shortlist.has(src)
    })
    if (!img) break
    const url = attributes(img.raw).src

    let datauri = null
    let note = null
    try {
      const answer = await fetchImpl(url)
      if (!answer.ok) {
        note = `host answered ${answer.status}`
      } else {
        const bytes = Buffer.from(await answer.arrayBuffer())
        const mime = sniff(bytes)
        if (!mime) note = 'not a raster image (checked by magic bytes, not the header)'
        else if (bytes.length > perImage) note = `${bytes.length.toLocaleString()} bytes is over the ${perImage.toLocaleString()}-byte per-image budget`
        else if (spent + bytes.length > total) note = `would take the page over the ${total.toLocaleString()}-byte image budget`
        else {
          spent += bytes.length
          datauri = `data:${mime};base64,${bytes.toString('base64')}`
        }
      }
    } catch (error) {
      note = `could not fetch: ${error.message}`
    }

    // Skipped pictures keep their URL: the tag stays valid, the artifact
    // shows alt and caption, and nothing is silently removed.
    inlined.set(url, datauri)
    if (datauri) report.push({ kind: 'embedded', url, detail: `${spent.toLocaleString()} bytes so far` })
    else report.push({ kind: 'skipped', url, detail: note })
  }

  // The replacement pass, back to front so offsets hold, quoting-aware like
  // everything else that touches a tag.
  for (const img of tags(out, 'img').reverse()) {
    const src = attributes(img.raw).src || ''
    const datauri = inlined.get(src)
    if (!datauri) continue
    out = out.slice(0, img.start)
      + img.raw.replace(src, datauri)
      + out.slice(img.end)
  }

  // Anything that is not on the shortlist was never fetched. Say so: an https
  // src the shortlist does not know is the same event resolve.mjs reports.
  for (const img of tags(out, 'img')) {
    const src = attributes(img.raw).src || ''
    if (/^https:/i.test(src) && !shortlist.has(src)) {
      report.push({ kind: 'refused', url: src.slice(0, 120), detail: 'not a shortlist URL; not fetched, not embedded' })
    }
  }

  return { html: out, report }
}

async function main () {
  const page = process.argv[2]
  if (!page || page.startsWith('--')) {
    console.error('Usage: node embed.mjs <page.html> [--corpus corpus.json] [--out page.artifact.html]')
    process.exit(2)
  }
  const arg = (name, fallback) => {
    const at = process.argv.indexOf(name)
    return at > -1 ? process.argv[at + 1] : fallback
  }
  const corpus = JSON.parse(readFileSync(arg('--corpus', 'corpus.json'), 'utf8'))
  const out = arg('--out', page.replace(/\.html$/, '') + '.artifact.html')
  if (out === page) {
    console.error('  Refusing to overwrite the edition itself: the validated page stays hotlinked. Pass a different --out.')
    process.exit(2)
  }

  const { html, report } = await embed(readFileSync(page, 'utf8'), corpus)
  writeFileSync(out, html)

  const embedded = report.filter((r) => r.kind === 'embedded')
  console.log('')
  console.log(`  Embedded ${embedded.length} picture(s) into ${out} (${html.length.toLocaleString()} characters).`)
  const rest = report.filter((r) => r.kind !== 'embedded')
  if (rest.length > 0) {
    console.log('')
    console.log('  NOT EMBEDDED - each of these shows as its caption and alt in the artifact.')
    console.log('  Tell the reader; do not let it pass silently:')
    console.log('')
    for (const r of rest) console.log(`  ${r.kind.toUpperCase()}: ${r.url}\n    ${r.detail}`)
  }
  console.log('')
}

// Importable by the tests; runs only when it is the thing that was invoked.
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch((error) => {
    console.error(`\n  Embed failed: ${error.message}\n`)
    process.exit(3)
  })
}
