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

function encodeBech32 (hrp, bytes) {
  const data = convertBits(Array.from(bytes), 8, 5, true)
  if (!data) throw new Error('bech32 encode failed')
  const checksum = polymod(hrpExpand(hrp).concat(data).concat([0, 0, 0, 0, 0, 0])) ^ 1
  const tail = []
  for (let i = 0; i < 6; i++) tail.push((checksum >> (5 * (5 - i))) & 31)
  return hrp + '1' + data.concat(tail).map((d) => CHARSET[d]).join('')
}

function decodeBech32 (value) {
  const lower = String(value || '').trim().toLowerCase()
  const split = lower.lastIndexOf('1')
  if (split < 1) throw new Error(`Not bech32: ${String(value).slice(0, 24)}`)
  const hrp = lower.slice(0, split)
  const chars = lower.slice(split + 1)
  const data = []
  for (const ch of chars) {
    const index = CHARSET.indexOf(ch)
    if (index === -1) throw new Error(`Not bech32: ${String(value).slice(0, 24)} has a character bech32 does not use.`)
    data.push(index)
  }
  if (polymod(hrpExpand(hrp).concat(data)) !== 1) {
    throw new Error(`That ${hrp} does not checksum.`)
  }
  const bytes = convertBits(data.slice(0, -6), 5, 8, false)
  if (!bytes) throw new Error(`That ${hrp} does not decode.`)
  return { hrp, bytes }
}

/** Hex to `npub1…`. */
export function toNpub (hex) {
  return encodeBech32('npub', Buffer.from(hex, 'hex'))
}

/**
 * Event id hex to `nevent1…` (NIP-19, id only).
 *
 * jumble.social's note URLs take an nevent, not bare hex. The writer still
 * cites hex; resolve.mjs is what encodes. Relays and author are omitted on
 * purpose: a permalink that names only the event cannot smuggle a relay
 * the corpus never spoke to.
 */
export function toNevent (hex) {
  const id = String(hex || '').toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(id)) throw new Error(`Not an event id: ${String(hex).slice(0, 16)}`)
  return encodeBech32('nevent', [0, 32, ...Buffer.from(id, 'hex')])
}

export const LIVE_KIND = 30311

/**
 * Kind 30311 address to `naddr1…` (NIP-19 TLV: 0=identifier, 2=author, 3=kind).
 *
 * The first version of this encoding put kind in type 0 and the d-tag in type 2,
 * which produced bech32 that looked valid and failed on every gateway. NIP-19
 * is explicit; follow it.
 */
export function toNaddr ({ kind, pubkey, identifier }) {
  const id = String(identifier || '')
  const pk = String(pubkey || '').toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(pk)) throw new Error(`Not a pubkey: ${pk.slice(0, 16)}`)
  const idBytes = Buffer.from(id, 'utf8')
  const bytes = [
    0, idBytes.length, ...idBytes,
    2, 32, ...Buffer.from(pk, 'hex'),
    3, 4, (kind >>> 24) & 255, (kind >>> 16) & 255, (kind >>> 8) & 255, kind & 255,
  ]
  return encodeBech32('naddr', bytes)
}

/** `naddr1…` to `{ kind, pubkey, identifier }`. Throws on bad input. */
export function fromNaddr (input) {
  const value = String(input || '').trim()
  const { hrp, bytes } = decodeBech32(value)
  if (hrp !== 'naddr') throw new Error(`Not an naddr: ${value.slice(0, 24)}`)
  let i = 0
  let identifier = null
  let pubkey = null
  let kind = null
  while (i + 2 <= bytes.length) {
    const type = bytes[i]
    const len = bytes[i + 1]
    i += 2
    if (i + len > bytes.length) break
    const payload = bytes.slice(i, i + len)
    i += len
    if (type === 0) identifier = Buffer.from(payload).toString('utf8')
    if (type === 2 && len === 32) pubkey = Buffer.from(payload).toString('hex')
    if (type === 3 && len === 4) {
      kind = ((payload[0] << 24) | (payload[1] << 16) | (payload[2] << 8) | payload[3]) >>> 0
    }
  }
  if (identifier == null || !pubkey || kind == null) throw new Error('That naddr is missing fields.')
  return { kind, pubkey, identifier }
}

/** Canonical zap.stream watch page for a live event. */
export function toZapStreamUrl (event) {
  const d = tagValue(event, 'd')
  if (!d || event.kind !== LIVE_KIND) throw new Error('Not a live stream address')
  return `https://zap.stream/${toNaddr({ kind: LIVE_KIND, pubkey: event.pubkey, identifier: d })}`
}

/** Writer form: event id hex. resolve.mjs encodes the naddr afterwards. */
export function streamWriterUrl (eventId) {
  const id = String(eventId || '').toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(id)) throw new Error(`Not an event id: ${String(eventId).slice(0, 16)}`)
  return `https://zap.stream/stream/${id}`
}

export const CLASSIFIED_KIND = 30402

/** Canonical Shopstr listing page for a classified. */
export function toShopstrUrl (event) {
  const d = tagValue(event, 'd')
  if (!d || event.kind !== CLASSIFIED_KIND) throw new Error('Not a classified address')
  return `https://shopstr.store/listing/${toNaddr({ kind: CLASSIFIED_KIND, pubkey: event.pubkey, identifier: d })}`
}

/** Writer form: event id hex. resolve.mjs encodes the naddr afterwards. */
export function classifiedWriterUrl (eventId) {
  const id = String(eventId || '').toLowerCase()
  if (!/^[0-9a-f]{64}$/.test(id)) throw new Error(`Not an event id: ${String(eventId).slice(0, 16)}`)
  return `https://shopstr.store/listing/${id}`
}

/** `nevent1…` to lowercase event-id hex. Throws if it is not an nevent that names an id. */
export function fromNevent (input) {
  const value = String(input || '').trim()
  const { hrp, bytes } = decodeBech32(value)
  if (hrp !== 'nevent') throw new Error(`Not an nevent: ${value.slice(0, 24)}`)
  let i = 0
  let id = null
  while (i + 2 <= bytes.length) {
    const type = bytes[i]
    const len = bytes[i + 1]
    i += 2
    if (i + len > bytes.length) break
    const payload = bytes.slice(i, i + len)
    i += len
    if (type === 0) {
      if (payload.length !== 32) throw new Error('That nevent names an id of the wrong length.')
      id = Buffer.from(payload).toString('hex')
    }
  }
  if (!id) throw new Error('That nevent does not name an event.')
  return id
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
