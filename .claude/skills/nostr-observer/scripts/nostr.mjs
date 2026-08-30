// Bech32, and one relay read.
//
// A port of the parts of `generator/src/main/kotlin/.../nostr/Relays.kt` this
// skill needs, minus everything it does not. Two deliberate absences:
//
//  - NO NIP-45 COUNT, anywhere. `AGENTS.md` records that search-staging sends
//    an AUTH challenge before it answers a COUNT even though `auth_required`
//    is false, that four concurrent COUNTs hang the readiness chain, and that
//    the store goes through spells of not answering COUNTs at all. Every
//    question this skill asks is a REQ, so none of that can happen. What it
//    costs is written down in readiness.mjs: no percentages, ever.
//
//  - NO PUBLISH. This skill reads. It holds no key and signs nothing.
//
// Node 22+ only: `WebSocket` is a global there, so this file has no
// dependencies and the skill needs no install step.

import { randomUUID } from 'node:crypto'

if (typeof WebSocket === 'undefined') {
  console.error('This skill needs Node 22 or newer (it uses the built-in WebSocket). Yours: ' + process.version)
  process.exit(1)
}

// --- bech32 ----------------------------------------------------------------
// Enough to read an npub and to print one. The paper must never print hex
// (see reference/editorial.md, "Never print a hex string"), so encoding is as
// load-bearing as decoding.

const CHARSET = 'qpzry9x8gf2tvdw0s3jn54khce6mua7l'
const GENERATOR = [0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3]

function polymod (values) {
  let chk = 1
  for (const value of values) {
    const top = chk >> 25
    chk = ((chk & 0x1ffffff) << 5) ^ value
    for (let i = 0; i < 5; i++) if ((top >> i) & 1) chk ^= GENERATOR[i]
  }
  return chk
}

function hrpExpand (hrp) {
  const out = []
  for (let i = 0; i < hrp.length; i++) out.push(hrp.charCodeAt(i) >> 5)
  out.push(0)
  for (let i = 0; i < hrp.length; i++) out.push(hrp.charCodeAt(i) & 31)
  return out
}

function convertBits (data, from, to, pad) {
  let acc = 0
  let bits = 0
  const out = []
  const maxv = (1 << to) - 1
  for (const value of data) {
    if (value < 0 || value >> from !== 0) return null
    acc = (acc << from) | value
    bits += from
    while (bits >= to) {
      bits -= to
      out.push((acc >> bits) & maxv)
    }
  }
  if (pad) {
    if (bits > 0) out.push((acc << (to - bits)) & maxv)
  } else if (bits >= from || ((acc << (to - bits)) & maxv)) {
    return null
  }
  return out
}

/** `npub1…` (or bare 64-hex) to lowercase hex. Throws with a readable sentence. */
export function toHex (input) {
  const value = String(input || '').trim()
  if (/^[0-9a-f]{64}$/i.test(value)) return value.toLowerCase()
  if (!value.startsWith('npub1')) {
    throw new Error(`Not an npub: ${value.slice(0, 24)}. Expected something starting with npub1.`)
  }
  const lower = value.toLowerCase()
  const split = lower.lastIndexOf('1')
  const hrp = lower.slice(0, split)
  const chars = lower.slice(split + 1)
  const data = []
  for (const ch of chars) {
    const index = CHARSET.indexOf(ch)
    if (index === -1) throw new Error(`Not an npub: ${value.slice(0, 24)} has a character bech32 does not use.`)
    data.push(index)
  }
  if (polymod(hrpExpand(hrp).concat(data)) !== 1) {
    throw new Error(`That npub does not checksum. Copy it again from your Nostr app: ${value.slice(0, 24)}…`)
  }
  const bytes = convertBits(data.slice(0, -6), 5, 8, false)
  if (!bytes || bytes.length !== 32) throw new Error('That npub decodes to the wrong length.')
  return Buffer.from(bytes).toString('hex')
}

/** Hex to `npub1…`. */
export function toNpub (hex) {
  const bytes = Array.from(Buffer.from(hex, 'hex'))
  const data = convertBits(bytes, 8, 5, true)
  const hrp = 'npub'
  const checksum = polymod(hrpExpand(hrp).concat(data).concat([0, 0, 0, 0, 0, 0])) ^ 1
  const tail = []
  for (let i = 0; i < 6; i++) tail.push((checksum >> (5 * (5 - i))) & 31)
  return hrp + '1' + data.concat(tail).map((d) => CHARSET[d]).join('')
}

