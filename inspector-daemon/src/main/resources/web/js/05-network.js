/* network: the one `fetch` and the one `WebSocket`, live or from an export */
'use strict';

// --- api ----------------------------------------------------------------

/**
 * Every request this page makes goes through here, and nowhere else.
 *
 * The live page asks the daemon; an exported file answers from the session it carries. One
 * object with one method and two implementations, rather than a mode check at each call site —
 * which is also why the browser's own fetch is called exactly once in this file, a count `render-web-ui-archive.js`
 * asserts so a new call site cannot quietly reach for the network from inside an export.
 */
const source = ARCHIVE ? archiveSource(ARCHIVE) : { fetch: (path, init) => fetch(path, init) };

/**
 * The daemon's read routes, answered from an exported session.
 *
 * It mirrors the routes the page reads, shape for shape: transactions come back as a page and
 * newest first, signals as a bare array and newest first — the two siblings differ because the
 * daemon's do. A route it does not know is a 404 naming the path, so a page that grows a new
 * request fails loudly in an export rather than rendering as if the data were absent.
 *
 * Anything that is not a GET is refused: it would be asking a daemon to do something, and there
 * is none. A filter is refused too — the grammar is evaluated by the daemon and has no copy here.
 */
function archiveSource(archive) {
  const encoder = new TextEncoder();
  const bytesOf = (blob) => (blob.encoding === 'base64'
    ? Uint8Array.from(atob(blob.text), (c) => c.charCodeAt(0))
    : encoder.encode(blob.text));
  const reply = (status, value) => {
    const bytes = value instanceof Uint8Array ? value : encoder.encode(JSON.stringify(value));
    // Decoded exactly as a live response is, so a binary body looks the same in both modes.
    const text = () => new TextDecoder().decode(bytes);
    return {
      ok: status >= 200 && status < 300,
      status,
      text: async () => text(),
      json: async () => JSON.parse(text()),
      arrayBuffer: async () => bytes.buffer.slice(bytes.byteOffset, bytes.byteOffset + bytes.byteLength),
    };
  };
  const refuse = (status, error) => reply(status, { error });
  const byMonoDesc = (a, b) => b.mono - a.mono;
  const rows = archive.transactions.map((t) => t.txn).sort(byMonoDesc);
  const exported = new Map(archive.transactions.map((t) => [t.txn.id, t]));
  const signals = archive.signals.slice().sort(byMonoDesc);
  const meta = archive.meta ? [archive.meta] : [];

  return {
    fetch: async (path, init) => {
      if (init && init.method && init.method !== 'GET') {
        return refuse(409, 'this is an exported session — there is no daemon to send that to');
      }
      const url = new URL(path, 'http://archive.invalid');
      const p = url.pathname;
      let m;
      if (p === '/api/sessions') return reply(200, meta);
      if (p === '/api/recording') return reply(200, []);
      if (/^\/api\/sessions\/[^/]+\/transactions$/.test(p)) {
        if (url.searchParams.get('filter')) {
          return refuse(400, 'filters are evaluated by the daemon, and an exported session has none');
        }
        // Sliced as the signals below are, for the same reason: the page reads the whole session a
        // page at a time, and an export answering every row whatever `offset` it was asked for
        // would be a different question from the daemon's. `total` and `matched` stay the session's.
        const offset = Math.max(0, Number(url.searchParams.get('offset')) || 0);
        const limit = url.searchParams.has('limit') ? Math.max(0, Number(url.searchParams.get('limit')) || 0) : rows.length;
        return reply(200, { total: rows.length, matched: rows.length, items: rows.slice(offset, offset + limit) });
      }
      if ((m = p.match(/^\/api\/sessions\/[^/]+\/transactions\/([^/]+)\/body\/(req|res)$/))) {
        const entry = exported.get(decodeURIComponent(m[1]));
        const blob = entry && (m[2] === 'req' ? entry.reqBody : entry.resBody);
        return blob ? reply(200, bytesOf(blob)) : refuse(404, `no ${m[2]} body for ${m[1]}`);
      }
      if ((m = p.match(/^\/api\/sessions\/[^/]+\/signals\/([^/]+)\/data$/))) {
        const blob = (archive.signalData || {})[decodeURIComponent(m[1])];
        return blob ? reply(200, bytesOf(blob)) : refuse(404, `no payload for signal ${m[1]}`);
      }
      if (/^\/api\/sessions\/[^/]+\/signals$/.test(p)) {
        if (url.searchParams.get('filter')) {
          return refuse(400, 'filters are evaluated by the daemon, and an exported session has none');
        }
        // Sliced as asked, with no clamp of its own: the page reads signals a page at a time, and
        // an export that ignored `offset` would be answering a different question from the daemon.
        const offset = Math.max(0, Number(url.searchParams.get('offset')) || 0);
        const limit = url.searchParams.has('limit') ? Math.max(0, Number(url.searchParams.get('limit')) || 0) : signals.length;
        return reply(200, signals.slice(offset, offset + limit));
      }
      if (/^\/api\/sessions\/[^/]+\/current$/.test(p)) return reply(200, archive.current || []);
      if (/^\/api\/sessions\/[^/]+\/markers$/.test(p)) return reply(200, archive.markers || []);
      return refuse(404, `not in this export: ${p}`);
    },
  };
}

const api = async (path) => {
  const res = await source.fetch(path);
  const text = await res.text();
  if (!res.ok) {
    let message = text;
    try { message = JSON.parse(text).error || text; } catch { /* keep raw */ }
    throw new Error(message);
  }
  return JSON.parse(text);
};

