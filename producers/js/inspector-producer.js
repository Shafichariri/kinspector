'use strict';
// Inspector's JavaScript producer: sends a React Native app's traffic and signals to the daemon's
// `WS /ingest` as the same WireMsg JSON the Kotlin StreamSink sends (docs/schema.md).
//
// One file, CommonJS, no dependencies, so a harness can vendor it. It never requires
// `react-native`: the app hands it in as `reactNative` when the producer needs to read it, which
// keeps this file loadable in Node, where the tests run.
//
// The efficiency contract, for one thread: the app's call takes a timestamp, builds one frame and
// pushes it on a bounded ring. Sending happens later, on a timer, at most FLUSH_CHARS per tick.
// A dead, slow or absent daemon costs dropped frames, never an exception or a wait in the app.

const VERSION = '0.1.0';

// The release-build canary. It is assigned to a global at module top level because a constant
// nothing reads is removed from Hermes bytecode, and a used one survives (ROADMAP §7, measurement
// 3). A consumer's release CI greps its bundle for this prefix; see the guide.
const CANARY = 'INSPECTOR_JS_CANARY_de7746a52fc1d9a4';
globalThis.__INSPECTOR_PRODUCER__ = { canary: CANARY, version: VERSION };

const SCHEMA_VERSION = 1;
// 512 frames as Kotlin's channel, and a character bound as well: one Kotlin row is bounded by its
// body cap, but a JS string is 2 bytes a char, so 8 Mi chars is about 16 MB at worst.
const RING_FRAMES = 512;
const RING_CHARS = 8 * 1024 * 1024;
const FLUSH_CHARS = 256 * 1024;
// Bytes the socket may hold unsent. Past it, frames wait in the ring, which is bounded and counts
// what it drops; a socket to a daemon that is attached but not reading would otherwise be a second
// queue with neither (KIN-172).
const SOCKET_HIGH_WATER = 1024 * 1024;
const MIN_BACKOFF_MS = 250; // StreamSink.kt's MIN_BACKOFF_MS and MAX_BACKOFF_MS
const MAX_BACKOFF_MS = 5000;
const SIGNAL_PAYLOAD_CAP = 65536; // SignalPolicy.maxPayloadBytes; held to it by fixtures/defaults.json
const ALLOWED_HOSTS = ['127.0.0.1', 'localhost', '::1', '[::1]', '10.0.2.2'];

const SIGN_ERROR = "this app's JavaScript producer cannot regenerate per-request headers. " +
  'Untick "regenerate per-request headers (re-sign)" and replay without re-signing.';

// ---- UTF-8 and base64 --------------------------------------------------------------------------
// Hermes has TextEncoder and no TextDecoder (measurement 2), so encoding uses the former and the
// rest is by hand. Node's TextEncoder, TextDecoder and Buffer are the test oracles.

function isHigh(c) { return c >= 0xd800 && c <= 0xdbff; }
function isLow(c) { return c >= 0xdc00 && c <= 0xdfff; }

// UTF-8 bytes of str[from, to), allocating nothing, so a long body can be counted in chunks
// between macrotasks. A pair is counted at its high half wherever the chunk boundary falls, and a
// lone surrogate as 3 bytes, which is what TextEncoder writes for it (U+FFFD).
function utf8Length(str, from, to) {
  const end = to === undefined ? str.length : to;
  let n = 0;
  for (let i = from || 0; i < end; i++) {
    const c = str.charCodeAt(i);
    if (c < 0x80) n += 1;
    else if (c < 0x800) n += 2;
    else if (isHigh(c)) n += isLow(str.charCodeAt(i + 1)) ? 4 : 3;
    else if (isLow(c)) n += i > 0 && isHigh(str.charCodeAt(i - 1)) ? 0 : 3;
    else n += 3;
  }
  return n;
}

// The first maxBytes bytes of str's UTF-8 encoding. Only a prefix of the string is encoded, since
// every char is at least one byte; it is widened by one when it would end inside a pair, or the
// last bytes would be U+FFFD rather than the start of the real character.
function utf8Prefix(str, maxBytes) {
  let end = Math.min(str.length, maxBytes);
  if (end < str.length && isHigh(str.charCodeAt(end - 1))) end++;
  const bytes = new TextEncoder().encode(str.slice(0, end));
  return bytes.length > maxBytes ? bytes.subarray(0, maxBytes) : bytes;
}

