'use strict';
// The two requests a daemon can send a JS app, and the exact replies. Both are sent at once and
// outside the ring, so a full queue neither delays nor drops them.

const test = require('node:test');
const assert = require('node:assert/strict');
const { connected, producer } = require('./fake-ws.js');

const SIGN_ERROR = "this app's JavaScript producer cannot regenerate per-request headers. " +
  'Untick "regenerate per-request headers (re-sign)" and replay without re-signing.';

function withFullQueue() {
  const h = connected();
  for (let i = 0; i < producer._test.RING_FRAMES + 50; i++) h.p.signal('state', 'n' + i);
  assert.equal(h.last.sent.length, 1, 'nothing flushed yet: the queue is full');
  return h;
}

test('signReq is answered with signRes, its requestId and the error that names the fix', () => {
  const h = withFullQueue();
  h.last.reply({ type: 'signReq', requestId: 'r-41', method: 'POST', url: 'https://api.example.test/v1/orders' });
  assert.equal(h.last.sent.length, 2);
  assert.deepEqual(JSON.parse(h.last.sent[1]), { type: 'signRes', requestId: 'r-41', error: SIGN_ERROR });
  assert.equal(h.p.stats().dropped, 50, 'the reply took no slot in the ring');
});

test("signalReq is answered with signalErr: 'no provider for <tag>/<name>; registered: none'", () => {
  const h = withFullQueue();
  h.last.reply({ type: 'signalReq', requestId: 'r-42', tag: 'cache', name: 'orders' });
  assert.equal(h.last.sent.length, 2);
  assert.deepEqual(JSON.parse(h.last.sent[1]), {
    type: 'signalErr', requestId: 'r-42', error: 'no provider for cache/orders; registered: none',
  });
});

// The high-water gate holds the ring's frames, never these: the host is waiting on the reply, and a
// socket the daemon is slow to read is exactly when an unanswered request would hang it (KIN-172).
test('a socket above the high-water mark still sends signRes and signalErr at once', () => {
  const h = connected({ bufferedAmount: true });
  const sock = h.last;
  sock.bufferedAmount = producer._test.SOCKET_HIGH_WATER + 1;
  h.p.signal('state', 'held');
  h.advance(0);
  assert.equal(sock.sent.length, 1, 'the gate holds the signal');
  sock.reply({ type: 'signReq', requestId: 'r-51', method: 'GET', url: 'https://api.example.test/v1' });
  sock.reply({ type: 'signalReq', requestId: 'r-52', tag: 'cache', name: 'orders' });
  assert.deepEqual(sock.frames().slice(1).map((f) => [f.type, f.requestId]), [['signRes', 'r-51'], ['signalErr', 'r-52']]);
});