/** What a person is called when they have published no name. Never hex. */
export function shortNpub (hex) {
  const npub = toNpub(hex)
  return npub.slice(0, 10) + '…' + npub.slice(-4)
}

// --- relay reads -----------------------------------------------------------

/**
 * How many bytes of filter one REQ may carry.
 *
 * search-staging advertises `max_message_length: 262144` and enforces it the
 * way relays generally do: the oversized frame is DROPPED, with no NOTICE and
 * no CLOSED. The subscription then sits open saying nothing until the idle
 * timer expires, and the caller gets an empty list that looks exactly like a
 * quiet day. Under the advertised cap because the cap is on the whole frame.
 */
export const MAX_REQ_BYTES = 240_000

/**
 * The token that opens the search relay to an UNRANKED query.
 *
 * Measured 2026-08-30: search-staging now CLOSES every REQ and COUNT whose
 * `search` field carries neither an `observer:<hex>` token nor `include:spam`
 * — "auth-required: this relay answers through a web of trust and has no
 * house observer to lend you." So every plain lookup — a kind 0, a 10002, a
 * 10063 — must say `include:spam` to be answered at all. The ranked desks
 * already name their observer and need nothing.
 *
 * This skill attaches it to every unranked query it sends, unconditionally,
 * and that is correct BECAUSE the skill talks to exactly one relay: whatever
 * `--relay` names must be a relay of the observer family anyway — the desks
 * send `observer:` and `sort:rank` to the same host — so there is no leg of
 * any read that could reach a relay that would misread the token as a text
 * search. The Kotlin generator cannot say the same (its profile and Blossom
 * reads fan out to the reader's own relays) and dresses per host there.
 */
export const INCLUDE_SPAM = 'include:spam'

/**
 * One socket per relay, shared by every subscription on it.
 *
 * The first version opened a fresh WebSocket per read: six for the readiness
 * chain, sixteen for a corpus pull, each paying a TCP and TLS handshake to a
 * host AGENTS.md says outright not to hammer — and which advertises a
 * subscription limit of fifty, so the multiplexing was always available. This
 * is what `Relays.kt` does with quartz's one `NostrClient`.
 *
 * The connection closes itself shortly after its last subscription finishes,
 * on an unref'd timer, so consecutive reads reuse it and an idle process can
 * still exit.
 */
const pool = new Map()
const LINGER_MS = 1_500

function connect (url) {
  const live = pool.get(url)
  if (live && !live.dead) {
    clearTimeout(live.linger)
    return live
  }

  const subs = new Map()
  const queued = []
  let ready = false
  const conn = { url, subs, dead: false, error: null, linger: null }

  const fail = (note) => {
    if (conn.dead) return
    conn.dead = true
    conn.error = note
    if (pool.get(url) === conn) pool.delete(url)
    for (const sub of [...subs.values()]) sub.finish(note)
  }

  conn.send = (frame) => { if (ready) conn.socket.send(frame); else queued.push(frame) }
  conn.close = () => { conn.dead = true; if (pool.get(url) === conn) pool.delete(url); try { conn.socket?.close() } catch { /* gone */ } }

  // Nothing left to do: linger briefly in case another read follows, then go.
  conn.release = () => {
    if (subs.size > 0 || conn.dead) return
    clearTimeout(conn.linger)
    conn.linger = setTimeout(() => { if (subs.size === 0) conn.close() }, LINGER_MS)
    conn.linger.unref?.()
  }

  try {
    conn.socket = new WebSocket(url)
  } catch (error) {
    conn.dead = true
    conn.error = `could not open ${url}: ${error.message}`
    return conn
  }

  conn.socket.addEventListener('open', () => { ready = true; for (const frame of queued.splice(0)) conn.socket.send(frame) })
  conn.socket.addEventListener('error', () => fail(`socket error on ${url}`))
  conn.socket.addEventListener('close', () => fail('socket closed'))
  conn.socket.addEventListener('message', (message) => {
    let frame
    try { frame = JSON.parse(message.data) } catch { return }
    if (!Array.isArray(frame)) return
    const [verb, id] = frame

    if (verb === 'EVENT') { subs.get(id)?.push(frame[2]); return }
    if (verb === 'EOSE') { subs.get(id)?.finish(null); return }
    if (verb === 'CLOSED') { subs.get(id)?.finish(`relay closed the subscription: ${frame[2] || 'no reason given'}`); return }
    if (verb === 'NOTICE') process.stderr.write(`  notice from ${url}: ${frame[1]}\n`)
    // AUTH / OK / anything else is not anybody's answer. Every waiting
    // subscription is still alive, so none of their idle clocks may advance:
    // search-staging sends an AUTH challenge unprompted, and a reader that
    // treats the first non-EVENT frame as the result reports an empty relay.
    for (const sub of subs.values()) sub.touch()
  })

  pool.set(url, conn)
  return conn
}

