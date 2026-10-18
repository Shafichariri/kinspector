/* core: the archive, prefs, state, DOM helpers, formatters and ages */
'use strict';

/* Inspector web UI.
 *
 * Deliberately dependency-free: no framework, no build step, no network beyond this daemon.
 * The daemon is a local debugging tool holding unredacted credentials — it has no business
 * loading anything from a CDN.
 */

/**
 * A whole session embedded by the daemon's HTML export, or null on the live page.
 *
 * When it is set this page is a file on disk with no daemon behind it: every request is answered
 * from it (`archiveSource`), and the controls that would have to send something are hidden
 * (`enterArchiveMode`). Everything else is the same code drawing the same rows — that is the
 * reason the export is this page and not a report written for the purpose.
 */
const ARCHIVE = window.INSPECTOR_ARCHIVE || null;

/**
 * `localStorage`, unable to take the page down.
 *
 * Storage can throw on access — an opaque origin, a file opened with site data blocked, a
 * private window — and three reads below run before anything renders. One `SecurityError` there
 * and the page draws nothing at all. These are reading preferences; losing one is a default.
 */
const prefs = {
  get(key) { try { return localStorage.getItem(key); } catch { return null; } },
  set(key, value) { try { localStorage.setItem(key, value); } catch { /* a preference, not data */ } },
};

