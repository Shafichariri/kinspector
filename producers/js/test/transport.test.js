'use strict';
// The transport half of inspector-producer.js against a scripted socket: framing, the session,
// backoff, the ring and the flush budget. See docs/decisions/capture.md, "JavaScript producers".

const test = require('node:test');
const assert = require('node:assert/strict');
const { harness, connected, CONFIG, producer } = require('./fake-ws.js');

const { RING_FRAMES, RING_CHARS, FLUSH_CHARS, SOCKET_HIGH_WATER } = producer._test;

function isPlainObject(v) {
  return v !== null && typeof v === 'object' && !Array.isArray(v);
}

test('hello is the first frame, and every send is one JSON object, never an array', () => {
  const h = harness();
  h.p.start(CONFIG);
  h.p.signal('screen', 'Home');
  h.p.signal('screen', 'Cart');
  h.last.open();
  const hello = JSON.parse(h.last.sent[0]);
  assert.equal(hello.type, 'hello');
  assert.deepEqual(hello.client, {
    v: 1, appId: 'dev.example.app', appVersion: '1.0', platform: 'android-emulator',
    device: 'unknown', osVersion: 'unknown', buildType: 'debug',
  });
  assert.equal('resumeSessionId' in hello, false);
  h.last.ack('s1');
  h.advance(0);
  h.p.stop();
  for (const text of h.sockets[0].sent) {
    const msg = JSON.parse(text);
    assert.ok(isPlainObject(msg), `one object per send: ${text.slice(0, 60)}`);
    assert.equal(typeof msg.type, 'string');
  }
  assert.equal(h.sockets[0].sent.length, 4, 'hello, two signals, bye');
});

test('nothing but hello goes out before helloAck, and the queue flushes after it', () => {
  const h = harness();
  h.p.start(CONFIG);
  h.p.signal('screen', 'Home');
  h.last.open();
  h.advance(1000);
  assert.deepEqual(h.last.frames().map((f) => f.type), ['hello']);
  h.last.ack('s1');
  assert.equal(h.last.sent.length, 1, 'the flush is a later tick, not the ack handler');
  h.advance(0);
  assert.deepEqual(h.last.frames().map((f) => f.type), ['hello', 'signal']);
  assert.deepEqual(h.p.stats(), { state: 'connected', url: 'ws://10.0.2.2:8099/ingest', sessionId: 's1', dropped: 0, lastError: null });
});

test('frames queued while the daemon was away go out after the next helloAck', () => {
  const h = connected();
  h.last.fail();
  h.p.signal('screen', 'Offline');
  h.advance(250);
  h.last.open();
  h.last.ack('s1');
  h.advance(0);
  assert.deepEqual(h.last.frames().map((f) => f.type), ['hello', 'signal']);
  assert.equal(h.last.frames()[1].signal.name, 'Offline');
});

test('a reconnect sends resumeSessionId, and a reload is a new session', () => {
  const h = connected();
  h.last.fail();
  h.advance(250);
  h.last.open();
  assert.equal(JSON.parse(h.last.sent[0]).resumeSessionId, 's1');

  // A reload is a fresh module: nothing carries the old id over, and mono restarts with it.
  const reloaded = harness();
  reloaded.p.start(CONFIG);
  reloaded.last.open();
  assert.equal('resumeSessionId' in JSON.parse(reloaded.last.sent[0]), false);
});

test('backoff runs 250, 500, 1000, 2000, 4000, 5000 ms and resets on helloAck', () => {
  const h = harness();
  h.p.start(CONFIG);
  const delays = [];
  for (let i = 0; i < 8; i++) {
    h.last.fail();
    const timer = h.timers.find((t) => t.delay > 0);
    delays.push(timer.delay);
    h.advance(timer.delay);
  }
  assert.deepEqual(delays, [250, 500, 1000, 2000, 4000, 5000, 5000, 5000]);

  h.last.open();
  h.last.ack('s1');
  h.last.fail();
  assert.equal(h.timers.find((t) => t.delay > 0).delay, 250, 'the ack resets the backoff');
});

