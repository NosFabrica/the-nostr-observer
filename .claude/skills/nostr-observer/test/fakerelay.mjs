// A relay that behaves badly on purpose.
//
// CI for this repository states that nothing in it talks to a relay or to the
// model API, and that a test which quietly needed a network would stop running
// the first week CI was busy. So the hazards recorded in AGENTS.md — the AUTH
// challenge sent before an answer, the subscription that says nothing, the
// CLOSED with a reason — are reproduced here instead of hoped for.
//
// Hand-rolled because Node ships a WebSocket CLIENT and no server, and the
// skill it tests has no dependencies. Enough of RFC 6455 for short text
// frames on loopback, and no more.

import { createServer } from 'node:http'
import { createHash } from 'node:crypto'

const GUID = '258EAFA5-E914-47DA-95CA-C5AB0DC85B11'

function encode (text) {
  const payload = Buffer.from(text, 'utf8')
  const header = payload.length < 126
    ? Buffer.from([0x81, payload.length])
    : Buffer.concat([Buffer.from([0x81, 126]), (() => { const b = Buffer.alloc(2); b.writeUInt16BE(payload.length); return b })()])
  return Buffer.concat([header, payload])
}

/** Every complete frame in `buffer`, and what is left over. */
function decode (buffer) {
  const frames = []
  let at = 0
  while (at + 2 <= buffer.length) {
    const opcode = buffer[at] & 0x0f
    const masked = (buffer[at + 1] & 0x80) !== 0
    let length = buffer[at + 1] & 0x7f
    let cursor = at + 2
    if (length === 126) {
      if (cursor + 2 > buffer.length) break
      length = buffer.readUInt16BE(cursor); cursor += 2
    } else if (length === 127) {
      if (cursor + 8 > buffer.length) break
      length = Number(buffer.readBigUInt64BE(cursor)); cursor += 8
    }
    let mask
    if (masked) {
      if (cursor + 4 > buffer.length) break
      mask = buffer.subarray(cursor, cursor + 4); cursor += 4
    }
    if (cursor + length > buffer.length) break
    const payload = Buffer.from(buffer.subarray(cursor, cursor + length))
    if (mask) for (let i = 0; i < payload.length; i++) payload[i] ^= mask[i % 4]
    at = cursor + length
    if (opcode === 0x1) frames.push(payload.toString('utf8'))
    if (opcode === 0x8) frames.push(null) // close
  }
  return { frames, rest: buffer.subarray(at) }
}

/**
 * Start a relay whose answer to a REQ is whatever `respond` returns.
 *
 * `respond(filters, sub)` returns an array of frames to send, each either a
 * ready-made array (sent as JSON) or the string 'silence' meaning send nothing
 * at all and leave the subscription hanging.
 */
export async function fakeRelay (respond) {
  const sockets = new Set()
  let connections = 0
  const server = createServer()

  server.on('upgrade', (request, socket) => {
    const key = request.headers['sec-websocket-key']
    const accept = createHash('sha1').update(key + GUID).digest('base64')
    socket.write(
      'HTTP/1.1 101 Switching Protocols\r\n'
      + 'Upgrade: websocket\r\nConnection: Upgrade\r\n'
      + `Sec-WebSocket-Accept: ${accept}\r\n\r\n`,
    )
    connections++
    sockets.add(socket)
    socket.on('close', () => sockets.delete(socket))
    socket.on('error', () => sockets.delete(socket))

    let buffer = Buffer.alloc(0)
    socket.on('data', (chunk) => {
      buffer = Buffer.concat([buffer, chunk])
      const { frames, rest } = decode(buffer)
      buffer = rest
      for (const frame of frames) {
        if (frame === null) { socket.end(); continue }
        let message
        try { message = JSON.parse(frame) } catch { continue }
        if (!Array.isArray(message) || message[0] !== 'REQ') continue
        const [, sub, ...filters] = message
        for (const out of respond(filters, sub)) {
          if (out === 'silence') break
          socket.write(encode(JSON.stringify(out)))
        }
      }
    })
  })

  await new Promise((done) => server.listen(0, '127.0.0.1', done))
  const { port } = server.address()
  return {
    url: `ws://127.0.0.1:${port}`,
    /** How many times a client actually dialled. */
    get connections () { return connections },
    async close () {
      for (const socket of sockets) socket.destroy()
      await new Promise((done) => server.close(done))
    },
  }
}

/** A minimal well-formed event. */
export function event (id, extra = {}) {
  return {
    id,
    pubkey: 'aa'.repeat(32),
    created_at: 1_786_900_000,
    kind: 1,
    tags: [],
    content: 'hello',
    sig: '00'.repeat(64),
    ...extra,
  }
}