/** Shut every pooled connection. Scripts call this so the process can exit. */
export function closeAll () {
  for (const conn of [...pool.values()]) conn.close()
}

/**
 * Everything matching, from one relay.
 * The timeout is an IDLE window, not a deadline: this drains until the relay
 * has said nothing for `idleMs`, so a slow relay finishes and a silent one is
 * given up on. Reading it as a deadline is a mistake this project's sibling
 * has already paid for once.
 *
 * AUTH, NOTICE and OK frames are IGNORED rather than treated as the answer.
 * search-staging sends an AUTH challenge unprompted; anything that resolves on
 * the first non-EVENT frame reads that challenge as the result and reports an
 * empty relay.
 */
export function req (url, filters, { idleMs = 15_000, label = '' } = {}) {
  const list = Array.isArray(filters) ? filters : [filters]
  const frame = JSON.stringify(list)
  // BYTES, not characters. The relay's `max_message_length` is a byte count,
  // and `.length` under-reports every non-ASCII character — so a filter up to
  // twice the cap passed this guard and was then dropped in silence, which is
  // precisely the failure the guard exists to prevent.
  const size = Buffer.byteLength(frame)
  if (size > MAX_REQ_BYTES) {
    return Promise.reject(new Error(
      `REQ is ${size} bytes, over the ${MAX_REQ_BYTES}-byte budget. Chunk its authors or lower a desk limit.`
    ))
  }

  return new Promise((resolve) => {
    const conn = connect(url)
    const sub = randomUUID().slice(0, 12)
    const events = []
    const seen = new Set()
    let idle
    let hard
    let done = false

    const finish = (note) => {
      if (done) return
      done = true
      clearTimeout(idle)
      clearTimeout(hard)
      conn.subs.delete(sub)
      if (!conn.dead) { try { conn.send(JSON.stringify(['CLOSE', sub])) } catch { /* gone */ } }
      conn.release?.()
      resolve({ events, note, relay: url })
    }

    if (conn.dead) return finish(conn.error || `could not reach ${url}${label ? ` (${label})` : ''}`)

    const touch = () => {
      clearTimeout(idle)
      idle = setTimeout(() => finish('idle'), idleMs)
    }

    conn.subs.set(sub, {
      finish,
      touch,
      push: (event) => {
        if (event?.id && !seen.has(event.id)) { seen.add(event.id); events.push(event) }
        touch()
      },
    })

    // A wall clock over the idle clock. Honest about what this is: a guard,
    // not a fix for a diagnosed bug. No read in this skill should be able to
    // block forever, and an empty list is a supported answer everywhere.
    hard = setTimeout(() => finish('deadline'), idleMs * 2 + 5_000)
    touch()
    conn.send(JSON.stringify(['REQ', sub, ...list]))
  })
}

/** The newest event matching, or null. */
export async function one (url, filter, options) {
  const { events } = await req(url, { ...filter, limit: 1 }, options)
  return events.sort((a, b) => b.created_at - a.created_at)[0] || null
}

/** First value of a tag, or null. */
export function tagValue (event, name) {
  return event?.tags?.find((t) => t[0] === name)?.[1] ?? null
}

export function tagsNamed (event, name) {
  return (event?.tags || []).filter((t) => t[0] === name)
}