test('the ring keeps the newest 512 frames, drops the oldest and counts each drop', () => {
  const h = harness();
  h.p.start(CONFIG);
  h.last.fail();
  for (let i = 1; i <= 600; i++) h.p.signal('state', 'n' + i);
  assert.equal(h.p.stats().dropped, 600 - RING_FRAMES);
  h.advance(250);
  h.last.open();
  h.last.ack('s1');
  h.advance(0);
  const names = h.last.frames().filter((f) => f.type === 'signal').map((f) => f.signal.name);
  assert.equal(names.length, RING_FRAMES);
  assert.equal(names[0], 'n89');
  assert.equal(names[names.length - 1], 'n600');
});

test('the ring is bounded by characters as well as frames', () => {
  const h = harness();
  h.p.start(CONFIG);
  h.last.fail();
  const big = 'x'.repeat(60000);
  for (let i = 0; i < 200; i++) h.p.signal('cache', 'c' + i, big);
  h.advance(250);
  h.last.open();
  h.last.ack('s1');
  h.advance(0);
  const frames = h.last.sent.slice(1);
  const kept = frames.length;
  const chars = frames.reduce((n, f) => n + f.length, 0);
  assert.ok(kept < RING_FRAMES, 'the character bound bound first');
  assert.ok(chars <= RING_CHARS, `${chars} chars kept`);
  assert.ok(chars + frames[0].length > RING_CHARS, 'and it kept as many as fit');
  assert.equal(h.p.stats().dropped, 200 - kept);
});

test('one tick sends at most 256 Ki chars of frames', () => {
  const h = connected();
  const big = 'y'.repeat(60000);
  for (let i = 0; i < 10; i++) h.p.signal('cache', 'c' + i, big);
  const ticks = [];
  for (let n = 0; n < 10 && h.timers.length > 0; n++) {
    const before = h.last.sent.length;
    h.tick();
    const sent = h.last.sent.slice(before);
    ticks.push(sent.reduce((c, f) => c + f.length, 0));
  }
  assert.ok(ticks.length > 1, `drained over ${ticks.length} ticks`);
  for (const chars of ticks) assert.ok(chars <= FLUSH_CHARS, `${chars} chars in one tick`);
  assert.equal(h.last.sent.length, 11);
});

// A daemon that is attached but not reading (kill -STOP) leaves the socket open, so without the
// gate every frame moves from the bounded ring into the socket's own buffer, which has no bound and
// counts nothing (KIN-172).
test("the socket's buffer stops at the high-water mark", () => {
  const h = connected({ bufferedAmount: true });
  const sock = h.last;
  const payload = 'z'.repeat(4000);
  const name = (i) => 'n' + String(i).padStart(5, '0'); // one frame length for all of them
  // 2000 signals over 10 s, 20 every 100 ms, so the flush runs between them as it would in an app.
  for (let i = 0; i < 2000; i++) {
    h.p.signal('cache', name(i), payload);
    if (i % 20 === 19) h.advance(100);
  }
  h.advance(10000);
  const signals = () => sock.sent.filter((t) => t.indexOf('"type":"signal"') >= 0);
  const frameLength = signals()[0].length;
  const largest = Math.max(...sock.sent.map((t) => Buffer.byteLength(t)));
  assert.ok(sock.bufferedAmount <= SOCKET_HIGH_WATER + largest, `${sock.bufferedAmount} bytes held by the socket`);
  const dropped = h.p.stats().dropped;
  assert.ok(dropped > 0, 'the frames held back are dropped from the ring, and counted');
  const inRing = 2000 - signals().length - dropped;
  assert.ok(inRing > 0, `${inRing} frames wait in the ring`);
  assert.ok(inRing * frameLength <= RING_CHARS, `${inRing * frameLength} chars in the ring`);
  assert.equal(h.p.stats().state, 'connected');

  // The daemon reads again: sending resumes, and the newest signal arrives.
  for (let n = 0; n < 20 && JSON.parse(sock.sent[sock.sent.length - 1]).signal.name !== name(1999); n++) {
    sock.drain();
    h.advance(1000);
  }
  assert.equal(JSON.parse(sock.sent[sock.sent.length - 1]).signal.name, name(1999));
});

