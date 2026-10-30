'use strict';
// The hand-rolled UTF-8 and base64 helpers against Node's own encoders, and signal() truncation,
// which is built on them. Hermes has TextEncoder and no TextDecoder, so only encoding may lean on
// the platform.

const test = require('node:test');
const assert = require('node:assert/strict');
const { connected, producer } = require('./fake-ws.js');

const { utf8Length, utf8Prefix, utf8Decode, base64 } = producer._test;

const SAMPLES = [
  '',
  'plain ascii',
  'café',
  '€100',
  '日本語のテキスト',
  'emoji 😀 and 👍🏽 pairs',
  '\ud800 lone high',
  'lone low \udc00 here',
  'pair split by nothing \ud83d',
  '\udc00\ud800',
  'x'.repeat(70000) + '😀'.repeat(300),
];

test('utf8Length counts bytes, as TextEncoder and Buffer do', () => {
  for (const s of SAMPLES) {
    const expected = new TextEncoder().encode(s).length;
    assert.equal(utf8Length(s), expected, JSON.stringify(s.slice(0, 30)));
    assert.equal(utf8Length(s), Buffer.byteLength(s, 'utf8'));
  }
});

test('utf8Length counted in chunks equals the whole, wherever a chunk boundary falls', () => {
  const s = 'a😀b€c\ud800d😀😀é';
  for (let chunk = 1; chunk <= s.length; chunk++) {
    let n = 0;
    for (let from = 0; from < s.length; from += chunk) n += utf8Length(s, from, Math.min(s.length, from + chunk));
    assert.equal(n, Buffer.byteLength(s, 'utf8'), `chunk ${chunk}`);
  }
});

test('utf8Prefix is the first n bytes of the encoding, even when n splits a character', () => {
  for (const s of SAMPLES) {
    const all = new TextEncoder().encode(s);
    for (const n of [0, 1, 2, 3, 4, 5, 7, 11, 65535, 65536, 65537, 70001, 70002, 70003]) {
      assert.deepEqual(Buffer.from(utf8Prefix(s, n)), Buffer.from(all.subarray(0, n)), `${JSON.stringify(s.slice(0, 20))} n=${n}`);
    }
  }
});

test('utf8Decode matches a fatal TextDecoder: the text when valid, null when not', () => {
  const fatal = new TextDecoder('utf-8', { fatal: true });
  const cases = SAMPLES.map((s) => new TextEncoder().encode(s));
  const euro = new TextEncoder().encode('€😀');
  for (let n = 0; n <= euro.length; n++) cases.push(euro.subarray(0, n)); // every cut, split ones included
  cases.push(
    Uint8Array.of(0xc0, 0x80), // overlong NUL
    Uint8Array.of(0xe0, 0x80, 0x80), // overlong
    Uint8Array.of(0xed, 0xa0, 0x80), // an encoded surrogate
    Uint8Array.of(0xf4, 0x90, 0x80, 0x80), // past U+10FFFF
    Uint8Array.of(0x80), // a lone continuation byte
    Uint8Array.of(0xff),
    Uint8Array.of(0xe2, 0x28, 0xa1), // a bad continuation
  );
  for (const bytes of cases) {
    let expected;
    try { expected = fatal.decode(bytes); } catch (_) { expected = null; }
    assert.equal(utf8Decode(bytes), expected, Buffer.from(bytes).toString('hex').slice(0, 40));
  }
});

test('base64 matches Buffer for every length mod 3', () => {
  for (let len = 0; len < 40; len++) {
    const bytes = new Uint8Array(len);
    for (let i = 0; i < len; i++) bytes[i] = (i * 97 + 13) & 0xff;
    assert.equal(base64(bytes), Buffer.from(bytes).toString('base64'), `length ${len}`);
  }
  const big = new TextEncoder().encode('😀'.repeat(20000));
  assert.equal(base64(big), Buffer.from(big).toString('base64'));
});

function sentSignal(data) {
  const h = connected();
  h.p.signal('cache', 'orders', data);
  h.advance(0);
  return JSON.parse(h.last.sent[1]);
}

test('a signal under 64 KB carries its whole payload as JSON text', () => {
  const frame = sentSignal({ items: [1, 2], who: 'zoë' });
  assert.equal(frame.data, '{"items":[1,2],"who":"zoë"}');
  assert.equal(frame.signal.bytes, 28);
  assert.equal('dataTruncated' in frame.signal, false);
  assert.equal('dataB64' in frame, false);
  assert.equal(sentSignal('just text').data, '"just text"', 'a string is JSON-quoted, as Kotlin does');
  const cyclic = {};
  cyclic.self = cyclic;
  assert.equal(sentSignal(cyclic).data, '"[object Object]"', 'what JSON cannot take is its String() form');
});

test('a signal over 64 KB sends the prefix bytes as text when the cut falls between characters', () => {
  const payload = 'a'.repeat(70000);
  const encoded = JSON.stringify(payload);
  const frame = sentSignal(payload);
  assert.equal(frame.signal.dataTruncated, true);
  assert.equal(frame.signal.bytes, Buffer.byteLength(encoded));
  assert.equal(frame.data, encoded.slice(0, 65536));
  assert.equal('dataB64' in frame, false);
});

test('a signal over 64 KB sends base64 when the cut splits a multibyte character', () => {
  // '"x' then 3-byte characters: byte 65536 falls inside one of them.
  const payload = 'x' + '€'.repeat(30000);
  const encoded = Buffer.from(JSON.stringify(payload), 'utf8');
  assert.notEqual((65536 - 2) % 3, 0, 'the fixture really does split a character');
  const frame = sentSignal(payload);
  assert.equal(frame.signal.dataTruncated, true);
  assert.equal(frame.signal.bytes, encoded.length);
  assert.equal(frame.dataB64, true);
  assert.equal(frame.data, encoded.subarray(0, 65536).toString('base64'));
});