/**
 * This page is an exported file, and it says so in the one place a stopped daemon would.
 *
 * Muted rather than the stopped banner's red: a stopped daemon is a problem, an export is the
 * record working as intended. Both top-bar dots go with the daemon controls instead of being
 * repurposed — "does the list follow new rows" and "is the page connected" have no answer in a
 * file, and a dot that meant something else here would mean two things.
 *
 * Everything that would have to send something is hidden by `body.archive` in the stylesheet,
 * including controls built per render (replay, pull), which CSS reaches and an `if` would not.
 */
function enterArchiveMode(archive) {
  document.body.classList.add('archive');
  // Nothing will arrive, and "follow the newest row" would scroll a finished record to its end.
  state.liveTail = false;

  const filter = $('filter');
  filter.disabled = true;
  filter.placeholder = 'filters need the daemon — this is an exported session';
  filter.title = 'The filter grammar is evaluated by the daemon, and an exported file has none. ' +
    'Drag across the time axis to narrow.';

  const node = $('server-banner');
  node.replaceChildren();
  node.hidden = false;
  node.classList.add('archive');
  node.appendChild(el('span', 'banner-title', 'exported session'));
  const meta = archive.meta || {};
  const exported = new Date(archive.exportedAt);
  const parts = [
    meta.sessionId,
    meta.appId && `${meta.appId} ${meta.appVersion || ''}`.trim(),
    meta.device && `on ${meta.device}`,
    Number.isNaN(exported.getTime()) ? null : `exported ${exported.toLocaleString()}`,
    archive.exporter && `${archive.exporter.name} ${archive.exporter.version}`,
  ].filter(Boolean);
  node.appendChild(el('span', 'banner-text', parts.join(' · ')));
  node.appendChild(el('span', 'spacer'));
  node.appendChild(el('span', 'banner-hint muted',
    'read-only: filters, replay, markers and pulls need a running daemon'));
}

/** The page's own connection to the daemon, which is what the dot beside its controls means. */
function setConn(kind) {
  const node = $('conn');
  node.dataset.state = kind;
  node.querySelector('.conn-label').textContent = kind;
}

function connectLive() {
  const ws = new WebSocket(`ws://${location.host}/api/live`);
  ws.onmessage = (event) => {
    const message = JSON.parse(event.data);
    if (message.type === 'sessionStarted') {
      state.activeSessionId = message.meta.sessionId;
      loadSessions();
      return;
    }
    if (message.type === 'sessionEnded') { loadSessions(); return; }
    const captured = message.txn?.ts || message.signal?.ts;
    if (captured && (!state.lastCaptureTs || captured > state.lastCaptureTs)) state.lastCaptureTs = captured;
    if (message.sessionId !== state.sessionId) return;
    if (!state.liveTail) return;

    if (message.type === 'txn') {
      state.transactions.push(message.txn);
      admitLive('transactions', message.txn);
      // The list takes the row as one more match, so both counts move and what the filter hides
      // stays what it was.
      if (state.sessionTotal !== null) state.sessionTotal++;
      if (state.matched !== null) state.matched++;
      renderList();
      renderWaterfall();
      renderPath();
    } else if (message.type === 'marker') {
      state.markers.push(message.marker);
      renderMarkers();
      renderList();
      renderTimeline();
      renderPath();
    } else if (message.type === 'signal') {
      // Carries `dataRef` already: the daemon broadcasts the row it stored, not the one it
      // received. A viewer handed the received row would see every payload as missing.
      state.signals.push(message.signal);
      admitLive('signals', message.signal);
      if (tagOf(message.signal) === 'screen') {
        state.screens.push(message.signal);
        if (state.view === 'path') loadScreenPayloads().then(renderPath);
      }
      // A tag's first signal is the moment its tab becomes reachable, and the moment `all`
      // starts having something to merge. Both are decided by what the session holds, so the
      // bar is rebuilt rather than revealed.
      renderTabs();
      refreshCurrent();
      renderTimeline();
      renderWaterfall();
      renderPath();
      if (tagOf(message.signal) === state.view) {
        loadPayload(message.signal).then(renderBrowser);
      }
    }
  };
  ws.onclose = () => {
    // A deliberate stop is not a connection problem, so the banner saying the daemon is gone on
    // purpose stays. The page still redials, slowly: `inspector serve` in a terminal is how the
    // daemon comes back, and a page that had stopped listening would need a reload to notice.
    if (state.serverState === 'stopped') {
      setTimeout(connectLive, 3000);
      return;
    }
    setConn('reconnecting');
    // A drop nobody asked for marks the daemon away, as a stop or a restart does. Left at
    // `running`, the next open took the page for one that had never been gone, and rows that
    // arrived while nothing listened stayed out of the whole-session copies until the filter
    // was cleared.
    if (state.serverState === 'running') {
      state.serverState = 'away';
      showServerBanner('Lost the daemon — reconnecting…', true);
    }
    setTimeout(connectLive, 1000);
  };
  ws.onopen = () => {
    setConn('connected');
    const wasAway = state.serverState !== 'running';
    state.serverState = 'running';
    showServerBanner(null, false);
    setControlsEnabled(true);
    // A restart means a new process with a fresh session list; a reconnect after a drop or a
    // blip may also have missed rows. Either way the page's data is stale, so re-read it — the
    // whole-session copies too, or a filter keeps them as they were before the gap.
    if (wasAway) {
      forgetWholeSession();
      loadServerInfo();
      loadSessions().then(loadTransactions);
    }
  };
}
