'use strict';
/*
 * A minimal app-side stream client: what `:inspector-stream` sends, spoken from Node so a check can
 * put real rows into a real daemon. The rows go through `/ingest`, the archive and the `/api/live`
 * broadcast exactly as an app's do, which no stubbed route or hand-fed `onmessage` reaches.
 * The wire is `docs/schema.md` → Wire protocol: a `hello`, the daemon's `helloAck`, and then
 * fire-and-forget `txn`, `signal` and `marker` frames.
 *
 * It refuses any daemon that is not on 127.0.0.1, and any host that does not end in `.invalid`
 * (RFC 2606), so nothing it streams can name or reach a real server, whatever a caller passes.
 */

const LOOPBACK = /^http:\/\/127\.0\.0\.1:\d+\/?$/;
const HOST = 'api.qa.invalid';

function checkHost(host) {
  if (!/\.invalid$/.test(host)) throw new Error(`a streamed host must end in .invalid, not ${host}`);
  return host;
}

/**
 * Opens one app session on the daemon at `origin` and resolves once the daemon has acknowledged
 * it, with the session's id. Each call to `call` or `signal` sends one row and returns its id.
 */
async function openApp(origin, appId) {
  if (!LOOPBACK.test(origin)) throw new Error(`the ingest client talks only to 127.0.0.1, not ${origin}`);
  const ws = new WebSocket(`${origin.replace(/^http/, 'ws').replace(/\/$/, '')}/ingest`);
  await new Promise((resolve, reject) => { ws.onopen = resolve; ws.onerror = () => reject(new Error(`${origin}/ingest refused the socket`)); });
  const sessionId = await new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error(`${origin}/ingest sent no helloAck`)), 10000);
    ws.onmessage = (event) => {
      const message = JSON.parse(event.data);
      if (message.type === 'helloAck') { clearTimeout(timer); resolve(message.sessionId); }
    };
    ws.send(JSON.stringify({ type: 'hello', client: {
      v: 1, appId, appVersion: '1.0', platform: 'desktop', device: 'measure', osVersion: '1', buildType: 'debug',
    } }));
  });
  const started = Date.now();
  // `mono` is the session's clock and must only grow; two rows sent in one millisecond would tie.
  let mono = 0;
  let seq = 0;
  const nextMono = () => { mono = Math.max(mono + 1, Date.now() - started); return mono; };
  return {
    sessionId,
    /** One GET to `path` answering `status`; `host` must end in `.invalid`. */
    call(path, status, { host = HOST, query } = {}) {
      const id = `${appId}-c${++seq}`;
      ws.send(JSON.stringify({ type: 'txn', txn: {
        v: 1, id, ts: new Date().toISOString(), mono: nextMono(), method: 'GET', scheme: 'https',
        host: checkHost(host), path, query, status, ms: 10, callId: `call-${id}`, resContentType: 'application/json',
      }, resBody: '{"ok":true}' }));
      return id;
    },
    /** One signal of `tag`, its payload `data` riding inline as the wire carries it. */
    signal(tag, name, data = '{}') {
      const id = `${appId}-s${++seq}`;
      ws.send(JSON.stringify({ type: 'signal', signal: { v: 1, id, ts: new Date().toISOString(), mono: nextMono(), tag, name }, data }));
      return id;
    },
    /** One marker from the app, as `MarkerMsg` carries it; markers have no id, so the label is returned. */
    marker(label) {
      ws.send(JSON.stringify({ type: 'marker', marker: { v: 1, ts: new Date().toISOString(), mono: nextMono(), label, source: 'app' } }));
      return label;
    },
    close() { ws.close(); },
  };
}

module.exports = { openApp };
