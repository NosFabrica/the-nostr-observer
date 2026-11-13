// Just enough HTML to read a tag correctly.
//
// The first version of the boundary found attributes with a regex like
// `<img\b[^>]*?\bsrc\s*=\s*"([^"]*)"`. That is wrong in a way that FAILS OPEN,
// which is the only direction that matters here: a `>` inside any attribute
// value ends `[^>]*?` early, the tag is never matched, and the element is
// never checked. `<img alt="a > b" src="https://evil.example/x.jpg">` sailed
// through the shortlist check, and `<a title="1 > 2" href="…">` sailed through
// the link check. Captions come from the corpus, and the corpus is where the
// attacker writes.
//
// A regex cannot fix this, because knowing where a tag ends means tracking
// whether you are inside a quoted value. So: a scanner that does.
//
// Not a parser. It does not build a tree, resolve entities in names, or care
// about `<!-- -->`. It answers two questions — where does this tag end, and
// what are its attributes — and those are the two the boundary asks.

/** Every `<name …>` in `html`, with the span it occupies. */
export function tags (html, name = '[a-z][a-z0-9-]*') {
  const out = []
  const open = new RegExp(`<(${name})(?=[\\s/>])`, 'gi')
  let match
  while ((match = open.exec(html)) !== null) {
    let at = match.index + match[0].length
    let quote = null
    while (at < html.length) {
      const ch = html[at]
      if (quote) {
        if (ch === quote) quote = null
      } else if (ch === '"' || ch === "'") {
        quote = ch
      } else if (ch === '>') {
        break
      }
      at++
    }
    // An unterminated tag is the end of anything we can read reliably.
    if (at >= html.length) break
    out.push({ name: match[1].toLowerCase(), start: match.index, end: at + 1, raw: html.slice(match.index, at + 1) })
    open.lastIndex = at + 1
  }
  return out
}

/**
 * The attributes of one tag, read in order.
 *
 * In order, and not by searching the tag for a name, because searching finds
 * the wrong one: in `<img alt="src=evil.jpg" src="art-1">` a search for `src=`
 * hits the text inside `alt` first. Names are lowercased; values keep their
 * case.
 */
export function attributes (raw) {
  const out = {}
  const after = /^<[a-z][a-z0-9-]*/i.exec(raw)
  if (!after) return out
  let at = after[0].length
  const end = raw.length - 1 // the closing '>'

  const skip = () => { while (at < end && /\s/.test(raw[at])) at++ }

  while (at < end) {
    skip()
    if (at >= end || raw[at] === '/') break
    const nameStart = at
    while (at < end && !/[\s=/>]/.test(raw[at])) at++
    const name = raw.slice(nameStart, at).toLowerCase()
    if (!name) { at++; continue }
    skip()
    if (raw[at] !== '=') { out[name] = ''; continue } // boolean attribute
    at++
    skip()
    let value
    if (raw[at] === '"' || raw[at] === "'") {
      const quote = raw[at++]
      const valueStart = at
      while (at < end && raw[at] !== quote) at++
      value = raw.slice(valueStart, at)
      at++
    } else {
      const valueStart = at
      while (at < end && !/\s/.test(raw[at])) at++
      value = raw.slice(valueStart, at)
    }
    out[name] = value
  }
  return out
}

/** Text between `<name>` and its matching `</name>`, nesting handled. */
export function textIn (html, name) {
  const found = []
  for (const tag of tags(html, name)) {
    if (tag.raw.endsWith('/>')) continue
    let depth = 1
    const scan = new RegExp(`</?${name}(?=[\\s/>])`, 'gi')
    scan.lastIndex = tag.end
    let close = -1
    let inner
    while ((inner = scan.exec(html)) !== null) {
      depth += inner[0][1] === '/' ? -1 : 1
      if (depth === 0) { close = inner.index; break }
    }
    if (close === -1) continue
    found.push({ start: tag.start, end: close, raw: html.slice(tag.end, close) })
  }
  return found
}