const state = {
  sessionId: null,
  sessions: [],
  transactions: [],
  markers: [],
  selectedId: null,
  filter: '',
  liveTail: true,
  activeSessionId: null,
  // 'running' | 'restarting' | 'stopped' — decides whether a dropped socket is a problem to
  // reconnect from or the outcome the user asked for.
  serverState: 'running',
  // The newest capture this page has seen arrive, from any session: what the stopped banner
  // means by "last capture". Null until the live socket delivers a row.
  lastCaptureTs: null,
  // false = oldest first (causal reading order), true = newest first (tail-a-log order).
  // Remembered across reloads because it is a reading preference, not session state.
  newestFirst: prefs.get('inspector.newestFirst') === '1',
  // Window for duplicate detection, in ms. 0 disables it.
  duplicateWindowMs: Number(prefs.get('inspector.duplicateWindowMs') ?? 3000),
  // id -> group ordinal, recomputed whenever the transaction list changes.
  duplicates: new Map(),
  // Every row in the session, ignoring the filter, plus the session it belongs to.
  //
  // `transactions` is the *filtered* set — the daemon applies the filter — and two features
  // need the unfiltered one. Endpoint chips built from the filtered view would collapse to the
  // single chip you just clicked, and a duplicate whose twin is filtered out is still a
  // duplicate; making the highlight depend on the filter would hide exactly the case you go
  // looking for.
  allTransactions: [],
  allSessionId: null,
  // How many list loads and signal loads have started. Each load keeps its own number and drops
  // its answer once a newer one has started, because the daemon may answer them out of order.
  callLoad: 0,
  signalLoad: 0,
  // How many endpoint chips to show. 0 hides them.
  endpointLimit: Number(prefs.get('inspector.endpointChipLimit') ?? 10),
  // App-state observations for this session as the daemon matched them against the filter, and
  // the latest per (tag, name). Only the timeline tab's entries and the path view's visit lines
  // read `signals`; they are the two places a filter is meant to thin signals.
  signals: [],
  current: [],
  // Every signal in the session, ignoring the filter, and the session it belongs to — the same
  // split as `allTransactions`, for the same reason: tabs, their counts, the tag browsers, the
  // glance and the axis describe the session, and a filter that emptied a tab would make the tab
  // claim the app recorded nothing.
  allSignals: [],
  allSignalsSessionId: null,
  // Every `screen` signal in the session, ignoring the filter: the skeleton `visits()` is built
  // from. Taken from `allSignals`; a transaction term such as `path:` drops every signal, screens
  // included, so a route drawn from the filtered list vanished the moment a filter was typed.
  screens: [],
  // Screen payloads as the text that was captured, by signal id, for the path view's "same
  // payload" note on a repeated screen. Text rather than parsed: comparing and summarising the
  // bytes needs no `JSON.parse`, and that is the parse this page avoids.
  screenPayloadText: new Map(),
  // Path view: visits the user has unfolded, how many visits the last render drew, and for which
  // session — so a new visit arriving while you read an earlier one can say so instead of
  // yanking the view to it.
  expandedVisits: new Set(),
  pathVisitCount: null,
  pathSessionId: null,
  // The visit last opened from the map, and whether the next render is the first since the tab
  // opened — which lands on the newest visit rather than wherever the shared pane was scrolled.
  pathFocus: null,
  pathFresh: true,
  // Signal payloads, keyed by signal id. Fetched lazily: the signal list deliberately omits
  // them — a session can hold thousands — but every field a browser row shows lives in one.
  payloads: new Map(),
  // 'all' (merged timeline) | 'network' (traffic) | a tag name such as 'cache'.
  //
  // Chosen per session rather than remembered: a session with no signals has nothing to merge
  // and no tags to tab through, so landing on an empty view would be worse than useless.
  // Sessions recorded before signals existed therefore behave exactly as they always did.
  view: 'network',
  selectedSignalId: null,
  // Tag-browser state. Facets are per-tag and rebuilt on every switch, because the words in a
  // payload are the app's — one app's `storage` values mean nothing to the next.
  browserFilter: '',
  browserFacets: {},
  browserKey: null,
  browserObservation: null,
  // How many rows the daemon's text filter matched, before the axis narrows it further.
  matchTotal: null,
  // Which face of the transaction detail is showing. Remembered across selections: comparing
  // request bodies down a list means picking the same tab on every row otherwise.
  detailTab: 'res',
  // Collapsed timeline runs the user has opened, by run key. Held in state rather than in the
  // DOM so an expanded run survives the re-render that every live signal triggers.
  expandedRuns: new Set(),
  // JSON tree view state by body — `txn:<id>:<side>` or `sig:<id>` — so folding and hidden
  // fields survive the detail pane being rebuilt and coming back to the same row. Memory only;
  // a reload starting fresh is fine.
  jsonViews: new Map(),
  // The host and leading path segments most of this session shares, or null when lifting one
  // would not pay for itself. Recomputed from `allTransactions`, never from the filtered view —
  // a prefix that changed as you typed would make each row mean something different mid-search.
  scope: null,
  // Session ids an app is connected to right now, from `/api/recording`. Not derivable from
  // `endedAt`: a session whose daemon was killed has neither an `endedAt` nor an open app.
  recording: [],
  // A stretch of the session selected on the axis, in device `mono` ms, or null for all of it.
  // Client-side on purpose: the text filter round-trips to the daemon, and a brush you drag
  // across a chart cannot wait for a fetch per pixel.
  timeRange: null,
};

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => {
  const node = document.createElement(tag);
  if (cls) node.className = cls;
  if (text !== undefined) node.textContent = text;
  return node;
};

// --- formatting ---------------------------------------------------------

const statusClass = (s) =>
  s === null || s === undefined ? 'x' : s >= 500 ? '5' : s >= 400 ? '4' : s >= 300 ? '3' : '2';

const KNOWN_METHODS = ['get', 'post', 'put', 'patch', 'delete'];
/** Anything unrecognised (HEAD, OPTIONS, a custom verb) stays muted rather than borrowing a
 *  colour that means something else. */
const methodClass = (m) => {
  const lower = String(m || '').toLowerCase();
  return KNOWN_METHODS.includes(lower) ? `m-${lower}` : 'm-other';
};

const fmtMs = (ms) =>
  ms === null || ms === undefined ? '—' : ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;

