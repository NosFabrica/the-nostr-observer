// The artifact copy: pictures go in, the boundary's properties stay.
//
// Same no-network rule as everything else in CI: the fetcher is injected, so
// these bytes never leave the process. What is held here: only shortlist URLs
// are fetched at all, the server's Content-Type is not believed, budgets are
// enforced, a failure degrades to the hotlink instead of removing the figure,
// and the edition file itself is never the output.

import { test } from 'node:test'
import assert from 'node:assert/strict'
import { embed, sniff } from '../scripts/embed.mjs'

const PNG = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==', 'base64')

const answer = (bytes, status = 200) => ({
  ok: status >= 200 && status < 300,
  status,
  arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength),
})

const corpus = { art: [
  { id: 'art-1', url: 'https://media.example/real.png' },
  { id: 'art-2', url: 'https://media.example/big.png' },
  { id: 'art-3', url: 'https://media.example/fake.png' },
] }

test('a shortlist picture is embedded as a data URI, by its real magic bytes', async () => {
  const html = '<figure><img src="https://media.example/real.png" alt="a"></figure>'
  const fetched = []
  const { html: out, report } = await embed(html, corpus, {
    fetchImpl: async (url) => { fetched.push(url); return answer(PNG) },
  })
  assert.deepEqual(fetched, ['https://media.example/real.png'])
  assert.match(out, /src="data:image\/png;base64,/)
  assert.deepEqual(report.map((r) => r.kind), ['embedded'])
})

test('a URL that is not on the shortlist is never fetched', async () => {
  // The corpus is where the attacker writes; "fetch whatever the page says"
  // is an outbound request on their behalf.
  const html = '<img src="https://evil.example/x.png" alt="a">'
  const fetched = []
  const { html: out, report } = await embed(html, corpus, {
    fetchImpl: async (url) => { fetched.push(url); return answer(PNG) },
  })
  assert.deepEqual(fetched, [], 'nothing off the shortlist may be dialled')
  assert.match(out, /src="https:\/\/evil\.example\/x\.png"/, 'and the tag is left alone')
  assert.deepEqual(report.map((r) => r.kind), ['refused'])
})

test('the Content-Type header is a claim; the bytes decide', async () => {
  // A host serving HTML as image/png is either broken or lying, and either
  // way its bytes do not go into the page.
  const html = '<img src="https://media.example/fake.png" alt="a">'
  const { html: out, report } = await embed(html, corpus, {
    fetchImpl: async () => answer(Buffer.from('<html>not a picture</html>')),
  })
  assert.match(out, /src="https:\/\/media\.example\/fake\.png"/, 'the hotlink stays; nothing is removed')
  assert.equal(report[0].kind, 'skipped')
  assert.match(report[0].detail, /magic bytes/)
})

test('budgets are enforced and a skip degrades, loudly', async () => {
  const html = '<img src="https://media.example/big.png" alt="big">'
  + '<img src="https://media.example/real.png" alt="ok">'
  const big = Buffer.concat([PNG, Buffer.alloc(64)])
  const { html: out, report } = await embed(html, corpus, {
    fetchImpl: async (url) => answer(url.endsWith('big.png') ? big : PNG),
    perImage: big.length - 1,
  })
  const kinds = Object.fromEntries(report.map((r) => [r.url, r.kind]))
  assert.equal(kinds['https://media.example/big.png'], 'skipped')
  assert.equal(kinds['https://media.example/real.png'], 'embedded')
  assert.match(out, /src="https:\/\/media\.example\/big\.png"/, 'over-budget keeps its URL')
  assert.match(out, /src="data:image\/png;base64,/, 'the one under budget is inlined')
})

test('a dead host costs one picture, not the page', async () => {
  const html = '<img src="https://media.example/real.png" alt="a">'
  const { html: out, report } = await embed(html, corpus, {
    fetchImpl: async () => { throw new Error('connect refused') },
  })
  assert.match(out, /src="https:\/\/media\.example\/real\.png"/)
  assert.equal(report[0].kind, 'skipped')
})

test('one URL in two figures is fetched once and embedded twice', async () => {
  const html = '<img src="https://media.example/real.png" alt="a">'
  + '<img src="https://media.example/real.png" alt="b">'
  let calls = 0
  const { html: out } = await embed(html, corpus, {
    fetchImpl: async () => { calls++; return answer(PNG) },
  })
  assert.equal(calls, 1)
  assert.equal((out.match(/data:image\/png;base64,/g) || []).length, 2)
})

test('a quoting hazard in a caption does not hide the picture from this pass either', async () => {
  // The audit's fail-open bug, held shut here the same way as in resolve and
  // validate: alt text is corpus-controlled, and `>` inside it must not end
  // the tag early.
  const html = '<img alt="a > b" src="https://media.example/real.png">'
  const { html: out } = await embed(html, corpus, { fetchImpl: async () => answer(PNG) })
  assert.match(out, /data:image\/png;base64,/)
})

test('sniff knows the five raster formats and nothing else', () => {
  assert.equal(sniff(PNG), 'image/png')
  assert.equal(sniff(Buffer.from([0xff, 0xd8, 0xff, 0xe0])), 'image/jpeg')
  assert.equal(sniff(Buffer.from('GIF89a......')), 'image/gif')
  assert.equal(sniff(Buffer.from('RIFF0000WEBPVP8 ')), 'image/webp')
  assert.equal(sniff(Buffer.from('\0\0\0 ftypavif\0\0\0\0')), 'image/avif')
  assert.equal(sniff(Buffer.from('<svg xmlns="…">')), null, 'an SVG is a document, not a picture')
})