// Strict UTF-8 decode: the string, or null when the bytes are not valid UTF-8 (a cut that split a
// character, an overlong form, a surrogate). Null is what sends a payload as base64 instead.
function utf8Decode(bytes) {
  const units = [];
  let out = '';
  for (let i = 0; i < bytes.length;) {
    const b = bytes[i];
    let cp, need, min;
    if (b < 0x80) { cp = b; need = 0; min = 0; }
    else if (b >= 0xc2 && b <= 0xdf) { cp = b & 0x1f; need = 1; min = 0x80; }
    else if (b >= 0xe0 && b <= 0xef) { cp = b & 0x0f; need = 2; min = 0x800; }
    else if (b >= 0xf0 && b <= 0xf4) { cp = b & 0x07; need = 3; min = 0x10000; }
    else return null;
    if (i + need >= bytes.length) return null; // the sequence runs past the end: a cut character
    for (let k = 1; k <= need; k++) {
      const cont = bytes[i + k];
      if ((cont & 0xc0) !== 0x80) return null;
      cp = (cp << 6) | (cont & 0x3f);
    }
    if (cp < min || cp > 0x10ffff || (cp >= 0xd800 && cp <= 0xdfff)) return null;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      units.push(0xd800 + (cp >> 10), 0xdc00 + (cp & 0x3ff));
    } else units.push(cp);
    i += need + 1;
    // fromCharCode.apply takes every unit as an argument, so flush in bounded chunks.
    if (units.length >= 8192) { out += String.fromCharCode.apply(null, units); units.length = 0; }
  }
  return out + String.fromCharCode.apply(null, units);
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

// Standard alphabet with padding, as the Kotlin sink's base64Encode. A missing byte reads as 0
// (`undefined | 0`) and its sextets become '='.
function base64(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length; i += 3) {
    const left = bytes.length - i;
    const n = (bytes[i] << 16) | ((bytes[i + 1] | 0) << 8) | (bytes[i + 2] | 0);
    out += B64[(n >> 18) & 63] + B64[(n >> 12) & 63] +
      (left > 1 ? B64[(n >> 6) & 63] : '=') + (left > 2 ? B64[n & 63] : '=');
  }
  return out;
}

// ---- Android emulator detection ----------------------------------------------------------------
// EmulatorDetection.kt's rule, minus what React Native cannot see. Platform.constants carries
// Fingerprint, Model, Brand and Manufacturer (AndroidInfoModule.kt:55-58, RN 0.79.6) and no
// HARDWARE, BOARD, DEVICE or PRODUCT, so the product and device are read out of the fingerprint,
// brand/product/device:release/..., and the Kotlin checks run over the five fields that gives.
// Kotlin's sturdiest rule, the hardware, is out of reach: an image only it catches reads as a
// device here. fixtures/emulator-profiles.json holds both runners to the same profiles.
function lower(v) { return v == null ? '' : String(v).toLowerCase(); }
function startsWithAny(v, prefixes) { return prefixes.some((p) => v.startsWith(p)); }

function looksLikeEmulator(constants) {
  const fingerprint = lower(constants.Fingerprint);
  const [, product = '', device = ''] = fingerprint.split(':')[0].split('/');
  const model = lower(constants.Model);
  return startsWithAny(device, ['emu64', 'generic', 'vsoc_', 'vbox86']) ||
    startsWithAny(product, ['sdk', 'google_sdk', 'aosp_cf', 'vbox86', 'emulator']) ||
    startsWithAny(lower(constants.Brand), ['generic']) ||
    startsWithAny(fingerprint, ['generic', 'unknown']) ||
    ['emulator', 'android sdk built for', 'sdk_gphone'].some((s) => model.indexOf(s) >= 0);
}

function randomId() {
  return ((Math.random() * 0x100000000) >>> 0).toString(16).padStart(8, '0');
}

// ---- The producer ------------------------------------------------------------------------------

