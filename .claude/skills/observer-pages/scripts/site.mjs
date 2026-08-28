#!/usr/bin/env node
// The public shelf. editions/ is every paper this machine has printed.
// dist/ is the subset that is allowed on the web, plus an index of those.
//
// That split exists because a print run also writes corpus.json (megabytes of
// other people's posts) next to the HTML. Deploying "the output folder"
// would publish the corpus. dist/ is allowed to contain only edition HTML,
// index.html, vercel.json, and favicon.svg. check() is the gate in front of
// `vercel`.
//
// This script does not talk to Vercel. The skill runs `npx vercel deploy`
// after check exits 0, so a missing login is a visible stop rather than a
// swallowed spawn.

import { copyFileSync, existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { basename, dirname, join, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

export const EDITION_RE = /^observer-(\d{4}-\d{2}-\d{2})-([0-9A-Fa-f]+)\.html$/

const SITE_FILES = new Set(['index.html', 'vercel.json', 'favicon.svg'])
const FAVICON_SRC = join(dirname(fileURLToPath(import.meta.url)), '..', 'favicon.svg')
export const FAVICON_LINK = '<link rel="icon" href="/favicon.svg" type="image/svg+xml">'
export const PUBLIC_ORIGIN = 'https://thenostrobserver.vercel.app'

export function localToday (now = new Date()) {
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

export function parseName (file) {
  const name = basename(file)
  const m = EDITION_RE.exec(name)
  if (!m) return null
  return { file: name, date: m[1], code: m[2].toUpperCase() }
}

export function escapeHtml (text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;')
}

function stripTags (html) {
  return html.replace(/<[^>]+>/g, ' ')
}

function decodeEntities (text) {
  return text
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&#39;|&apos;/g, "'")
}

export function headlineOf (html) {
  for (const h of html.matchAll(/<h[12]\b[^>]*\bclass=["'][^"']*\b(lead-head|main-head)\b[^"']*["'][^>]*>([\s\S]*?)<\/h[12]>/gi)) {
    const text = decodeEntities(stripTags(h[2])).replace(/\s+/g, ' ').trim()
    if (text) return text
  }
  const h2 = html.match(/<h2\b[^>]*>([\s\S]*?)<\/h2>/i)
  if (h2) {
    const text = decodeEntities(stripTags(h2[1])).replace(/\s+/g, ' ').trim()
    if (text) return text
  }
  const title = html.match(/<title\b[^>]*>([\s\S]*?)<\/title>/i)
  if (title) {
    const text = decodeEntities(stripTags(title[1])).replace(/\s+/g, ' ').trim()
    if (text) return text
  }
  return 'Untitled edition'
}

export function dekOf (html) {
  const dek = html.match(/<(?:p|div)\b[^>]*\bclass=["'][^"']*\bdek\b[^"']*["'][^>]*>([\s\S]*?)<\/(?:p|div)>/i)
  if (dek) {
    const text = decodeEntities(stripTags(dek[1])).replace(/\s+/g, ' ').trim()
    if (text) return text
  }
  return ''
}

export function firstImageOf (html) {
  const img = html.match(/<img\b[^>]*\bsrc=["'](https:[^"']+)["']/i)
  return img ? img[1] : `${PUBLIC_ORIGIN}/favicon.svg`
}

export function socialMetaBlock ({ headline, description, url, image }) {
  const desc = description || headline
  const imageUrl = image || `${PUBLIC_ORIGIN}/favicon.svg`
  return [
    `<meta name="description" content="${escapeHtml(desc)}">`,
    `<meta property="og:type" content="article">`,
    `<meta property="og:site_name" content="The Nostr Observer">`,
    `<meta property="og:title" content="${escapeHtml(headline)}">`,
    `<meta property="og:description" content="${escapeHtml(desc)}">`,
    `<meta property="og:url" content="${escapeHtml(url)}">`,
    `<meta property="og:image" content="${escapeHtml(imageUrl)}">`,
    `<meta name="twitter:card" content="summary_large_image">`,
    `<meta name="twitter:title" content="${escapeHtml(headline)}">`,
    `<meta name="twitter:description" content="${escapeHtml(desc)}">`,
    `<meta name="twitter:image" content="${escapeHtml(imageUrl)}">`,
  ].join('\n  ')
}

export function stripSocialMeta (html) {
  return html
    .replace(/^\s*<meta\b[^>]*\bname=["']description["'][^>]*>\s*$/gim, '')
    .replace(/^\s*<meta\b[^>]*\bproperty=["']og:[^"']+["'][^>]*>\s*$/gim, '')
    .replace(/^\s*<meta\b[^>]*\bname=["']twitter:[^"']+["'][^>]*>\s*$/gim, '')
}

export function formatDate (iso) {
  const [y, m, d] = iso.split('-').map(Number)
  if (!y || !m || !d) return iso
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-US', {
    weekday: 'long',
    month: 'long',
    day: 'numeric',
    year: 'numeric',
    timeZone: 'UTC',
  })
}

export function readPaper (path) {
  const parsed = parseName(path)
  if (!parsed) return null
  const html = readFileSync(path, 'utf8')
  return { ...parsed, path, headline: headlineOf(html) }
}

export function papersIn (dir) {
  if (!existsSync(dir)) return []
  return readdirSync(dir)
    .filter((name) => EDITION_RE.test(name))
    .map((name) => readPaper(join(dir, name)))
    .filter(Boolean)
    .sort((a, b) => (a.date === b.date ? b.code.localeCompare(a.code) : b.date.localeCompare(a.date)))
}

export function matchSelectors (papers, tokens, now = new Date()) {
  const missing = []
  const hits = []
  const seen = new Set()
  for (const raw of tokens) {
    const token = String(raw).trim()
    if (!token) continue
    const wanted = token.toLowerCase() === 'today' ? localToday(now) : token
    const matched = papers.filter((p) => {
      if (p.file === wanted || p.file === basename(wanted)) return true
      if (wanted.endsWith('/' + p.file) || wanted.endsWith('\\' + p.file)) return true
      if (p.code.toLowerCase() === wanted.toLowerCase()) return true
      if (p.date === wanted) return true
      return false
    })
    if (matched.length === 0) missing.push(token)
    for (const p of matched) {
      if (seen.has(p.file)) continue
      seen.add(p.file)
      hits.push(p)
    }
  }
  return { papers: hits, missing }
}

const JUNK_HINTS = new Set(['corpus.json', 'digest.md', 'readiness.json'])

export function check (distDir) {
  const junk = []
  if (!existsSync(distDir)) return { ok: true, junk, papers: [] }
  for (const name of readdirSync(distDir)) {
    if (name === '.vercel' || name === '.DS_Store') continue
    if (SITE_FILES.has(name)) continue
    if (EDITION_RE.test(name)) continue
    junk.push(name)
  }
  return { ok: junk.length === 0, junk, papers: papersIn(distDir) }
}

export function renderIndex (papers) {
  const items = papers.length === 0
    ? `<p class="empty">The shelf is empty. Print a paper, then choose which ones go to press.</p>`
    : `<ol class="issues">${papers.map((p) => `
      <li>
        <a href="${escapeHtml(p.file)}">
          <span class="when">${escapeHtml(formatDate(p.date))}</span>
          <span class="code">${escapeHtml(p.code)}</span>
          <span class="hed">${escapeHtml(p.headline)}</span>
        </a>
      </li>`).join('')}
    </ol>`

  const n = papers.length
  const folio = n === 0 ? 'No editions to press' : n === 1 ? 'One edition to press' : `${n} editions to press`

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>The Nostr Observer — editions</title>
  ${FAVICON_LINK}
  <style>
    :root {
      color-scheme: light;
      --paper: #FAF8F3; --paper-2: #F2EFE8; --ink: #16121B; --ink-2: #4C4553;
      --ink-3: #6E6675; --rule: #C8C1CC; --accent: #57277F; --spot: #9C2B24;
      --serif: "Times New Roman", Times, "Liberation Serif", "Nimbus Roman", serif;
      --body: Georgia, "Liberation Serif", "Times New Roman", Times, serif;
      --util: "Helvetica Neue", Helvetica, Arial, "Liberation Sans", sans-serif;
      --mono: ui-monospace, "SF Mono", Menlo, Consolas, "Liberation Mono", monospace;
    }
    * { box-sizing: border-box; }
    body {
      margin: 0; background: var(--paper); color: var(--ink);
      font-family: var(--body); font-size: 18px; line-height: 1.45;
    }
    .wrap { max-width: 52rem; margin: 0 auto; padding: 28px 22px 72px; }
    .folio {
      font-family: var(--util); font-size: .72rem; letter-spacing: .14em;
      text-transform: uppercase; color: var(--ink-3); margin-bottom: 8px;
    }
    .masthead {
      border-top: 5px solid var(--ink); border-bottom: 1px solid var(--ink);
      padding: 18px 0 14px; text-align: center;
    }
    .masthead h1 {
      margin: 0; font-family: var(--serif); font-size: clamp(2.2rem, 8vw, 4.2rem);
      letter-spacing: .02em; text-transform: uppercase; line-height: .92;
    }
    .masthead .the {
      display: block; font-size: .42em; letter-spacing: .4em; margin-bottom: .3em;
      text-indent: .4em; color: var(--ink-2);
    }
    .motto {
      margin: 12px 0 0; font-family: var(--util); font-size: .7rem;
      letter-spacing: .28em; text-transform: uppercase; color: var(--ink-3);
    }
    .rule { border-bottom: 3px double var(--ink); margin: 14px 0 28px; }
    .empty { color: var(--ink-2); font-style: italic; }
    .issues { list-style: none; margin: 0; padding: 0; }
    .issues li { border-bottom: 1px solid var(--rule); }
    .issues a {
      display: grid; grid-template-columns: 1fr auto; gap: 2px 18px;
      padding: 16px 0; color: inherit; text-decoration: none;
    }
    .issues a:hover .hed { color: var(--accent); }
    .when {
      font-family: var(--util); font-size: .72rem; letter-spacing: .12em;
      text-transform: uppercase; color: var(--ink-3); grid-column: 1;
    }
    .code {
      font-family: var(--mono); font-size: .75rem; letter-spacing: .08em;
      color: var(--spot); grid-column: 2; align-self: start;
    }
    .hed {
      font-family: var(--serif); font-size: 1.35rem; font-weight: 700;
      line-height: 1.15; grid-column: 1 / -1;
    }
    @media (max-width: 560px) {
      .issues a { grid-template-columns: 1fr; }
      .code { grid-column: 1; }
    }
  </style>
</head>
<body>
  <div class="wrap">
    <div class="folio">${escapeHtml(folio)}</div>
    <header class="masthead">
      <h1><span class="the">The</span> Nostr Observer</h1>
      <p class="motto">The editions that went to press</p>
    </header>
    <div class="rule"></div>
    ${items}
  </div>
</body>
</html>
`
}

export const VERCEL_JSON = {
  cleanUrls: false,
  headers: [
    {
      source: '/(.*)',
      headers: [
        {
          key: 'Content-Security-Policy',
          value: "default-src 'none'; img-src 'self' https: data:; style-src 'unsafe-inline'; script-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
        },
        { key: 'X-Content-Type-Options', value: 'nosniff' },
        { key: 'Referrer-Policy', value: 'no-referrer' },
      ],
    },
  ],
}

export function withFavicon (html) {
  if (/rel\s*=\s*["']icon["']/i.test(html)) return html
  if (/<\/title>/i.test(html)) return html.replace(/<\/title>/i, `</title>\n  ${FAVICON_LINK}`)
  if (/<head[^>]*>/i.test(html)) return html.replace(/<head([^>]*)>/i, `<head$1>\n  ${FAVICON_LINK}`)
  return html
}

export function withSocialMeta (html, paper) {
  const headline = paper.headline || headlineOf(html)
  const description = dekOf(html)
  const url = `${PUBLIC_ORIGIN}/${paper.file}`
  const image = firstImageOf(html)
  const block = socialMetaBlock({ headline, description, url, image })
  const stripped = stripSocialMeta(html)
  if (/<\/title>/i.test(stripped)) {
    return stripped.replace(/<\/title>/i, `</title>\n  ${block}`)
  }
  if (/<head[^>]*>/i.test(stripped)) {
    return stripped.replace(/<head([^>]*)>/i, `<head$1>\n  ${block}`)
  }
  return stripped
}

export function writeSite (distDir) {
  mkdirSync(distDir, { recursive: true })
  const papers = papersIn(distDir)
  writeFileSync(join(distDir, 'index.html'), renderIndex(papers))
  writeFileSync(join(distDir, 'vercel.json'), JSON.stringify(VERCEL_JSON, null, 2) + '\n')
  copyFileSync(FAVICON_SRC, join(distDir, 'favicon.svg'))
  for (const paper of papers) {
    const path = join(distDir, paper.file)
    const stamped = withSocialMeta(withFavicon(readFileSync(path, 'utf8')), paper)
    writeFileSync(path, stamped)
  }
  return papers
}

function assertSafeSource (paper) {
  // A 1.5 MB corpus.json must never be copied on a wildcard. The name gate is
  // the whole defence; size is a second tripwire if a "paper" is implausibly large.
  if (!EDITION_RE.test(paper.file)) {
    throw new Error(`refusing to publish ${paper.file}: not an observer-YYYY-MM-DD-CODE.html edition`)
  }
}

export function add (editionsDir, distDir, tokens, now = new Date()) {
  const printed = papersIn(editionsDir)
  const { papers, missing } = matchSelectors(printed, tokens, now)
  if (missing.length) {
    throw new Error(`not in editions/: ${missing.join(', ')}`)
  }
  if (papers.length === 0) throw new Error('name the edition to publish')
  mkdirSync(distDir, { recursive: true })
  for (const paper of papers) {
    assertSafeSource(paper)
    copyFileSync(paper.path, join(distDir, paper.file))
  }
  writeSite(distDir)
  return papers
}

export function remove (distDir, tokens, now = new Date()) {
  const published = papersIn(distDir)
  const { papers, missing } = matchSelectors(published, tokens, now)
  if (missing.length) throw new Error(`not on the shelf: ${missing.join(', ')}`)
  for (const paper of papers) rmSync(join(distDir, paper.file))
  writeSite(distDir)
  return papers
}

export function catalog (editionsDir, distDir) {
  const printed = papersIn(editionsDir)
  const published = papersIn(distDir)
  const onShelf = new Set(published.map((p) => p.file))
  return {
    printed: printed.map((p) => ({ ...p, published: onShelf.has(p.file) })),
    published,
  }
}

function flag (argv, name, fallback) {
  const i = argv.indexOf(name)
  return i === -1 ? fallback : argv[i + 1]
}

function printList (editionsDir, distDir) {
  const { printed, published } = catalog(editionsDir, distDir)
  const line = (p) => `  ${p.date}  ${p.code}  ${p.headline}${p.published === false ? '  [not published]' : ''}`
  console.log(`printed (editions/) — ${printed.length} paper${printed.length === 1 ? '' : 's'}`)
  console.log(printed.length ? printed.map(line).join('\n') : '  (none)')
  console.log(`published (dist/) — ${published.length} paper${published.length === 1 ? '' : 's'}`)
  console.log(published.length ? published.map((p) => line({ ...p, published: true })).join('\n') : '  (empty)')
}

function usage () {
  console.error(`Usage:
  node site.mjs list   [--editions DIR] [--dist DIR] [--json]
  node site.mjs add    <edition...> [--editions DIR] [--dist DIR]
  node site.mjs remove <edition...> [--dist DIR]
  node site.mjs index  [--dist DIR]
  node site.mjs check  [--dist DIR]

  An edition is a filename, a date (YYYY-MM-DD), a code, or "today".
  Never point this at editions/ as --dist. That folder holds the corpus.`)
}

export function main (argv = process.argv.slice(2), cwd = process.cwd()) {
  const cmd = argv[0]
  const editionsDir = resolve(cwd, flag(argv, '--editions', 'editions'))
  const distDir = resolve(cwd, flag(argv, '--dist', 'dist'))
  const rest = argv.slice(1).filter((a, i, arr) => a && !a.startsWith('--') && arr[i - 1] !== '--editions' && arr[i - 1] !== '--dist')

  if (cmd === 'list') {
    if (argv.includes('--json')) {
      console.log(JSON.stringify(catalog(editionsDir, distDir), null, 2))
    } else {
      printList(editionsDir, distDir)
    }
    return 0
  }
  if (cmd === 'add') {
    const added = add(editionsDir, distDir, rest)
    console.log(`added ${added.map((p) => p.file).join(', ')}`)
    return 0
  }
  if (cmd === 'remove') {
    const gone = remove(distDir, rest)
    console.log(`removed ${gone.map((p) => p.file).join(', ')}`)
    return 0
  }
  if (cmd === 'index') {
    const papers = writeSite(distDir)
    console.log(`wrote index.html (${papers.length} edition${papers.length === 1 ? '' : 's'})`)
    return 0
  }
  if (cmd === 'check') {
    const result = check(distDir)
    if (!result.ok) {
      const known = result.junk.filter((n) => JUNK_HINTS.has(n))
      console.error(`dist/ is not safe to deploy. Extra files: ${result.junk.join(', ')}`)
      if (known.length) {
        console.error('Those look like print-run leftovers. dist/ is the public shelf; the corpus stays in editions/.')
      }
      return 1
    }
    console.log(`ok (${result.papers.length} edition${result.papers.length === 1 ? '' : 's'})`)
    return 0
  }
  usage()
  return 2
}

const invoked = process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href
if (invoked) {
  try {
    process.exitCode = main()
  } catch (err) {
    console.error(err.message || err)
    process.exitCode = 1
  }
}