const fmtBytes = (n) => {
  if (!n) return '—';
  if (n < 1024) return `${n} B`;
  if (n < 1048576) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1048576).toFixed(1)} MB`;
};

/**
 * How long ago something happened, from the device's own wall clock.
 *
 * `mono` cannot answer this. It is the *device's* monotonic clock, with no relationship to this
 * machine's, so subtracting it from `Date.now()` is meaningless — which is why ages used to be
 * measured against the newest observation in the session instead. That has a defect you only
 * see by watching it: there is no clock in it, so it cannot tick, a refresh never moves it, and
 * a pull moves every other row at once because it shifts the reference point.
 *
 * `ts` is a real timestamp, so this is answerable. It is still the device's clock: a simulator
 * whose clock has drifted reports the drift, and an age that comes out negative is clamped
 * rather than rendered as the future.
 */
const ageOf = (ts) => {
  const at = Date.parse(ts);
  if (!Number.isFinite(at)) return null;
  return Math.max(0, Date.now() - at);
};

const fmtAge = (ms) => {
  if (ms === null || ms === undefined) return '—';
  if (ms < 2000) return 'just now';
  if (ms < 60000) return `${Math.round(ms / 1000)}s ago`;
  if (ms < 3600000) return `${Math.round(ms / 60000)}m ago`;
  if (ms < 86400000) return `${Math.round(ms / 3600000)}h ago`;
  return `${Math.round(ms / 86400000)}d ago`;
};

/**
 * An age that keeps itself current.
 *
 * The timestamp travels on the node, so [paintAges] can refresh every age on the page without
 * re-rendering anything around them.
 */
function ageNode(ts, cls) {
  const node = el('span', cls || 'age');
  node.dataset.ageTs = ts;
  node.title = "measured against this machine's clock, using the device's own timestamp";
  paintAge(node);
  return node;
}

function paintAge(node) {
  const age = ageOf(node.dataset.ageTs);
  node.textContent = fmtAge(age);
  // Only while an app is attached. In a session that ended hours ago *everything* is old, so
  // colouring every row would mark the whole panel stale and say nothing — the warning has to
  // mean "this has stopped updating while you watch", which is only a claim a live session can
  // make.
  node.classList.toggle('stale', sessionIsLive() && age !== null && age > 60000);
}

const paintAges = () => document.querySelectorAll('[data-age-ts]').forEach(paintAge);

/** True while the app is still writing to the session on screen: `endedAt` is set on close. */
const sessionIsLive = () => {
  const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
  return Boolean(meta) && !meta.endedAt;
};

const prettyJson = (text) => {
  try {
    return printJsonTree(parseJsonTree(text));
  } catch {
    return text; // truncated or malformed bodies are exactly the ones worth seeing raw
  }
};

// @mirror-begin urlOf
// Mirrors NetworkTransaction.url on the Kotlin side. Built here rather than taken from the row
// because `url` is a computed property and does not travel on the wire. An empty query keeps its
// `?`, as Kotlin's `query?.let` does: `/a?` and `/a` are different URLs, and the duplicate key is
// built from this.
const urlOf = (txn) => {
  const dflt = { http: 80, ws: 80, https: 443, wss: 443 }[String(txn.scheme).toLowerCase()];
  const port = txn.port != null && txn.port !== dflt ? `:${txn.port}` : '';
  return `${txn.scheme}://${txn.host}${port}${txn.path}${txn.query != null ? '?' + txn.query : ''}`;
};
// @mirror-end urlOf

const curlFor = (txn, reqBody) => {
  let out = `curl -X ${txn.method} '${urlOf(txn)}'`;
  for (const [name, values] of Object.entries(txn.reqHeaders || {})) {
    for (const v of values) out += ` \\\n  -H '${name}: ${String(v).replace(/'/g, "'\\''")}'`;
  }
  if (reqBody) out += ` \\\n  -d '${reqBody.replace(/'/g, "'\\''")}'`;
  return out;
};

/** Wall-clock start of the request, which is what makes a duplicate visible in the list. */
function fmtClock(ts) {
  if (!ts) return '';
  const d = new Date(ts);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n, w = 2) => String(n).padStart(w, '0');
  return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
}