// Everything the producer touches outside itself comes through deps, so the tests can drive the
// socket, the clocks and the timers. start() below builds it from the globals.
function createProducer(deps) {
  const d = deps;
  let started = false;
  let client = null;
  let url = null;
  let ws = null;
  let acked = false;
  let state = 'stopped';
  let sessionId = null;
  // In memory only, never persisted: a full JS reload restarts `mono`, so a reloaded bundle
  // resuming the old session would put rows before rows they came after.
  let resumeSessionId = null;
  let lastError = null;
  let backoffMs = MIN_BACKOFF_MS;
  let reconnectTimer = null;
  let flushTimer = null;
  // The ring: frames already encoded, so their length is known when they are bounded.
  let ring = [];
  let ringChars = 0;
  let dropped = 0;
  let lastMono = 0;
  let seq = 0;
  // The log ladder: each line once per session or per outage, never per retry.
  let loggedSession = null;
  let everConnected = false;
  let outage = false;
  let outageDrops = 0;
  let dropLogged = false;

  function log(level, line) {
    try { d.console[level]('[inspector] ' + line); } catch (_) { /* a missing console is not the app's problem */ }
  }

  function refuse(line) { log('error', 'not started: ' + line); return false; }

  // `mono` then `seq`, in that order and nowhere else, so on one thread `seq` is program order.
  // performance.now is fractional on a device (measurement 6) and the daemon decodes a Long, so a
  // fractional value would fail the whole frame there: floor it, and never let it step back.
  function stamp() {
    let mono = Math.floor(d.now());
    if (!(mono >= lastMono)) mono = lastMono;
    lastMono = mono;
    seq += 1;
    return { mono, seq };
  }

  function drop() {
    dropped += 1;
    if (outage) {
      outageDrops += 1;
      if (!dropLogged) {
        dropLogged = true;
        log('info', 'the queue is full while the daemon is away; dropping the oldest calls');
      }
    }
  }

  function enqueue(frame) {
    ring.push(frame);
    ringChars += frame.length;
    while (ring.length > RING_FRAMES || ringChars > RING_CHARS) {
      ringChars -= ring.shift().length;
      drop();
    }
    if (acked) scheduleFlush();
  }

  function scheduleFlush() {
    if (flushTimer === null) flushTimer = d.setTimeout(flush, 0);
  }

  // One frame per send, and a tick sends at most FLUSH_CHARS of them, so a long queue after a
  // reconnect drains over several macrotasks rather than in one block on the app's thread. A frame
  // larger than the budget still goes, alone, or it would never leave.
  function flush() {
    flushTimer = null;
    let sent = 0;
    while (acked && ws && ring.length > 0) {
      // Checked before every frame, not once a tick: Node's bufferedAmount rises during send itself.
      if (backedUp(ws)) { flushTimer = d.setTimeout(flush, MIN_BACKOFF_MS); return; }
      const next = ring[0];
      if (sent > 0 && sent + next.length > FLUSH_CHARS) break;
      ring.shift();
      ringChars -= next.length;
      sent += next.length;
      // A frame handed to a socket that then dies is lost and not counted, as in Kotlin.
      if (!sendNow(ws, next)) return;
    }
    if (acked && ring.length > 0) scheduleFlush();
  }

  function backedUp(sock) {
    const b = sock.bufferedAmount;
    // React Native 0.79.6 declares bufferedAmount and never assigns it (WebSocket_old.js:92,
    // WebSocket_new.js:92), so undefined is "no information", never "backed up".
    return typeof b === 'number' && b > SOCKET_HIGH_WATER;
  }

  function sendNow(sock, text) {
    try {
      sock.send(text);
      return true;
    } catch (e) {
      lost(sock, describe(e, 'send failed'));
      return false;
    }
  }

  function describe(e, fallback) {
    return e && e.message ? String(e.message) : fallback;
  }

  function hello() {
    const msg = { type: 'hello', client };
    if (resumeSessionId !== null) msg.resumeSessionId = resumeSessionId;
    return JSON.stringify(msg);
  }

  function connect() {
    reconnectTimer = null;
    if (!started) return;
    state = 'connecting';
    acked = false;
    let sock;
    try {
      sock = new d.WebSocket(url);
    } catch (e) {
      lost(null, describe(e, 'cannot open a WebSocket'));
      return;
    }
    ws = sock;
    // Every handler is fenced: whatever the daemon or the socket does, nothing reaches the app.
    sock.onopen = () => { try { if (ws === sock) sendNow(sock, hello()); } catch (_) { /* fenced */ } };
    sock.onmessage = (event) => { try { if (ws === sock) receive(sock, event && event.data); } catch (_) { /* fenced */ } };
    sock.onerror = (event) => { try { lost(sock, describe(event, 'WebSocket error')); } catch (_) { /* fenced */ } };
    sock.onclose = (event) => {
      try { lost(sock, 'closed' + (event && event.code ? ' (' + event.code + ')' : '')); } catch (_) { /* fenced */ }
    };
  }

  // A socket error and its close both arrive; only the first one for the current socket counts.
  function lost(sock, reason) {
    if (sock !== null && ws !== sock) return;
    ws = null;
    acked = false;
    if (sock !== null) { try { sock.close(); } catch (_) { /* already gone */ } }
    if (!started) return;
    state = 'disconnected';
    lastError = reason;
    if (!outage) {
      outage = true;
      outageDrops = 0;
      dropLogged = false;
      log('info', `daemon not reachable at ${url}; retrying, up to ${RING_FRAMES} calls are kept`);
    }
    reconnectTimer = d.setTimeout(connect, backoffMs);
    backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
  }

  function receive(sock, text) {
    let msg;
    try { msg = JSON.parse(text); } catch (_) { return; }
    if (!msg || typeof msg !== 'object') return;
    if (msg.type === 'helloAck') {
      if (acked) return;
      acked = true;
      state = 'connected';
      lastError = null;
      sessionId = resumeSessionId = String(msg.sessionId);
      // Reset on the ack, not on a clean close as Kotlin does: an ack is the one proof the daemon
      // is really there, so a daemon that accepts sockets and drops them keeps backing off.
      backoffMs = MIN_BACKOFF_MS;
      if (sessionId !== loggedSession) {
        loggedSession = sessionId;
        log('info', `connected to ${url}, session ${sessionId}`);
      }
      if (outage && everConnected) {
        log('info', `reconnected; ${outageDrops} calls were dropped while the daemon was away`);
      }
      everConnected = true;
      outage = false;
      scheduleFlush();
    } else if (msg.type === 'signReq') {
      // Answered at once and outside the ring: the host is waiting, and a full queue must not
      // delay or drop the reply. There is no signing hook in JS, so the answer names the fix.
      sendNow(sock, JSON.stringify({ type: 'signRes', requestId: String(msg.requestId), error: SIGN_ERROR }));
    } else if (msg.type === 'signalReq') {
      sendNow(sock, JSON.stringify({
        type: 'signalErr',
        requestId: String(msg.requestId),
        error: `no provider for ${msg.tag}/${msg.name}; registered: none`,
      }));
    }
  }

  function platformFrom(config) {
    if (config.platform != null) return String(config.platform);
    const rn = config.reactNative;
    const os = rn && rn.Platform ? rn.Platform.OS : undefined;
    if (os === 'ios') {
      return refuse("on iOS pass platform: 'ios-simulator' to start(). It cannot be detected without a native module.");
    }
    // The platform decides the default host, so this runs before the host is chosen.
    if (os === 'android') return looksLikeEmulator(rn.Platform.constants || {}) ? 'android-emulator' : 'android-device';
    return refuse("pass reactNative: require('react-native') or platform");
  }

  function start(config) {
    try {
      if (started) return true;
      const c = config && typeof config === 'object' ? config : {};
      const platform = platformFrom(c);
      if (!platform) return false;
      const host = c.host == null ? (platform === 'android-emulator' ? '10.0.2.2' : '127.0.0.1') : String(c.host);
      if (ALLOWED_HOSTS.indexOf(host) < 0) {
        return refuse(`host "${host}" is not allowed. Use 127.0.0.1, localhost, ::1 or 10.0.2.2.`);
      }
      const port = c.port == null ? 8099 : Number(c.port);
      if (!Number.isInteger(port) || port < 1 || port > 65535) return refuse(`port "${c.port}" is not a TCP port`);
      if (typeof c.appId !== 'string' || typeof c.appVersion !== 'string') {
        return refuse('pass appId and appVersion as strings');
      }
      if (typeof d.XMLHttpRequest !== 'function') return refuse('XMLHttpRequest is not available here');
      if (typeof d.WebSocket !== 'function') return refuse('WebSocket is not available here');

      client = {
        v: SCHEMA_VERSION,
        appId: c.appId,
        appVersion: c.appVersion,
        platform,
        device: c.device == null ? 'unknown' : String(c.device),
        osVersion: c.osVersion == null ? 'unknown' : String(c.osVersion),
        buildType: c.buildType == null ? 'debug' : String(c.buildType),
      };
      url = `ws://${host.indexOf(':') >= 0 && host[0] !== '[' ? '[' + host + ']' : host}:${port}/ingest`;
      started = true;
      connect();
      return true;
    } catch (_) {
      return false;
    }
  }

  // Kotlin's encoding: a string is JSON-quoted, anything else is JSON, and what JSON cannot take
  // is its String() form, quoted. Undefined means no payload at all.
  function encodePayload(data) {
    if (data === undefined) return undefined;
    let encoded;
    try { encoded = JSON.stringify(data); } catch (_) { encoded = undefined; }
    return encoded === undefined ? JSON.stringify(String(data)) : encoded;
  }

  function signal(tag, name, data) {
    try {
      if (!started) return;
      const at = stamp();
      const row = {
        v: SCHEMA_VERSION,
        id: d.randomId(),
        ts: new Date(d.wallNow()).toISOString(),
        mono: at.mono,
        tag: String(tag),
        name: String(name),
      };
      const encoded = encodePayload(data);
      let payload;
      let b64 = false;
      if (encoded !== undefined) {
        const bytes = utf8Length(encoded);
        if (bytes > SIGNAL_PAYLOAD_CAP) {
          // The wire carries the prefix bytes, as StreamSink does: text when they are still
          // UTF-8, base64 when the cut split a character. The JSON-string form Recorder keeps
          // is the in-memory row only.
          const prefix = utf8Prefix(encoded, SIGNAL_PAYLOAD_CAP);
          payload = utf8Decode(prefix);
          if (payload === null) { payload = base64(prefix); b64 = true; }
          row.dataTruncated = true;
        } else payload = encoded;
        row.bytes = bytes;
      }
      row.seq = at.seq;
      const msg = { type: 'signal', signal: row };
      if (payload !== undefined) msg.data = payload;
      if (b64) msg.dataB64 = true;
      enqueue(JSON.stringify(msg));
    } catch (_) { /* never into the app */ }
  }

  function stats() {
    return { state, url, sessionId, dropped, lastError };
  }

  function stop() {
    try {
      if (!started) return;
      started = false;
      const sock = ws;
      ws = null;
      if (sock !== null) {
        if (acked) { try { sock.send(JSON.stringify({ type: 'bye' })); } catch (_) { /* gone */ } }
        try { sock.close(); } catch (_) { /* gone */ }
      }
      if (reconnectTimer !== null) d.clearTimeout(reconnectTimer);
      if (flushTimer !== null) d.clearTimeout(flushTimer);
      reconnectTimer = flushTimer = null;
      acked = false;
      state = 'stopped';
      sessionId = resumeSessionId = loggedSession = lastError = null;
      ring = [];
      ringChars = 0;
      backoffMs = MIN_BACKOFF_MS;
      everConnected = outage = dropLogged = false;
    } catch (_) { /* never into the app */ }
  }

  return { start, signal, stats, stop };
}