test('an undefined bufferedAmount never holds the queue', () => {
  // React Native 0.79.6's shape: the property is never assigned, so it says nothing either way.
  const h = connected();
  assert.equal(h.last.bufferedAmount, undefined);
  const big = 'y'.repeat(60000);
  for (let i = 0; i < 40; i++) h.p.signal('cache', 'c' + i, big);
  let ticks = 0;
  for (; ticks < 100 && h.timers.length > 0; ticks++) h.tick();
  assert.equal(h.last.sent.length, 41, `every frame went out, over ${ticks} ticks`);
  assert.equal(h.p.stats().dropped, 0);
});

test('a dead or refusing daemon never throws into the app', () => {
  const h = harness();
  h.constructorThrows = true;
  assert.equal(h.p.start(CONFIG), true);
  assert.doesNotThrow(() => h.p.signal('screen', 'Home'));
  assert.equal(h.p.stats().state, 'disconnected');
  assert.equal(h.p.stats().lastError, 'connection refused');
  assert.doesNotThrow(() => h.advance(30000));

  h.constructorThrows = false;
  h.advance(5000);
  const sock = h.last;
  sock.open();
  assert.doesNotThrow(() => sock.reply('not json'));
  assert.doesNotThrow(() => sock.reply('[1,2]'));
  assert.doesNotThrow(() => sock.reply({ type: 'unknown' }));
  sock.ack('s1');
  sock.throwOnSend = true;
  assert.doesNotThrow(() => { h.p.signal('screen', 'Lost'); h.advance(0); });
  assert.equal(h.p.stats().state, 'disconnected');
  assert.doesNotThrow(() => { sock.fail(); h.p.stop(); h.p.stop(); });
});

test('mono, seq and bytes are integers; mono never steps back; seq counts every frame', () => {
  let now = 1000.75;
  const h = harness({ now: () => now });
  h.p.start(CONFIG);
  h.last.open();
  h.last.ack('s1');
  h.p.signal('a', 'one', { k: 1 });
  now = 999.2; // a clock that stepped back
  h.p.signal('a', 'two');
  now = 1003.999;
  h.p.signal('a', 'three', 'é');
  h.advance(0);
  const rows = h.last.frames().filter((f) => f.type === 'signal').map((f) => f.signal);
  assert.deepEqual(rows.map((r) => r.mono), [1000, 1000, 1003]);
  assert.deepEqual(rows.map((r) => r.seq), [1, 2, 3]);
  assert.deepEqual(rows.map((r) => r.bytes), [7, undefined, 4]);
  for (const r of rows) for (const k of ['mono', 'seq', 'bytes']) {
    if (r[k] !== undefined) assert.ok(Number.isInteger(r[k]), `${k} = ${r[k]}`);
  }
  assert.match(h.last.sent[1], /"mono":1000,/);
});

test('signal() before start() is ignored', () => {
  const h = harness();
  assert.doesNotThrow(() => h.p.signal('screen', 'Early'));
  h.p.start(CONFIG);
  h.last.open();
  h.last.ack('s1');
  h.advance(0);
  assert.equal(h.last.sent.length, 1);
});

test('stop() sends bye and closes the socket', () => {
  const h = connected();
  const sock = h.last;
  h.p.stop();
  assert.deepEqual(sock.frames().pop(), { type: 'bye' });
  assert.equal(sock.closed, true);
  assert.equal(h.p.stats().state, 'stopped');
  assert.equal(h.timers.length, 0);
});

test('the log ladder: connected once per session, one line per outage, the first drop, the return', () => {
  const h = connected();
  h.last.fail();
  h.advance(250);
  h.last.fail();
  for (let i = 0; i < RING_FRAMES + 3; i++) h.p.signal('state', 'n' + i);
  h.advance(500);
  h.last.open();
  h.last.ack('s1');
  assert.deepEqual(h.infos(), [
    '[inspector] connected to ws://10.0.2.2:8099/ingest, session s1',
    '[inspector] daemon not reachable at ws://10.0.2.2:8099/ingest; retrying, up to 512 calls are kept',
    '[inspector] the queue is full while the daemon is away; dropping the oldest calls',
    '[inspector] reconnected; 3 calls were dropped while the daemon was away',
  ]);
  assert.deepEqual(h.errors(), [], 'only refusals use console.error');
});
