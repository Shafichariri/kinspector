'use strict';
// A scripted WebSocket, manual timers and clocks for driving inspector-producer.js without a
// daemon. Nothing here opens a socket or sleeps: a test advances time and fires socket events itself.

const producer = require('../inspector-producer.js');

function harness(options) {
  const opts = options || {};
  const sockets = [];
  const logs = [];
  const timers = [];
  let clock = 0;
  let nextTimer = 1;
  let ids = 0;

  class FakeWebSocket {
    constructor(url) {
      if (h.constructorThrows) throw new Error('connection refused');
      this.url = url;
      this.sent = [];
      this.closed = false;
      this.throwOnSend = false;
      // Left undefined unless asked for, as React Native 0.79.6 leaves it; Node's counts bytes.
      if (opts.bufferedAmount) this.bufferedAmount = 0;
      sockets.push(this);
    }
    send(text) {
      if (this.throwOnSend) throw new Error('socket is gone');
      this.sent.push(text);
      if (opts.bufferedAmount) this.bufferedAmount += Buffer.byteLength(text);
    }
    // The daemon reads everything the socket held.
    drain() { this.bufferedAmount = 0; }
    close() { this.closed = true; }
    // Daemon-side helpers.
    open() { this.onopen && this.onopen({}); }
    reply(msg) { this.onmessage && this.onmessage({ data: typeof msg === 'string' ? msg : JSON.stringify(msg) }); }
    ack(sessionId) { this.reply({ type: 'helloAck', sessionId: sessionId || 's1' }); }
    fail() { this.onerror && this.onerror({ message: 'ECONNREFUSED' }); this.onclose && this.onclose({ code: 1006 }); }
    frames() { return this.sent.map((t) => JSON.parse(t)); }
  }

  const h = {
    sockets,
    logs,
    timers,
    constructorThrows: false,
    now: opts.now || (() => clock),
    get last() { return sockets[sockets.length - 1]; },
    setClock(ms) { clock = ms; },
    // Runs every timer due within ms, in time order, including ones they schedule.
    advance(ms) {
      const until = clock + ms;
      for (;;) {
        timers.sort((a, b) => a.at - b.at || a.id - b.id);
        const t = timers[0];
        if (!t || t.at > until) break;
        timers.shift();
        clock = Math.max(clock, t.at);
        t.fn();
      }
      clock = until;
    },
    // One macrotask: the timers due now, but not the ones they schedule.
    tick() {
      const due = timers.filter((t) => t.at <= clock).sort((a, b) => a.id - b.id);
      for (const t of due) {
        timers.splice(timers.indexOf(t), 1);
        t.fn();
      }
    },
    errors() { return logs.filter((l) => l.level === 'error').map((l) => l.line); },
    infos() { return logs.filter((l) => l.level === 'info').map((l) => l.line); },
  };

  const deps = {
    WebSocket: 'WebSocket' in opts ? opts.WebSocket : FakeWebSocket,
    XMLHttpRequest: 'XMLHttpRequest' in opts ? opts.XMLHttpRequest : function FakeXhr() {},
    now: () => h.now(),
    wallNow: () => Date.UTC(2026, 9, 30, 12, 0, 0, 0) + clock,
    randomId: () => (ids++).toString(16).padStart(8, '0'),
    setTimeout: (fn, ms) => {
      const t = { id: nextTimer++, at: clock + (ms || 0), delay: ms || 0, fn };
      timers.push(t);
      return t.id;
    },
    clearTimeout: (id) => {
      const i = timers.findIndex((t) => t.id === id);
      if (i >= 0) timers.splice(i, 1);
    },
    console: {
      info: (line) => logs.push({ level: 'info', line }),
      warn: (line) => logs.push({ level: 'warn', line }),
      error: (line) => logs.push({ level: 'error', line }),
      log: (line) => logs.push({ level: 'log', line }),
    },
  };

  h.deps = deps;
  h.p = producer._test.createProducer(deps);
  return h;
}

const CONFIG = Object.freeze({ appId: 'dev.example.app', appVersion: '1.0', platform: 'android-emulator' });

// Started, opened and acknowledged.
function connected(options) {
  const h = harness(options);
  h.p.start(CONFIG);
  h.last.open();
  h.last.ack('s1');
  return h;
}

module.exports = { harness, connected, CONFIG, producer };