function globalDeps() {
  const g = globalThis;
  const perf = g.performance && typeof g.performance.now === 'function' ? g.performance : null;
  return {
    WebSocket: g.WebSocket,
    XMLHttpRequest: g.XMLHttpRequest,
    now: perf ? () => perf.now() : () => Date.now(),
    wallNow: () => Date.now(),
    randomId,
    setTimeout: (fn, ms) => g.setTimeout(fn, ms),
    clearTimeout: (t) => g.clearTimeout(t),
    console: g.console,
  };
}

// The globals are read at the first start(), not at load, so a harness may load this file before
// the runtime has installed them.
let instance = null;

module.exports = {
  start(config) {
    try {
      if (instance === null) instance = createProducer(globalDeps());
      return instance.start(config);
    } catch (_) {
      return false;
    }
  },
  signal(tag, name, data) { if (instance !== null) instance.signal(tag, name, data); },
  stats() {
    return instance !== null ? instance.stats() : { state: 'stopped', url: null, sessionId: null, dropped: 0, lastError: null };
  },
  stop() { if (instance !== null) instance.stop(); },
  // For the tests in producers/js/test; not part of the consumer surface.
  _test: {
    createProducer, utf8Length, utf8Prefix, utf8Decode, base64, CANARY,
    RING_FRAMES, RING_CHARS, FLUSH_CHARS, SOCKET_HIGH_WATER, SIGNAL_PAYLOAD_CAP,
  },
};
