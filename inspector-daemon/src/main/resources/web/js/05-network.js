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
 * Every request to a control route, and the one place the page writes the control header.
 *
 * The header is how the daemon tells its own page from any other page in the browser, so whatever
 * the daemon later asks of a control request is added here, once, as it is added in one gate on the
 * server (`item:control-boundary-advisory`). Six call sites wrote it by hand. The marker post is
 * not one of them: its route is open by decision and it sends no header. `json`, when given, is the
 * body, sent as `application/json`. Answers what `source.fetch` answers, unread.
 */
function controlFetch(path, { method = 'POST', json } = {}) {
  const headers = { 'X-Inspector-Control': '1' };
  const init = { method, headers };
  if (json !== undefined) {
    headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(json);
  }
  return source.fetch(path, init);
}

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

/**
 * The page's own connection to the daemon, which is what the dot in the daemon group means. Its
 * label is drawn only where the bar has room, so the same word is the dot's accessible name.
 */
function setConn(kind) {
  const node = $('conn');
  node.dataset.state = kind;
  node.querySelector('.conn-label').textContent = kind;
  node.setAttribute('aria-label', `connection: ${kind}`);
}

/**
 * How long a relaunched daemon has to open the socket again before the page calls it lost. A
 * restart that never came back read `Restarting…` for as long as the page stayed open.
 */
const RESTART_GRACE_MS = 20000;

/**
 * How long live calls wait before the timeline and the glance are redrawn for them.
 *
 * Each redraws from every row the session holds, so one per call made a burst cost as many full
 * redraws as it had calls. The first call arms one redraw and the calls arriving meanwhile ride on
 * it, as the filtered re-read's rows do (`LIVE_REREAD_MS`): a burst of 100 calls over one second
 * costs at most `⌈1000 / 250⌉ + 1`, five, of each, and a lone call is drawn within a quarter second.
 */
const LIVE_DRAW_MS = 250;

/**
 * Arms the one pending redraw of the timeline and the glance, unless one is already armed.
 *
 * It draws whatever the page holds when it fires, so a switch or a pause meanwhile draws that and
 * nothing stale. The timeline keeps its own follow rule (`renderTimeline`): at its end it follows,
 * scrolled back it stays, and an open run stays open. The glance is drawn only while no call is
 * open, as everywhere else.
 */
function drawLive() {
  if (state.liveDraw.timer !== null) return;
  state.liveDraw.timer = setTimeout(() => {
    state.liveDraw.timer = null;
    renderTimeline();
    if ($('detail').hidden) renderSessionGlance();
  }, LIVE_DRAW_MS);
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
      admitLive('transactions', message.txn);
      if (state.sessionTotal !== null) state.sessionTotal++;
      // Under a filter the daemon says whether the row matches (`rereadLive`); pushed here, every
      // 200 drew under `status>=500` until the next load. With none it is one more match.
      if (state.filter) {
        oweReread('transactions');
      } else {
        state.transactions.push(message.txn);
        if (state.matched !== null) state.matched++;
      }
      // The header and the traffic tab say what the list and the session hold now, not at the last
      // load: `16 of 16 calls` stood above 20 rows. Under a filter only the session moved yet.
      renderCounts();
      renderTabs();
      renderList();
      renderWaterfall();
      renderPath();
      // The picker's label counts what the header counts, and the timeline and the glance follow
      // within LIVE_DRAW_MS: both said what the session held at the last load, `calls 27` under a
      // header of 29, and the timeline left out every call that arrived with no filter set.
      followLiveCount();
      drawLive();
    } else if (message.type === 'marker') {
      state.markers.push(message.marker);
      renderMarkers();
      renderList();
      renderTimeline();
      renderPath();
    } else if (message.type === 'signal') {
      // Carries `dataRef` already: the daemon broadcasts the row it stored, not the one it
      // received. A viewer handed the received row would see every payload as missing.
      admitLive('signals', message.signal);
      if (state.filter) oweReread('signals');
      else state.signals.push(message.signal);
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
    // Every close that is not a stop marks the daemon away and says so, except a restart still
    // inside its grace. Left at `running`, the next open took the page for one that had never been
    // gone, and rows that arrived while nothing listened stayed out of the whole-session copies.
    // It used to act only on the way out of `running`, so a restart that never came back read
    // `Restarting…` forever, and a stop or restart that failed while the daemon was gone left its
    // own message up through every later close.
    const restarting = state.serverState === 'restarting' && state.restartAt !== null
      && Date.now() - state.restartAt < RESTART_GRACE_MS;
    if (!restarting) {
      state.serverState = 'away';
      showServerBanner('Lost the daemon — reconnecting…', true);
    }
    setTimeout(connectLive, 1000);
  };
  ws.onopen = () => {
    setConn('connected');
    const wasAway = state.serverState !== 'running';
    state.serverState = 'running';
    state.restartAt = null;
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
