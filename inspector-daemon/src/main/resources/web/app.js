/* Inspector web UI.
 *
 * Deliberately dependency-free: no framework, no build step, no network beyond this daemon.
 * The daemon is a local debugging tool holding unredacted credentials — it has no business
 * loading anything from a CDN.
 */
(() => {
  'use strict';

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
    newestFirst: localStorage.getItem('inspector.newestFirst') === '1',
    // Window for duplicate detection, in ms. 0 disables it.
    duplicateWindowMs: Number(localStorage.getItem('inspector.duplicateWindowMs') ?? 3000),
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
    // How many endpoint chips to show. 0 hides them.
    endpointLimit: Number(localStorage.getItem('inspector.endpointChipLimit') ?? 10),
    // App-state observations for this session, and the latest per (tag, name).
    signals: [],
    current: [],
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
      return JSON.stringify(JSON.parse(text), null, 2);
    } catch {
      return text; // truncated or malformed bodies are exactly the ones worth seeing raw
    }
  };

  // Mirrors NetworkTransaction.url on the Kotlin side. Built here rather than taken from the row
  // because `url` is a computed property and does not travel on the wire.
  const urlOf = (txn) => {
    const dflt = { http: 80, ws: 80, https: 443, wss: 443 }[String(txn.scheme).toLowerCase()];
    const port = txn.port != null && txn.port !== dflt ? `:${txn.port}` : '';
    return `${txn.scheme}://${txn.host}${port}${txn.path}${txn.query ? '?' + txn.query : ''}`;
  };

  const curlFor = (txn, reqBody) => {
    let out = `curl -X ${txn.method} '${urlOf(txn)}'`;
    for (const [name, values] of Object.entries(txn.reqHeaders || {})) {
      for (const v of values) out += ` \\\n  -H '${name}: ${String(v).replace(/'/g, "'\\''")}'`;
    }
    if (reqBody) out += ` \\\n  -d '${reqBody.replace(/'/g, "'\\''")}'`;
    return out;
  };

  // --- api ----------------------------------------------------------------

  const api = async (path) => {
    const res = await fetch(path);
    const text = await res.text();
    if (!res.ok) {
      let message = text;
      try { message = JSON.parse(text).error || text; } catch { /* keep raw */ }
      throw new Error(message);
    }
    return JSON.parse(text);
  };

  async function loadSessions() {
    state.sessions = await api('/api/sessions');
    state.recording = await api('/api/recording').catch(() => state.recording);
    const picker = $('session-picker');
    picker.innerHTML = '';
    for (const s of state.sessions) {
      const option = el('option', null, sessionLabel(s));
      option.value = s.sessionId;
      // The id is what the CLI, the MCP tools and the archive folder use, so it stays reachable.
      option.title = s.sessionId;
      picker.appendChild(option);
    }
    if (!state.sessionId && state.sessions.length) state.sessionId = state.sessions[0].sessionId;
    if (state.sessionId) picker.value = state.sessionId;

    showSessionLabel();
  }

  /**
   * `● app · device · 09:41 today · 12 calls`.
   *
   * The option used to be the session id, which is a timestamp and three slugs run together — the
   * facts were in it, but in the order a folder name needs rather than the one a reader asks in.
   * The dot marks a session an app is attached to right now, asked of `/api/recording` rather than
   * inferred from `endedAt`: a session whose app vanished has no `endedAt` and no app either.
   */
  function sessionLabel(meta) {
    const started = new Date(meta.startedAt);
    let when = '';
    if (!Number.isNaN(started.getTime())) {
      const pad = (n) => String(n).padStart(2, '0');
      const today = new Date();
      const sameDay = started.toDateString() === today.toDateString();
      const day = sameDay
        ? 'today'
        : started.toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
      when = `${pad(started.getHours())}:${pad(started.getMinutes())} ${day}`;
    }
    // `?? 0`, not `||`: kotlinx omits a field equal to its default, so a session with no traffic
    // arrives with `txnCount` missing rather than zero.
    const count = meta.txnCount ?? 0;
    const parts = [meta.appId, meta.device, when, `${count} ${count === 1 ? 'call' : 'calls'}`];
    const recording = (state.recording || []).includes(meta.sessionId);
    return `${recording ? '● ' : ''}${parts.filter(Boolean).join(' · ')}`;
  }

  /**
   * The header count, over whatever is actually on screen.
   *
   * The daemon reports how many rows the *text filter* matched, which was the whole story until
   * the axis could narrow things further. Counting only that would leave the header reading
   * `17/17` above a list of five — a number that is true of a query nobody can see.
   */
  function renderCounts() {
    const total = state.matchTotal;
    if (total === null || total === undefined) {
      $('counts').textContent = '';
      return;
    }
    // With its noun: a bare `12/12` beside a session picker could be a count of anything.
    $('counts').textContent = `${visibleTransactions().length} of ${total} ${total === 1 ? 'call' : 'calls'}`;
  }

  /**
   * Which app the page is showing, in the window title — the picker already names it in the bar.
   * Also runs on switch, or it would name the old one.
   */
  function showSessionLabel() {
    const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
    document.title = meta ? `${meta.appId} · inspector` : 'inspector';
  }

  async function loadTransactions() {
    if (!state.sessionId) return;
    const q = new URLSearchParams({ filter: state.filter, limit: '2000' });
    try {
      const page = await api(`/api/sessions/${encodeURIComponent(state.sessionId)}/transactions?${q}`);
      state.transactions = page.items;
      state.matchTotal = page.total;
      showFilterError(null);
    } catch (e) {
      // Parser messages are written to be shown verbatim, so show them verbatim.
      showFilterError(e.message);
      state.transactions = [];
      state.matchTotal = null;
    }
    renderCounts();
    state.markers = await api(`/api/sessions/${encodeURIComponent(state.sessionId)}/markers`).catch(() => []);
    state.recording = await api('/api/recording').catch(() => []);
    await syncAllTransactions();
    await loadSignals();
    renderMarkers();
    markerFormState();
    renderList();
    renderTimeline();
  }

  // --- tabs ---------------------------------------------------------------

  /**
   * Tags Inspector has an opinion about, in the order they are worth reading.
   *
   * The list is a *preference*, not a filter. A tag this build has never heard of still gets a
   * tab — the schema says tags are app-defined, and a view that silently drops one would make
   * Inspector lie about what the app recorded.
   */
  const TAB_ORDER = ['cache', 'screen', 'state'];
  const TAG_LABELS = { screen: 'screens' };

  const tagOf = (signal) => String(signal.tag).toLowerCase();
  const signalsForTag = (tag) => state.signals.filter((signal) => tagOf(signal) === tag);

  function tagsInSession() {
    const tags = [...new Set(state.signals.map(tagOf))];
    const rank = (tag) => {
      const at = TAB_ORDER.indexOf(tag);
      return at === -1 ? TAB_ORDER.length : at;
    };
    return tags.sort((a, b) => rank(a) - rank(b) || a.localeCompare(b));
  }

  /** Every view this session can offer, in tab order. */
  function availableViews() {
    const views = [];
    // Traffic first, and it is where a session opens. This is a network debugger: in a real
    // session the merged timeline ran 126 rows of which 13 were calls, so landing there puts
    // what you came for at one row in ten. The merge is a correlation tool you reach for once
    // you know which call you care about, which makes it the second tab, not the first.
    views.push({ id: 'network', label: 'traffic', title: 'HTTP calls', count: state.transactions.length });
    // Merging is only worth a tab when there is something to merge with the traffic.
    if (state.signals.length) {
      views.push({
        id: 'all',
        label: 'timeline',
        title: 'Traffic, signals and markers on one timeline',
      });
    }
    // Grouping by screen needs both halves. With traffic but no screen signals it would be one
    // unnamed block, which the traffic tab already draws better.
    if (state.transactions.length && state.signals.some((s) => tagOf(s) === 'screen')) {
      views.push({
        id: 'waterfall',
        label: 'waterfall',
        title: 'Calls grouped by the screen that was showing when they started',
      });
    }
    for (const tag of tagsInSession()) {
      views.push({
        id: tag,
        label: TAG_LABELS[tag] || tag,
        title: `${tag} signals recorded by the app`,
        count: signalsForTag(tag).length,
      });
    }
    return views;
  }

  function renderTabs() {
    const bar = $('tabs');
    const views = availableViews();
    // A single tab is a label, not a choice. Sessions with no signals therefore look exactly as
    // they did before tabs existed.
    bar.hidden = views.length < 2;
    bar.innerHTML = '';
    for (const view of views) {
      const tab = el('button', 'tab');
      tab.dataset.view = view.id;
      tab.title = view.title;
      tab.appendChild(el('span', 'tab-label', view.label));
      if (view.count !== undefined) tab.appendChild(el('span', 'tab-count muted mono', String(view.count)));
      tab.classList.toggle('active', view.id === state.view);
      tab.addEventListener('click', () => selectView(view.id));
      bar.appendChild(tab);
    }
  }

  async function selectView(id) {
    if (state.view !== id) {
      state.browserKey = null;
      state.browserObservation = null;
      state.browserFilter = '';
      state.browserFacets = {};
      const input = $('browser-filter');
      if (input) input.value = '';
    }
    state.view = id;
    // A cache key only exists inside a payload, so the browser cannot draw its list without
    // them. Fetched on the way in rather than for every session that happens to hold cache rows.
    if (id === 'cache') await loadPayloadsFor('cache');
    renderTabs();
    applyView();
  }

  /**
   * Signals and the current-state panel.
   *
   * Failures are swallowed to an empty list on purpose: a daemon older than this page has no
   * signal routes, and the traffic view must keep working against one rather than going blank.
   */
  async function loadSignals() {
    if (!state.sessionId) return;
    const id = encodeURIComponent(state.sessionId);
    const q = new URLSearchParams({ filter: state.filter, limit: '2000' });
    const page = await api(`/api/sessions/${id}/signals?${q}`).catch(() => null);
    state.signals = Array.isArray(page) ? page : [];
    state.current = await api(`/api/sessions/${id}/current`).catch(() => []);

    // A tab whose tag this session never recorded is a dead end, so the set is rebuilt per
    // session and the current view falls back rather than pointing at nothing.
    const ids = availableViews().map((view) => view.id);
    if (!ids.includes(state.view)) state.view = 'network';
    state.viewChosenForSession = true;

    renderTabs();
    renderCurrent();
    applyView();
    if ($('detail').hidden) renderSessionGlance();
  }

  /**
   * Keeps `allTransactions` in step with the current session.
   *
   * With no filter the rows just fetched already are the whole session, so the common case costs
   * nothing. With a filter active it costs one extra request per *session*, not per keystroke:
   * the endpoint inventory only grows when new traffic arrives, and the live socket appends to
   * both lists.
   */
  async function syncAllTransactions() {
    if (!state.filter) {
      // A copy, not an alias — the live socket pushes into both, and sharing one array would
      // append every new row twice.
      state.allTransactions = state.transactions.slice();
      state.allSessionId = state.sessionId;
      return;
    }
    if (state.allSessionId === state.sessionId && state.allTransactions.length) return;
    try {
      const q = new URLSearchParams({ filter: '', limit: '2000' });
      const page = await api(`/api/sessions/${encodeURIComponent(state.sessionId)}/transactions?${q}`);
      state.allTransactions = page.items;
    } catch {
      // Chips are a convenience. If this fails, fall back to what we have rather than taking the
      // list down with it.
      state.allTransactions = state.transactions.slice();
    }
    state.allSessionId = state.sessionId;
  }

  function showFilterError(message) {
    const node = $('filter-error');
    node.hidden = !message;
    node.textContent = message || '';
  }

  // --- rendering ----------------------------------------------------------

  function renderMarkers() {
    const list = $('markers');
    list.innerHTML = '';
    const labels = [...new Set(state.markers.map((m) => m.label))];
    if (!labels.length) {
      list.appendChild(el('li', 'muted', 'none'));
      return;
    }
    for (const label of labels) {
      const item = el('li', null, label);
      item.title = 'Filter to traffic after this marker';
      item.onclick = () => {
        $('filter').value = `since:marker("${label}")`;
        applyFilter();
      };
      list.appendChild(item);
    }
  }

  /**
   * Dropping a marker from here.
   *
   * The daemon refuses a marker on a session that is not currently recording — there is no clock
   * to place it on once the app has gone — so the form says that up front instead of letting
   * someone type a label and collect a 409.
   *
   * The check is `/api/recording`, **not** `sessionIsLive()`. They disagree exactly where it
   * matters: `sessionIsLive` asks whether `endedAt` is absent, and a session whose daemon was
   * killed or whose app vanished without a clean close has no `endedAt` and no open connection
   * either. Gating on that would leave the control enabled on precisely the sessions where the
   * app went away, which is when somebody most wants to mark where it happened.
   *
   * `source: 'user'` matters. `MarkerSource.USER` existed and had no caller: every marker in every
   * archive so far says `app` or `agent`. A marker dropped by the person watching is a different
   * claim from one an agent left while working through the session, and whoever reads the archive
   * later is entitled to tell them apart.
   */
  function markerFormState() {
    const form = $('marker-form');
    const input = $('marker-label');
    const status = $('marker-status');
    const live = state.recording.includes(state.sessionId);
    input.disabled = !live;
    $('marker-add').disabled = !live;
    if (!live) {
      status.hidden = false;
      status.textContent = 'no app is connected to this session — a marker needs a live one';
    } else if (status.dataset.sticky !== '1') {
      status.hidden = true;
      status.textContent = '';
    }
    return form;
  }

  function wireMarkerForm() {
    const form = $('marker-form');
    const input = $('marker-label');
    const status = $('marker-status');

    const say = (text, isError) => {
      status.hidden = false;
      status.textContent = text;
      status.classList.toggle('bad', Boolean(isError));
      status.dataset.sticky = '1';
      setTimeout(() => { status.dataset.sticky = '0'; markerFormState(); }, 2500);
    };

    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      const label = input.value.trim();
      // A marker with no label is a line across the list that explains nothing.
      if (!label) return;
      $('marker-add').disabled = true;
      try {
        const res = await fetch(`/api/sessions/${encodeURIComponent(state.sessionId)}/markers`, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ label, source: 'user' }),
        });
        if (res.ok) {
          input.value = '';
          say('added');
          // The marker arrives over the live socket like any other, but only for a viewer that
          // has one. Re-reading is what makes the divider appear for everyone else.
          state.markers = await api(`/api/sessions/${encodeURIComponent(state.sessionId)}/markers`)
            .catch(() => state.markers);
          renderMarkers();
          renderList();
        } else {
          // The daemon's own message names the reason — "markers can only be added to a session
          // that is currently recording" is more use than "failed".
          const detail = await res.json().catch(() => ({}));
          say(detail.error || `refused (${res.status})`, true);
        }
      } catch {
        say('could not reach the daemon', true);
      } finally {
        markerFormState();
      }
    });
  }

  /** Inside the brushed stretch of the session, if one is selected. */
  const inRange = (mono) =>
    !state.timeRange || (mono >= state.timeRange.from && mono <= state.timeRange.to);

  const visibleTransactions = () => state.transactions.filter((txn) => inRange(txn.mono));

  /**
   * Transactions in display order — the single source of truth for both rendering and j/k
   * navigation, so "next row" always means the row visually below the current one.
   */
  const orderedRows = () => {
    const rows = visibleTransactions().sort((a, b) => a.mono - b.mono);
    return state.newestFirst ? rows.reverse() : rows;
  };

  // --- the scope bar ------------------------------------------------------

  /**
   * A mirror of `pathScope` in `:inspector-model`. Keep them in step.
   *
   * It lives there because the overlay draws the same bar, and three thresholds plus a segment
   * walk are exactly the kind of rule that drifts invisibly: a bar that appears on one surface and
   * not the other, for a session both are showing, reads as a bug in whichever one you are looking
   * at. `PathScopeTest` in that module is the authority; this is the copy that must follow it.
   *
   * Three rules, each of which has a test named after it over there:
   *
   * - **The dominant host only.** A session talking to an API and an auth server has no single
   *   prefix. Rows outside the scope name their host, so the bar is never read as covering them.
   * - **Never the last segment.** `/v3/orders/submit` and `/v3/orders/cancel` share `/v3/orders/`
   *   and that is as far as this may go; a prefix that swallowed a whole path leaves a blank row.
   * - **Thresholds.** A four-row session, or a two-character saving, is not worth a bar.
   */
  const SCOPE_MIN_ROWS = 4;
  const SCOPE_MIN_LENGTH = 6;

  function pathScope(transactions) {
    if (transactions.length < SCOPE_MIN_ROWS) return null;

    const counts = new Map();
    for (const txn of transactions) counts.set(txn.host, (counts.get(txn.host) || 0) + 1);
    let host = null;
    let best = -1;
    for (const [candidate, count] of counts) {
      if (count > best) { host = candidate; best = count; }
    }
    if (host === null) return null;

    const paths = transactions.filter((t) => t.host === host).map((t) => t.path);
    if (paths.length < SCOPE_MIN_ROWS) return null;

    const segmented = paths.map((path) => path.split('/').filter((s) => s.length > 0));
    const shared = [];
    for (let i = 0; ; i++) {
      const segment = segmented[0][i];
      if (segment === undefined) break;
      // `length <= i + 1` is the never-the-last-segment rule: this path has nothing left over.
      if (segmented.some((s) => s.length <= i + 1 || s[i] !== segment)) break;
      shared.push(segment);
    }
    if (!shared.length) return null;

    const prefix = `/${shared.join('/')}/`;
    if (prefix.length < SCOPE_MIN_LENGTH) return null;

    return {
      host,
      prefix,
      covered: paths.length,
      label: host + prefix,
      covers: (txn) => txn.host === host && txn.path.startsWith(prefix),
      strip: (path) => (path.startsWith(prefix) ? path.slice(prefix.length) : path),
    };
  }

  /**
   * Drawn above the list and not scrolled with it.
   *
   * It is a standing claim about what every path beneath it is missing, so a row read after the
   * bar had scrolled off would be read wrong. Same reasoning as the overlay's, and the same place
   * on screen.
   */
  function renderScopeBar() {
    const bar = $('scope-bar');
    const scope = state.scope;
    bar.hidden = !scope;
    if (!scope) return;
    bar.innerHTML = '';
    const label = el('span', 'scope-label mono', scope.label);
    label.title = 'Shared by most of this session, and lifted out of the rows below';
    bar.appendChild(label);
    bar.appendChild(el('span', 'scope-count muted mono', `${scope.covered} of ${state.allTransactions.length}`));
  }

  function renderList() {
    const list = $('list');
    list.innerHTML = '';
    // The map follows the data. Suspended during a brush, where `setTimeRange` has already drawn
    // it and redrawing per pointermove is the one place this is hot.
    if (!axisPaintSuspended) renderAxis();

    // Over *all* transactions, not the filtered view: a duplicate whose twin is filtered out is
    // still a duplicate, and making the highlight depend on the current filter would hide exactly
    // the case you go looking for.
    state.duplicates = computeDuplicates(state.allTransactions, state.duplicateWindowMs);
    // Same source as the duplicates and the endpoint chips, and for the same reason.
    state.scope = pathScope(state.allTransactions);
    renderScopeBar();
    renderEndpointChips();

    const rows = orderedRows();
    $('list-empty').hidden = rows.length > 0;
    // Two things can empty this list and they have different fixes. Saying which one did it is
    // the difference between "widen the selection" and "the app sent nothing".
    $('list-empty').textContent = state.timeRange && state.transactions.length
      ? 'no calls in the selected stretch'
      : 'no transactions';

    // Build the interleaved sequence oldest-first, where "marker, then the rows after it" is the
    // only arrangement that makes causal sense, then reverse the whole thing. Reversing after
    // interleaving keeps each divider attached to the same rows: read downward in newest-first
    // and a divider below a row still means that row happened after the marker.
    const markers = [...state.markers].filter((m) => inRange(m.mono)).sort((a, b) => a.mono - b.mono);
    const sequence = [];
    let markerIndex = 0;
    for (const txn of visibleTransactions().sort((a, b) => a.mono - b.mono)) {
      while (markerIndex < markers.length && markers[markerIndex].mono <= txn.mono) {
        sequence.push({ marker: markers[markerIndex] });
        markerIndex++;
      }
      sequence.push({ txn });
    }
    for (; markerIndex < markers.length; markerIndex++) {
      sequence.push({ marker: markers[markerIndex] });
    }
    if (state.newestFirst) sequence.reverse();

    for (const entry of sequence) {
      list.appendChild(
        entry.marker
          ? el('div', 'marker-divider', entry.marker.label)
          : rowFor(entry.txn),
      );
    }

    // Follow the newest row, wherever it now lives.
    if (state.liveTail) {
      const pane = list.parentElement;
      pane.scrollTop = state.newestFirst ? 0 : pane.scrollHeight;
    }
  }

  // --- handing a finding to an agent --------------------------------------

  /*
   * You spot something here; the agent that can act on it is in your editor. Today the handover
   * is a sentence — "the failing POST to /oauth/token around 15:13" — and the agent has to go
   * find what you were already looking at, which it may or may not land on.
   *
   * These build a paste. Not a screenshot and not a raw dump: an unambiguous identifier, enough
   * context to answer the obvious question without a round trip, and the exact MCP calls that
   * fetch the rest. An agent with the inspector server registered can go deeper; one without it
   * still has the facts.
   */

  const BUNDLE_BODY_CAP = 4000;
  const BUNDLE_NEAR_MS = 10_000;
  /**
   * Per-header cap.
   *
   * Wide enough for every ordinary header and narrow enough to clip a bearer token, which on a
   * real call ran 1300 characters — a quarter of the paste spent on a value no agent reads, and
   * a live credential dropped into a chat log on the way. The length is still reported, so
   * "was a token sent, and did it change between these two calls" stays answerable.
   */
  const BUNDLE_HEADER_CAP = 120;

  /** `mono` is the device's clock. Offsets from a chosen origin are the only readable form. */
  const relMs = (mono, origin) => {
    const delta = mono - origin;
    const sign = delta < 0 ? '-' : '+';
    return `${sign}${fmtMs(Math.abs(delta))}`;
  };

  const clip = (text, cap) =>
    text.length <= cap ? text : `${text.slice(0, cap)}\n… truncated at ${cap} of ${text.length} chars`;

  const clipHeader = (value) =>
    value.length <= BUNDLE_HEADER_CAP
      ? value
      : `${value.slice(0, BUNDLE_HEADER_CAP)}… [${value.length} chars]`;

  /** The screen that was showing when something happened, and how long it had been showing. */
  function screenAt(mono) {
    const arrivals = state.signals
      .filter((s) => tagOf(s) === 'screen' && s.mono <= mono)
      .sort((a, b) => b.mono - a.mono);
    return arrivals[0] || null;
  }

  function bundleHeader() {
    const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
    const lines = [`# Inspector session ${state.sessionId}`];
    if (meta) lines.push(`${meta.appId} · ${meta.device} · ${meta.buildType || 'debug'}`);
    return lines;
  }

  function nearbyLines(mono) {
    const near = [
      ...state.signals.map((s) => ({ mono: s.mono, text: `${s.tag} ${s.name}` })),
      ...state.markers.map((m) => ({ mono: m.mono, text: `marker "${m.label}"` })),
      ...state.allTransactions
        .filter((t) => t.mono !== mono)
        .map((t) => ({ mono: t.mono, text: `${t.method} ${t.path} → ${t.status ?? 'ERR'} ${fmtMs(t.ms)}` })),
    ]
      .filter((e) => Math.abs(e.mono - mono) <= BUNDLE_NEAR_MS)
      .sort((a, b) => a.mono - b.mono);
    // Bounded: a busy ten seconds can hold hundreds of state observations, and a paste nobody
    // reads to the end is worse than a shorter one that is all signal.
    const capped = near.length > 30
      ? [...near.slice(0, 15), { elided: near.length - 30 }, ...near.slice(-15)]
      : near;
    // The elision carries no offset. Giving it one — even the anchor's own — reads as an event
    // that happened at that instant, which is the one thing it is not.
    return capped.map((e) =>
      e.elided ? `${' '.repeat(10)}… ${e.elided} more` : `${relMs(e.mono, mono).padStart(8)}  ${e.text}`);
  }

  /** Everything an agent needs about one call, and where to get what is not here. */
  async function transactionBundle(txn) {
    const [reqBody, resBody] = await Promise.all([fetchBody(txn, 'req'), fetchBody(txn, 'res')]);
    const screen = screenAt(txn.mono);
    const lines = [
      ...bundleHeader(),
      '',
      '## The call',
      `id ${txn.id}`,
      `${txn.method} ${urlOf(txn)}`,
      `${txn.status ?? 'transport failure'} · ${fmtMs(txn.ms)} · req ${fmtBytes(txn.reqBytes)} · res ${fmtBytes(txn.resBytes)}`,
      `started ${txn.ts}`,
    ];
    if (txn.error) lines.push(`error ${txn.error}`);
    if (txn.attempt > 1) lines.push(`attempt ${txn.attempt} of this call`);
    if (txn.redacted && txn.redacted.length) {
      // Say it was removed, not that it was absent, or the agent concludes none was sent.
      lines.push(`redacted at capture: ${txn.redacted.join(', ')}`);
    }
    if (screen) {
      lines.push(`screen at the time: ${screen.name} (arrived ${fmtMs(txn.mono - screen.mono)} earlier)`);
    }

    for (const [title, side, body] of [['Request', 'req', reqBody], ['Response', 'res', resBody]]) {
      const headers = Object.entries(side === 'req' ? txn.reqHeaders || {} : txn.resHeaders || {});
      lines.push('', `### ${title} headers`);
      lines.push(
        headers.length
          ? headers.map(([k, v]) => `${k}: ${clipHeader(v.join(', '))}`).join('\n')
          : 'none',
      );
      lines.push('', `### ${title} body`);
      const absent = bodyAbsenceReason(txn, side, body);
      lines.push(absent !== null ? absent : clip(body, BUNDLE_BODY_CAP));
    }

    lines.push('', `## Around it (±${fmtMs(BUNDLE_NEAR_MS)})`, ...nearbyLines(txn.mono));
    lines.push(
      '',
      '## Read more (MCP server "inspector")',
      'Long header values above are clipped; get_transaction returns them whole.',
      `get_transaction(session: "${state.sessionId}", id: "${txn.id}")`,
      `get_body(session: "${state.sessionId}", id: "${txn.id}", side: "res")`,
      `timeline(session: "${state.sessionId}", limit: 60)`,
    );
    return lines.join('\n');
  }

  /**
   * The session as a whole — or, when the axis has narrowed it, the stretch on screen.
   *
   * Honouring the brush is the point. Handing over the whole session when you have spent a minute
   * narrowing to the ten seconds that matter throws away the work you just did.
   */
  function sessionBundle() {
    const txns = visibleTransactions();
    const failed = txns.filter((t) => t.error || (t.status ?? 0) >= 400);
    const lines = [...bundleHeader()];

    if (state.timeRange) {
      lines.push(`narrowed to a ${fmtMs(state.timeRange.to - state.timeRange.from)} stretch of it`);
    }
    // Unlike the panel this button sits in, the bundle is "what I am looking at" and so keeps the
    // text filter too. Stated rather than silent: counts below are of the filtered set.
    if (state.filter) lines.push(`filter applied: ${state.filter}`);
    lines.push('', `${txns.length} calls, ${failed.length} failed`);

    const hosts = new Map();
    for (const txn of txns) hosts.set(txn.host, (hosts.get(txn.host) || 0) + 1);
    for (const [host, n] of [...hosts].sort((a, b) => b[1] - a[1])) lines.push(`host ${host} · ${n}`);

    if (failed.length) {
      lines.push('', '## Failed');
      for (const txn of failed.slice(0, 20)) {
        lines.push(`${txn.id}  ${txn.method} ${txn.path} → ${txn.status ?? 'ERR'} ${txn.error || ''}`.trimEnd());
      }
    }

    const slowest = [...txns].filter((t) => t.ms != null).sort((a, b) => b.ms - a.ms).slice(0, 5);
    if (slowest.length) {
      lines.push('', '## Slowest');
      for (const txn of slowest) lines.push(`${fmtMs(txn.ms).padStart(7)}  ${txn.id}  ${txn.method} ${txn.path}`);
    }

    const groups = waterfallGroups();
    if (groups.length) {
      lines.push('', '## By screen');
      for (const group of groups) {
        lines.push(
          `${group.segment.name || 'before the first screen'} — ${group.rows.length} calls, ` +
          `${fmtMs(group.wall)}, ${fmtBytes(group.bytes)}${group.failed ? `, ${group.failed} failed` : ''}`,
        );
      }
    }

    const current = state.current.filter((s) => tagOf(s) !== 'state').slice(0, 12);
    if (current.length) {
      lines.push('', '## What the app holds now');
      for (const signal of current) {
        lines.push(`${signal.tag} ${signal.name} · ${signal.trigger === 'request' ? 'pulled' : 'pushed'} ${fmtAge(ageOf(signal.ts))}`);
      }
    }

    lines.push(
      '',
      '## Read more (MCP server "inspector")',
      `session_summary(session: "${state.sessionId}")`,
      `list_transactions(session: "${state.sessionId}", filter: "has:error")`,
      `timeline(session: "${state.sessionId}", limit: 60)`,
    );
    return lines.join('\n');
  }

  /**
   * Copy, and say so on the button itself.
   *
   * `navigator.clipboard` is unavailable on a page served over plain http from anything but
   * localhost, and it rejects rather than throwing — a silent failure that looks exactly like a
   * successful copy. The button reports it.
   */
  /**
   * A copy button for one value.
   *
   * The UI could copy a whole cURL command and a whole AI bundle, and could not copy one header
   * value — so getting a bearer token into another terminal meant selecting it by hand out of a
   * monospace block that wraps, which is where the ends get clipped and nobody notices until the
   * request 401s. The overlay has had per-field copy since the first device outing; this is the
   * same affordance.
   *
   * Always in the DOM rather than created on hover, so it is reachable by keyboard and by a
   * screen reader. It is CSS that keeps it quiet until the row is hovered or the button focused.
   *
   * @param what names the value in the confirmation, because several of these sit close together
   *   and a bare "copied" does not say which one took.
   */
  function copyButton(text, what) {
    const button = el('button', 'copy-field', '⧉');
    button.type = 'button';
    button.title = `Copy ${what}`;
    button.setAttribute('aria-label', `Copy ${what}`);
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      navigator.clipboard.writeText(text).then(
        () => {
          button.classList.add('done');
          button.textContent = '✓';
          setTimeout(() => { button.classList.remove('done'); button.textContent = '⧉'; }, 1200);
        },
        () => {
          button.classList.add('bad');
          button.title = 'The clipboard is unavailable here. Open the UI on 127.0.0.1 rather than a LAN address.';
        },
      );
    });
    return button;
  }

  /** The value, with its copy button beside it. */
  function copyableRow(cls, text, what) {
    const line = el('div', `copyable ${cls}`);
    line.appendChild(el('span', 'copyable-value', text));
    line.appendChild(copyButton(text, what));
    return line;
  }

  function copyBundle(button, build, label) {
    button.disabled = true;
    Promise.resolve()
      .then(build)
      .then((text) => navigator.clipboard.writeText(text).then(() => text))
      .then((text) => {
        button.textContent = `copied ${fmtBytes(text.length)}`;
      })
      .catch(() => {
        button.textContent = 'copy failed';
        button.title = 'The clipboard is unavailable here. Open the UI on 127.0.0.1 rather than a LAN address.';
      })
      .finally(() => {
        setTimeout(() => { button.textContent = label; button.disabled = false; }, 1600);
      });
  }

  function bundleButton(label, build, title) {
    const button = el('button', 'btn btn-sm', label);
    button.title = title;
    button.addEventListener('click', (event) => {
      event.stopPropagation();
      copyBundle(button, build, label);
    });
    return button;
  }

  // --- the waterfall ------------------------------------------------------

  /**
   * Calls grouped by the screen that was showing when each one started.
   *
   * The join is the point: screens and traffic are recorded by different mechanisms that share
   * only a clock, and nothing until now put them together. "This screen costs six calls and two
   * seconds" is not in either stream alone.
   *
   * Attribution is by *start*, not overlap. A call that outlives the screen that began it still
   * belongs to that screen — it was that navigation that asked for it, and moving it to whatever
   * came next would blame the wrong screen for the wait.
   */
  function waterfallGroups() {
    const win = sessionWindow();
    if (!win) return [];
    const rows = visibleTransactions().sort((a, b) => a.mono - b.mono);
    if (!rows.length) return [];

    const segments = screenSegments(win);
    const groups = [];
    const openGroup = (segment) => {
      const group = { segment, rows: [] };
      groups.push(group);
      return group;
    };

    if (!segments.length) {
      // No screen signals recorded: still worth drawing, just as one unnamed stretch.
      const group = openGroup({ name: null, from: win.from, to: win.to, colour: 'var(--divider)' });
      group.rows = rows;
    } else {
      for (const segment of segments) {
        const inSegment = rows.filter((txn) => txn.mono >= segment.from && txn.mono < segment.to);
        if (inSegment.length) openGroup(segment).rows = inSegment;
      }
      const lastSegment = segments[segments.length - 1];
      const after = rows.filter((txn) => txn.mono >= lastSegment.to);
      if (after.length) openGroup(lastSegment).rows.push(...after);
    }

    for (const group of groups) {
      const starts = group.rows.map((t) => t.mono);
      const ends = group.rows.map((t) => t.mono + (t.ms ?? 0));
      group.from = Math.min(...starts);
      group.to = Math.max(...ends);
      group.span = Math.max(1, group.to - group.from);
      group.bytes = group.rows.reduce((n, t) => n + (t.resBytes || 0), 0);
      group.failed = group.rows.filter((t) => t.error || (t.status ?? 0) >= 400).length;
      // Wall time is not the sum of the durations: calls overlap, and reporting the sum would
      // claim a screen took far longer than the user waited.
      group.wall = group.to - group.from;
    }
    return groups;
  }

  function renderWaterfall() {
    if (state.view !== 'waterfall') return;
    const root = $('waterfall');
    root.innerHTML = '';
    const groups = waterfallGroups();
    $('waterfall-empty').hidden = groups.length > 0;
    if (!groups.length) return;

    for (const group of groups) {
      const block = el('div', 'wf-group');

      const head = el('div', 'wf-head');
      const swatch = el('span', 'wf-swatch');
      swatch.style.background = group.segment.colour;
      head.appendChild(swatch);
      head.appendChild(el('span', 'wf-name', group.segment.name || 'before the first screen'));
      const stats = el('span', 'wf-stats muted mono');
      stats.textContent = `${group.rows.length} calls · ${fmtMs(group.wall)} · ${fmtBytes(group.bytes)}`;
      head.appendChild(stats);
      if (group.failed) head.appendChild(el('span', 'wf-failed', `${group.failed} failed`));
      block.appendChild(head);

      // Each group gets its own scale. A shared one would squeeze a 200 ms screen into a sliver
      // next to a 30 s one, and the question here is "what did *this* screen do", not "which
      // screen was longest" — the stats line answers that.
      for (const txn of group.rows) {
        const row = el('div', `wf-row${txn.id === state.selectedId ? ' selected' : ''}`);
        row.dataset.id = txn.id;
        row.tabIndex = 0;

        row.appendChild(el('span', `method ${methodClass(txn.method)}`, txn.method));
        const path = el('span', 'wf-path');
        // See `.wf-path`: isolated so the RTL box trims the prefix without reordering the path.
        const isolated = el('bdi', null, txn.path);
        isolated.dir = 'ltr';
        path.appendChild(isolated);
        path.title = txn.path;
        row.appendChild(path);

        const track = el('span', 'wf-track');
        const left = ((txn.mono - group.from) / group.span) * 100;
        const width = ((txn.ms ?? 0) / group.span) * 100;
        const bar = el('span', `wf-bar s${statusClass(txn.status)}`);
        bar.style.left = `${left}%`;
        // Never zero-width: an instant call is still a call, and one that vanishes reads as a
        // row that failed to draw.
        bar.style.width = `${Math.max(0.8, width)}%`;
        bar.title = `${fmtMs(txn.ms)} · started ${txn.mono - group.from}ms into this screen`;
        track.appendChild(bar);
        row.appendChild(track);

        row.appendChild(el('span', 'wf-ms muted mono', fmtMs(txn.ms)));
        row.addEventListener('click', () => select(txn.id));
        row.addEventListener('keydown', (event) => {
          if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); select(txn.id); }
        });
        block.appendChild(row);
      }
      root.appendChild(block);
    }
  }

  // --- the time axis ------------------------------------------------------

  const AXIS_BUCKETS = 200;
  const AXIS_W = 1000;          // viewBox units; the element itself stretches to its container
  const AXIS_BARS_H = 30;
  const AXIS_BAND_H = 7;
  const AXIS_H = AXIS_BARS_H + AXIS_BAND_H;

  const svgEl = (tag, attrs = {}) => {
    const node = document.createElementNS('http://www.w3.org/2000/svg', tag);
    for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
    return node;
  };

  /**
   * One colour per screen, assigned by first appearance rather than hashed.
   *
   * A hash gives a screen the same colour forever, which sounds better than it is: two screens
   * whose names hash adjacent become indistinguishable, and you cannot fix it. Ordering means the
   * first few screens of a session are always maximally far apart, which is what you are actually
   * looking at.
   */
  const SCREEN_COLOURS = [
    '#6aa9ff', '#c08cff', '#59c98a', '#ffb454', '#ff7b8a', '#4fd1c5', '#f6c945', '#9aa7ff',
  ];

  /** The wall-clock span of the session, and the `mono` window it maps onto. */
  function sessionWindow() {
    const monos = [
      ...state.allTransactions.map((t) => t.mono),
      ...state.signals.map((s) => s.mono),
      ...state.markers.map((m) => m.mono),
    ];
    if (!monos.length) return null;
    const from = Math.min(...monos);
    const to = Math.max(...monos);
    return { from, to, span: Math.max(1, to - from) };
  }

  /**
   * Which screen was on at each moment, as segments.
   *
   * A screen signal is a point observation — "we arrived here" — so the screen it names is the
   * one showing from that moment until the next such signal. Calls before the first one are not
   * attributed to a guess: the app was somewhere, and this build does not know where.
   */
  function screenSegments(win) {
    if (!win) return [];
    const arrivals = state.signals
      .filter((s) => tagOf(s) === 'screen')
      .sort((a, b) => a.mono - b.mono);
    if (!arrivals.length) return [];

    const colours = new Map();
    const colourFor = (name) => {
      if (!colours.has(name)) colours.set(name, SCREEN_COLOURS[colours.size % SCREEN_COLOURS.length]);
      return colours.get(name);
    };

    const segments = [];
    if (arrivals[0].mono > win.from) {
      segments.push({ name: null, from: win.from, to: arrivals[0].mono, colour: 'var(--divider)' });
    }
    for (let i = 0; i < arrivals.length; i++) {
      const next = arrivals[i + 1];
      const from = arrivals[i].mono;
      const to = next ? next.mono : win.to;
      // Re-arriving at the screen you are already on is a re-render, not a new segment.
      const open = segments[segments.length - 1];
      if (open && open.name === arrivals[i].name) open.to = to;
      else segments.push({ name: arrivals[i].name, from, to, colour: colourFor(arrivals[i].name) });
    }
    return segments.filter((s) => s.to > s.from);
  }

  function renderAxis() {
    const host = $('axis');
    const win = sessionWindow();
    // Every view built from transactions on a clock, which the waterfall is: brushing already
    // narrows it, so hiding the control that does the brushing would be the odd choice.
    const onTimeView = ['all', 'network', 'waterfall'].includes(state.view);
    host.hidden = !onTimeView || !win || state.allTransactions.length + state.signals.length < 2;
    if (host.hidden) return;

    const svg = $('axis-svg');
    svg.setAttribute('viewBox', `0 0 ${AXIS_W} ${AXIS_H}`);
    svg.innerHTML = '';
    const x = (mono) => ((mono - win.from) / win.span) * AXIS_W;

    // The shape is drawn from the *whole* session, never the filtered set. A map that redraws
    // itself every time you filter cannot answer "where am I", which is what it is for.
    const buckets = new Array(AXIS_BUCKETS).fill(null).map(() => ({ all: 0, hit: 0, bad: 0 }));
    const slot = (mono) =>
      Math.min(AXIS_BUCKETS - 1, Math.floor(((mono - win.from) / win.span) * AXIS_BUCKETS));
    const matching = new Set(state.transactions.map((t) => t.id));
    for (const txn of state.allTransactions) {
      const b = buckets[slot(txn.mono)];
      b.all++;
      if (matching.has(txn.id)) b.hit++;
      if (txn.error || (txn.status ?? 0) >= 400) b.bad++;
    }
    const tallest = Math.max(1, ...buckets.map((b) => b.all));
    const bw = AXIS_W / AXIS_BUCKETS;

    for (let i = 0; i < AXIS_BUCKETS; i++) {
      const b = buckets[i];
      if (!b.all) continue;
      const h = Math.max(1.5, (b.all / tallest) * AXIS_BARS_H);
      const bx = i * bw;
      // Three layers, because they answer three different questions: how busy, how much of it
      // your filter kept, and how much of it failed.
      svg.appendChild(svgEl('rect', {
        x: bx, y: AXIS_BARS_H - h, width: Math.max(1, bw - 0.4), height: h, class: 'axis-bar-all',
      }));
      if (b.hit) {
        const hh = Math.max(1.5, (b.hit / tallest) * AXIS_BARS_H);
        svg.appendChild(svgEl('rect', {
          x: bx, y: AXIS_BARS_H - hh, width: Math.max(1, bw - 0.4), height: hh, class: 'axis-bar-hit',
        }));
      }
      if (b.bad) {
        const bh = Math.max(1.5, (b.bad / tallest) * AXIS_BARS_H);
        svg.appendChild(svgEl('rect', {
          x: bx, y: AXIS_BARS_H - bh, width: Math.max(1, bw - 0.4), height: bh, class: 'axis-bar-bad',
        }));
      }
    }

    // The screen band ties the two halves of this release together: the bars say when the app was
    // busy, the band says where it was while it was.
    for (const seg of screenSegments(win)) {
      const rect = svgEl('rect', {
        x: x(seg.from), y: AXIS_BARS_H + 1, width: Math.max(1, x(seg.to) - x(seg.from)),
        height: AXIS_BAND_H - 1, fill: seg.colour, class: 'axis-screen',
      });
      rect.appendChild(svgEl('title')).textContent = seg.name
        ? `${seg.name} — ${fmtMs(seg.to - seg.from)}`
        : 'before the first screen signal';
      svg.appendChild(rect);
    }

    for (const marker of state.markers) {
      const mx = x(marker.mono);
      svg.appendChild(svgEl('line', { x1: mx, y1: 0, x2: mx, y2: AXIS_BARS_H, class: 'axis-marker' }));
      const flag = svgEl('rect', { x: mx - 2, y: 0, width: 4, height: 4, class: 'axis-marker-flag' });
      flag.appendChild(svgEl('title')).textContent = marker.label;
      svg.appendChild(flag);
    }

    if (state.timeRange) {
      const from = x(state.timeRange.from);
      const to = x(state.timeRange.to);
      // Dim what is excluded rather than tint what is kept: the selection should read as the
      // normal view with the rest pushed back, not as a highlight over an unchanged chart.
      svg.appendChild(svgEl('rect', { x: 0, y: 0, width: Math.max(0, from), height: AXIS_H, class: 'axis-mask' }));
      svg.appendChild(svgEl('rect', { x: to, y: 0, width: Math.max(0, AXIS_W - to), height: AXIS_H, class: 'axis-mask' }));
      svg.appendChild(svgEl('line', { x1: from, y1: 0, x2: from, y2: AXIS_H, class: 'axis-edge' }));
      svg.appendChild(svgEl('line', { x1: to, y1: 0, x2: to, y2: AXIS_H, class: 'axis-edge' }));
    }

    renderAxisFoot(win);
  }

  function renderAxisFoot(win) {
    const clock = (mono) => {
      // `mono` is the device's monotonic clock and means nothing by itself. Anchor it to the
      // wall-clock time of the nearest row that carries both, which every row does.
      const anchor = state.allTransactions[0] || state.signals[0];
      if (!anchor) return '';
      const at = Date.parse(anchor.ts);
      if (!Number.isFinite(at)) return '';
      return new Date(at + (mono - anchor.mono)).toLocaleTimeString();
    };
    $('axis-from').textContent = clock(win.from);
    $('axis-to').textContent = clock(win.to);

    const hint = $('axis-hint');
    hint.innerHTML = '';
    if (!state.timeRange) {
      hint.className = 'axis-hint muted';
      hint.textContent = `${state.allTransactions.length} calls over ${fmtMs(win.span)} — drag to narrow`;
      return;
    }
    hint.className = 'axis-hint';
    const kept = visibleTransactions().length;
    const label = el('span', null, `${clock(state.timeRange.from)} – ${clock(state.timeRange.to)} · ${kept} calls`);
    const clear = el('button', 'btn btn-sm btn-quiet', 'show all');
    clear.addEventListener('click', () => setTimeRange(null));
    hint.appendChild(label);
    hint.appendChild(clear);
  }

  let axisPaintSuspended = false;

  function setTimeRange(range) {
    state.timeRange = range;
    renderAxis();
    renderCounts();
    axisPaintSuspended = true;
    try {
      renderList();
      renderTimeline();
      renderWaterfall();
    } finally {
      axisPaintSuspended = false;
    }
    if ($('detail').hidden) renderSessionGlance();
  }

  /**
   * Drag a stretch, click to clear.
   *
   * Wired once against the container rather than per render: the SVG is rebuilt on every live
   * signal, and listeners attached to its children would be replaced mid-drag.
   */
  function wireAxis() {
    const host = $('axis');
    const svg = $('axis-svg');
    let anchor = null;

    const monoAt = (event) => {
      const win = sessionWindow();
      if (!win) return null;
      const box = svg.getBoundingClientRect();
      // A zero-width box means the element is not laid out — in a headless render, or before
      // first paint. Dividing by it yields NaN, and a NaN range silently filters everything out.
      if (!box.width) return null;
      const ratio = Math.min(1, Math.max(0, (event.clientX - box.left) / box.width));
      return win.from + ratio * win.span;
    };

    host.addEventListener('pointerdown', (event) => {
      if (event.target.closest('button')) return;
      anchor = monoAt(event);
      if (anchor === null) return;
      // Capture keeps the drag alive when the pointer leaves the strip, which it will — the strip
      // is 37px tall. Not fatal if unavailable, so not worth failing the drag over.
      try { host.setPointerCapture(event.pointerId); } catch { /* no capture, drag still works */ }
    });

    host.addEventListener('pointermove', (event) => {
      if (anchor === null) return;
      const now = monoAt(event);
      if (now === null) return;
      setTimeRange({ from: Math.min(anchor, now), to: Math.max(anchor, now) });
    });

    const finish = (event) => {
      if (anchor === null) return;
      const now = monoAt(event);
      const win = sessionWindow();
      // A click is a drag of nothing. Treat anything under 1% of the session as "show me all of
      // it again" rather than selecting a sliver nobody could have aimed at.
      if (win && now !== null && Math.abs(now - anchor) < win.span * 0.01) setTimeRange(null);
      anchor = null;
    };
    host.addEventListener('pointerup', finish);
    host.addEventListener('pointercancel', finish);
  }

  function setSortOrder(newestFirst) {
    state.newestFirst = newestFirst;
    localStorage.setItem('inspector.newestFirst', newestFirst ? '1' : '0');
    $('order-oldest').classList.toggle('on', !newestFirst);
    $('order-newest').classList.toggle('on', newestFirst);
    renderList();
    renderTimeline();
    // Order is a reading preference, not a view: it applies to the key list and to every
    // history under it too.
    if (state.view !== 'all' && state.view !== 'network') renderBrowser();
  }

  /**
   * Mirror of `duplicateGroups` in :inspector-model — same rules, so a row that highlights here
   * highlights in the app too. Keep the two in step; the Kotlin one carries the reasoning.
   *
   * The two rules that matter: a *different* callId is required, because redirect hops and retry
   * attempts share one and are a single logical call; and headers are excluded, because a signed
   * app puts a fresh nonce on every request and a key including them would never match twice.
   */
  function duplicateKey(txn) {
    return [
      txn.method,
      urlOf(txn),
      txn.status ?? -1,
      `${txn.reqBytes || 0}/${txn.resBytes || 0}`,
    ].join(' ');
  }

  /**
   * The last non-empty path segment: `/v3/accounts/profile/status` -> `status`.
   *
   * Trailing slashes are ignored. A path with no segments at all (`/`) has no shortcut worth
   * offering, so it yields null rather than an empty chip.
   */
  function lastSegment(path) {
    const segments = String(path || '').split('/').filter((s) => s.length > 0);
    return segments.length ? segments[segments.length - 1] : null;
  }

  // Builds a star-glob term - for the segment `status`, the filter `path:` star `/status`.
  //
  // A bare `path:` term is a substring match, so `path:profile` would also match
  // `/v3/accounts/profile/status` and the chip would not mean what its label says. The star makes
  // it a glob, and `globMatches` anchors the trailing literal with `endsWith`, so the term matches
  // only paths that end in `/status`.
  //
  // Line comments on purpose: the term contains the sequence that closes a block comment.
  const endpointFilter = (segment) => `path:*/${segment}`;

  /**
   * One entry per distinct last segment, most-used first.
   *
   * Ordered by count rather than recency deliberately: recency would reshuffle the whole chip row
   * on every request during live tail, and the endpoints worth a one-click filter are the ones
   * that dominate the list. Ties break on the most recent call and then alphabetically, so the
   * order is fully determined and the jsdom harness can assert it.
   */
  function endpointShortcuts(txns, limit) {
    if (!limit || limit <= 0) return [];
    const bySegment = new Map();
    for (const txn of txns) {
      const segment = lastSegment(txn.path);
      // The filter tokenizer splits on whitespace and `|`, so a segment containing either cannot
      // be expressed as a term. Skipping beats emitting a chip that filters to the wrong thing.
      if (!segment || /[\s|"]/.test(segment)) continue;
      const entry = bySegment.get(segment) || { segment, count: 0, latest: -Infinity };
      entry.count += 1;
      if (txn.mono > entry.latest) entry.latest = txn.mono;
      bySegment.set(segment, entry);
    }
    return [...bySegment.values()]
      .sort((a, b) => b.count - a.count || b.latest - a.latest || a.segment.localeCompare(b.segment))
      .slice(0, limit);
  }

  function renderEndpointChips() {
    const box = $('endpoint-chips');
    const shortcuts = endpointShortcuts(state.allTransactions, state.endpointLimit);
    box.innerHTML = '';
    box.hidden = shortcuts.length === 0;

    for (const { segment, count } of shortcuts) {
      const value = endpointFilter(segment);
      const chip = el('button', 'chip');
      chip.dataset.filter = value;
      chip.title = `${count} request${count === 1 ? '' : 's'} ending in /${segment}`;
      chip.appendChild(el('span', null, segment));
      chip.appendChild(el('span', 'chip-count', String(count)));
      chip.classList.toggle('active', value === state.filter);
      chip.addEventListener('click', () => {
        $('filter').value = $('filter').value === value ? '' : value;
        applyFilter();
      });
      box.appendChild(chip);
    }
  }

  function computeDuplicates(txns, windowMs) {
    const marks = new Map();
    if (!windowMs || windowMs <= 0 || txns.length < 2) return marks;

    const byKey = new Map();
    for (const txn of txns) {
      const key = duplicateKey(txn);
      if (!byKey.has(key)) byKey.set(key, []);
      byKey.get(key).push(txn);
    }

    let ordinal = 0;
    for (const group of byKey.values()) {
      // `mono` is the monotonic clock. `ts` is a wall clock that can step, and the device and host
      // clocks are unrelated — so timing uses mono and only the display uses ts.
      const ordered = [...group].sort((a, b) => a.mono - b.mono);
      let run = [];
      const flush = () => {
        if (run.length > 1 && new Set(run.map((t) => t.callId)).size > 1) {
          ordinal += 1;
          const span = run[run.length - 1].mono - run[0].mono;
          for (const t of run) marks.set(t.id, { ordinal, count: run.length, spanMs: span });
        }
        run = [];
      };
      for (const txn of ordered) {
        if (run.length && txn.mono - run[run.length - 1].mono > windowMs) flush();
        run.push(txn);
      }
      flush();
    }
    return marks;
  }

  /** Wall-clock start of the request, which is what makes a duplicate visible in the list. */
  function fmtClock(ts) {
    if (!ts) return '';
    const d = new Date(ts);
    if (Number.isNaN(d.getTime())) return '';
    const pad = (n, w = 2) => String(n).padStart(w, '0');
    return `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${pad(d.getMilliseconds(), 3)}`;
  }

  function rowFor(txn) {
    const cls = statusClass(txn.status);
    const row = el('div', 'row');
    row.dataset.id = txn.id;
    if (txn.id === state.selectedId) row.classList.add('selected');

    const duplicate = state.duplicates.get(txn.id);
    if (duplicate) {
      row.classList.add('duplicate');
      row.title =
        `sent ${duplicate.count}x by separate calls within ${fmtMs(duplicate.spanMs)} — ` +
        'same method, URL, status and byte counts';
    }

    row.appendChild(el('span', `status-dot b${cls}`));
    // The start time is what makes a duplicate legible: two rows 1.9s apart is the whole finding.
    row.appendChild(el('span', 'clock mono muted', fmtClock(txn.ts)));
    row.appendChild(el('span', `method ${methodClass(txn.method)}`, txn.method));

    // The host appears only when it is not the one the scope bar claims. Repeating it on every
    // row is noise when it never changes, and load-bearing on the one row where it does — the
    // same rule the overlay applies, which is why both read `scope.covers`.
    const scope = state.scope;
    if (!scope || txn.host !== scope.host) {
      row.appendChild(el('span', 'host mono muted', txn.host));
    }

    // Stripped when the bar above already says this front. The bar is what makes that safe: it is
    // on screen, it does not scroll away, and it names the exact prefix that was removed.
    const path = el('span', 'path', scope && scope.covers(txn) ? scope.strip(txn.path) : txn.path);
    if (txn.attempt > 1) {
      const badge = el('span', 'attempt', ` ·attempt ${txn.attempt}`);
      path.appendChild(badge);
    }
    if (duplicate) {
      // Colour alone is invisible to some readers and unexplained to the rest.
      path.appendChild(el('span', 'dup-badge', ` ·repeated ${duplicate.count}x`));
    }
    row.appendChild(path);

    row.appendChild(el('span', `status s${cls}`, txn.status === null || txn.status === undefined ? 'ERR' : txn.status));
    row.appendChild(el('span', 'ms', fmtMs(txn.ms)));
    row.appendChild(el('span', 'size', fmtBytes(Math.max(txn.reqBytes || 0, txn.resBytes || 0))));

    row.onclick = () => select(txn.id);
    return row;
  }

  async function renderDetail(txn) {
    const pane = $('detail');
    $('detail-empty').hidden = true;
    pane.hidden = false;
    pane.innerHTML = '';
    openDrawer();

    const head = el('div', 'detail-head');
    head.appendChild(el('span', `status s${statusClass(txn.status)}`, txn.status ?? 'ERR'));
    head.appendChild(el('span', `method ${methodClass(txn.method)}`, txn.method));
    head.appendChild(el('span', 'path', txn.path));
    pane.appendChild(head);

    const [reqBody, resBody] = await Promise.all([
      fetchBody(txn, 'req'),
      fetchBody(txn, 'res'),
    ]);

    const copy = el('button', 'btn', 'copy cURL');
    copy.onclick = () => {
      navigator.clipboard.writeText(curlFor(txn, reqBody)).then(
        () => { copy.textContent = 'copied'; setTimeout(() => (copy.textContent = 'copy cURL'), 1200); },
      );
    };
    head.appendChild(copy);

    const replayBtn = el('button', 'btn', 'replay');
    replayBtn.title = 'Re-send this request. Per-request headers are regenerated by the running app.';
    replayBtn.onclick = () => runReplay(txn, pane, replayBtn);
    head.appendChild(replayBtn);

    // Separate from `replay` rather than replacing it: re-sending a request unchanged is the
    // common case and deserves one click, and an editor that opened every time would put a form
    // between the reader and the thing they usually want.
    const editBtn = el('button', 'btn', 'edit & replay');
    editBtn.title = 'Change the request before re-sending it';
    editBtn.onclick = () => toggleReplayEditor(txn, pane, reqBody);
    head.appendChild(editBtn);

    head.appendChild(bundleButton(
      'for AI',
      () => transactionBundle(txn),
      'Copy this call, what surrounded it, and the MCP calls that fetch the rest — to paste to an agent',
    ));

    // Pinned above the tabs, both of them, because neither is something to go looking for.
    //
    // Redaction says credentials were *removed*, and a reader who does not see it concludes none
    // were sent. An attempt chain means this row is one of several for the same call — read it as
    // the whole story and you are reading a retry as the only try. Tabbing away either of those
    // would hide a correction to what the rest of the pane appears to say.
    if (txn.redacted && txn.redacted.length) {
      pane.appendChild(el('div', 'section-title', 'Redacted at capture'));
      pane.appendChild(el('div', 'banner redacted', txn.redacted.join(', ')));
    }

    const chain = state.transactions.filter((t) => t.callId === txn.callId).sort((a, b) => a.attempt - b.attempt);
    if (chain.length > 1) {
      pane.appendChild(el('div', 'section-title', 'Attempt chain'));
      for (const attempt of chain) {
        const line = el(
          'div',
          `chain-row${attempt.id === txn.id ? ' current' : ''}`,
          `${attempt.attempt}. ${attempt.method} ${attempt.path} → ${attempt.status ?? 'ERR'} (${fmtMs(attempt.ms)})`,
        );
        if (attempt.id !== txn.id) line.onclick = () => select(attempt.id);
        pane.appendChild(line);
      }
    }

    /*
      One face at a time, response first.

      This was a single scroll: overview, request headers, request body, response headers,
      response body. The response body is what you opened the row for and it was last, behind
      roughly forty lines of headers — and headers are exactly the thing that is long, unreadable
      and rarely what you want. Three tabs, and the one you came for is already showing.
    */
    const panels = {
      res: sidePanel(txn, 'res', resBody),
      req: sidePanel(txn, 'req', reqBody),
      overview: overviewPanel(txn),
    };

    const bar = el('div', 'dtabs');
    const faces = [
      ['res', 'response', fmtBytes(txn.resBytes)],
      ['req', 'request', txn.reqBytes ? fmtBytes(txn.reqBytes) : null],
      ['overview', 'overview', null],
    ];
    if (!panels[state.detailTab]) state.detailTab = 'res';

    const paint = () => {
      for (const button of bar.querySelectorAll('.dtab')) {
        button.classList.toggle('active', button.dataset.dtab === state.detailTab);
      }
      for (const [name, panel] of Object.entries(panels)) panel.hidden = name !== state.detailTab;
    };

    for (const [name, label, hint] of faces) {
      const button = el('button', 'dtab');
      button.dataset.dtab = name;
      button.appendChild(el('span', null, label));
      // The size on the tab answers "is there even a body in there" without opening it.
      if (hint) button.appendChild(el('span', 'dtab-hint muted mono', hint));
      button.addEventListener('click', () => { state.detailTab = name; paint(); });
      bar.appendChild(button);
    }
    pane.appendChild(bar);
    for (const panel of Object.values(panels)) pane.appendChild(panel);
    paint();
  }

  function overviewPanel(txn) {
    const panel = el('div', 'dtab-panel');
    panel.dataset.dtab = 'overview';
    const kv = el('dl', 'kv');
    const put = (k, v) => {
      kv.appendChild(el('dt', null, k));
      const dd = el('dd');
      dd.appendChild(copyableRow('', String(v), k.toLowerCase()));
      kv.appendChild(dd);
    };
    put('URL', urlOf(txn));
    put('Status', txn.status ?? 'transport failure');
    if (txn.error) put('Error', txn.error);
    put('Duration', fmtMs(txn.ms));
    put('Request size', fmtBytes(txn.reqBytes));
    put('Response size', fmtBytes(txn.resBytes));
    put('Started', txn.ts);
    panel.appendChild(kv);
    return panel;
  }

  /**
   * Explains an absent body, using what the capture layer recorded rather than assuming.
   * Returns null when there is a body to show.
   *
   * Every branch here was once collapsed into a single "content type outside the capture
   * allowlist" line, which confidently misreported bodies that had been captured and stored.
   */
  function bodyAbsenceReason(txn, side, body) {
    if (body !== null) return null;
    const totalBytes = side === 'req' ? txn.reqBytes : txn.resBytes;
    const omitted = side === 'req' ? txn.reqBodyOmitted : txn.resBodyOmitted;
    const contentType = side === 'req' ? txn.reqContentType : txn.resContentType;
    const size = fmtBytes(totalBytes);

    // A recorded reason outranks the byte count. A discarded hop reports zero bytes because nothing
    // was ever read from it, so the 'empty' shortcut below would state as fact the one thing
    // capture could not determine.
    if (omitted === 'discarded') {
      return 'body not read — the client discarded this response to make the next attempt';
    }
    if (!totalBytes) return 'empty';

    if (omitted === 'contentType') {
      const named = contentType ? `content type ${contentType} is` : 'no content type was declared, so it is';
      return `${size} not captured — ${named} not on the capture allowlist (set captureAllBodies = true to capture it anyway)`;
    }
    if (omitted === 'streaming') {
      return `${size} not captured — streamed body, never held in memory`;
    }
    const ref = side === 'req' ? txn.reqBodyRef : txn.resBodyRef;
    if (!ref) return `${size} recorded on device, but no body reached the archive`;
    return `${size} captured, but ${ref} could not be read`;
  }

  /**
   * One side of the call: body first, then its headers.
   *
   * Body before headers is the whole point of the change. Headers are long, mostly boilerplate,
   * and the same on every call; the body is the one part that differs and the reason the row was
   * opened. They are still one scroll apart — just the other way round.
   */
  function sidePanel(txn, side, body) {
    const title = side === 'req' ? 'Request' : 'Response';
    const headers = side === 'req' ? txn.reqHeaders : txn.resHeaders;
    const truncated = side === 'req' ? txn.reqBodyTruncated : txn.resBodyTruncated;
    const contentType = side === 'req' ? txn.reqContentType : txn.resContentType;
    const totalBytes = side === 'req' ? txn.reqBytes : txn.resBytes;

    const panel = el('div', 'dtab-panel');
    panel.dataset.dtab = side;

    panel.appendChild(el('div', 'section-title', `${title} body`));
    const absent = bodyAbsenceReason(txn, side, body);
    if (absent !== null) {
      panel.appendChild(el('div', 'muted', absent));
    } else {
      if (truncated) {
        panel.appendChild(
          el('div', 'banner', `truncated at ${fmtBytes(body.length)} of ${fmtBytes(totalBytes)}`),
        );
      }
      const isJson = (contentType || '').includes('json') || /^\s*[{[]/.test(body);
      const pretty = isJson ? prettyJson(body) : body;
      const wrap = el('div', 'body-wrap');
      wrap.appendChild(el('pre', 'body', pretty));
      // The body as shown, pretty-printing included: what you are looking at is what you meant to
      // copy, and re-minifying it on the way to the clipboard would be a surprise.
      wrap.appendChild(copyButton(pretty, `${side === 'req' ? 'request' : 'response'} body`));
      panel.appendChild(wrap);
    }

    panel.appendChild(el('div', 'section-title', `${title} headers`));
    const box = el('div', 'headers');
    const entries = Object.entries(headers || {});
    if (!entries.length) {
      box.appendChild(el('div', 'muted', 'none'));
    } else {
      for (const [name, values] of entries) {
        const value = values.join(', ');
        const line = el('div', 'copyable');
        const text = el('span', 'copyable-value');
        text.appendChild(el('span', 'hname', `${name}: `));
        text.appendChild(el('span', null, value));
        line.appendChild(text);
        // The value alone, not `name: value` — what you are about to paste into a curl or a
        // terminal is the value.
        line.appendChild(copyButton(value, name));
        box.appendChild(line);
      }
    }
    panel.appendChild(box);
    return panel;
  }

  async function fetchBody(txn, side) {
    const ref = side === 'req' ? txn.reqBodyRef : txn.resBodyRef;
    if (!ref) return null;
    // Bodies are fetched lazily, only for the selected row — the index stays cheap.
    const res = await fetch(
      `/api/sessions/${encodeURIComponent(state.sessionId)}/transactions/${txn.id}/body/${side}`,
    );
    return res.ok ? res.text() : null;
  }


  // --- timeline -----------------------------------------------------------

  /** Tags with a lane of their own. Anything else shares the generic one. */
  const KNOWN_TAGS = ['screen', 'state', 'cache', 'session'];
  const laneFor = (tag) =>
    KNOWN_TAGS.includes(String(tag).toLowerCase()) ? String(tag).toLowerCase() : 'other';

  function applyView() {
    const all = state.view === 'all';
    const network = state.view === 'network';
    const waterfall = state.view === 'waterfall';
    const browsing = !all && !network && !waterfall;

    $('list').hidden = !network;
    $('list-empty').hidden = !network || state.transactions.length > 0;
    $('timeline').hidden = !all;
    $('timeline-empty').hidden = true;
    $('waterfall').hidden = !waterfall;
    $('waterfall-empty').hidden = true;
    $('browser').hidden = !browsing;
    for (const tab of $('tabs').querySelectorAll('.tab')) {
      tab.classList.toggle('active', tab.dataset.view === state.view);
    }
    // The browser carries its own detail panel, so the transaction one would be a second, empty
    // detail column. A class rather than `hidden`, because `hidden` loses to any author rule that
    // sets `display` — the bug this pane already shipped once.
    $('panes').classList.toggle('panes-wide', browsing);

    // The toolbar filters transactions, which is what both of these views are built from. The tag
    // browser filters payload keys instead and carries its own toolbar for it, so showing this one
    // there would put two filter boxes on screen that mean different things.
    // The waterfall is built from transactions, so the call filter applies to it as much as to
    // the list. Only the tag browsers, which filter payload keys, get their own toolbar instead.
    $('toolbar').hidden = browsing;
    if (browsing) closePops();
    // The drawer covers the list it was opened from. Carrying it across a tab switch would leave
    // it sitting over a list that never produced it.
    closeDrawer();
    // Nothing selected means the pane is free to say something useful about the session instead.
    if (!browsing && $('detail').hidden) renderSessionGlance();
    renderAxis();
    // `Now` is the app's current state, which is only ever read beside the merged timeline.
    $('now-strip').hidden = !all || state.current.length === 0;

    if (all) renderTimeline();
    if (waterfall) renderWaterfall();
    if (browsing) renderBrowser();
  }

  /**
   * The detail pane, when it is a drawer rather than a column.
   *
   * The class is set at every width and only means anything under the media query, so there is no
   * breakpoint to track in JS and nothing to re-synchronise on resize: drag the window wider and
   * the drawer is simply a column again, still showing what it was showing.
   */
  function openDrawer() {
    $('panes').classList.add('drawer-open');
    $('drawer-scrim').hidden = false;
  }

  function closeDrawer() {
    $('panes').classList.remove('drawer-open');
    $('drawer-scrim').hidden = true;
  }

  /**
   * Markers and the shortcut legend, one click away.
   *
   * Both were permanent rail sections and both sat below the fold, which is the worst of both:
   * space spent, and not readable anyway. A popover costs a click and is the first time the
   * shortcut list has been visible at all.
   */
  function closePops() {
    for (const [button, pop] of POPS) {
      $(pop).hidden = true;
      $(button).setAttribute('aria-expanded', 'false');
    }
  }

  const POPS = [
    ['markers-button', 'markers-pop'],
    ['keys-button', 'keys-pop'],
  ];

  function wirePops() {
    for (const [button, pop] of POPS) {
      $(button).addEventListener('click', (event) => {
        event.stopPropagation();
        const open = $(pop).hidden;
        closePops();
        $(pop).hidden = !open;
        $(button).setAttribute('aria-expanded', String(open));
        if (open) {
          // Anchored under its own button rather than at a fixed offset, so it stays put when
          // the chips wrap and move the button to a second row.
          const rect = $(button).getBoundingClientRect();
          $(pop).style.top = `${rect.bottom + 4}px`;
        }
      });
    }
    document.addEventListener('click', closePops);
    for (const [, pop] of POPS) {
      $(pop).addEventListener('click', (event) => event.stopPropagation());
    }
  }

  // --- tag browser --------------------------------------------------------

  /**
   * A key list beside one key's detail, for any tag.
   *
   * Inspector does not define what a signal payload contains — `tag` is app-defined and so is the
   * payload — so this reads a set of conventional field names and degrades rather than failing:
   * `storage`, `scopes` (or `scope`), `expired`, `key`, `value`, `payloadBytes`. A payload using
   * none of them still gets a key with its time and raw value; an app following the convention
   * gets the facets too. Documented in INTEGRATION.md §12d.
   *
   * Two payload shapes both feed this, because both are useful and apps emit both:
   *   - a **whole-cache snapshot**, `{ items: [ … ] }`, which expands to one key per entry;
   *   - a **single entry**, one observation, typically pushed as the entry changes.
   */

  /**
   * Payloads for one tag, fetched once per signal.
   *
   * Only `cache` needs them up front: a cache key lives *inside* the payload, so the list cannot
   * be drawn without it. Every other tag names its key in `signal.name`, so the list draws from
   * the rows alone and a payload is fetched only when someone opens one.
   */
  async function loadPayloadsFor(tag) {
    const missing = signalsForTag(tag).filter(
      (signal) => signal.dataRef && !state.payloads.has(signal.id),
    );
    if (!missing.length || !state.sessionId) return;
    await Promise.all(missing.map(loadPayload));
  }

  async function loadPayload(signal) {
    if (!signal || !signal.dataRef || state.payloads.has(signal.id)) return;
    const id = encodeURIComponent(state.sessionId);
    try {
      const res = await fetch(`/api/sessions/${id}/signals/${encodeURIComponent(signal.id)}/data`);
      state.payloads.set(signal.id, JSON.parse(await res.text()));
    } catch {
      // A payload that will not parse is still an observation; it just has nothing to show.
      // Recording the failure stops us retrying it on every render.
      state.payloads.set(signal.id, null);
    }
  }

  const asScopes = (o) => {
    if (Array.isArray(o.scopes)) return o.scopes.map(String);
    if (typeof o.scope === 'string') return [o.scope];
    return [];
  };

  function cacheFieldsOf(o) {
    return {
      storage: typeof o.storage === 'string' ? o.storage : '',
      scopes: asScopes(o),
      // Tri-state on purpose: `false` means the app said it is live, `null` means it said
      // nothing. Collapsing them would report an unknown as healthy.
      expired: typeof o.expired === 'boolean' ? o.expired : null,
      value: o.value === undefined ? null : o.value,
      bytes: typeof o.payloadBytes === 'number' ? o.payloadBytes : null,
      kind: typeof o.kind === 'string' ? o.kind : null,
      storageKey: typeof o.key === 'string' ? o.key : null,
    };
  }

  const baseRow = (signal) => ({
    ts: signal.ts,
    mono: signal.mono,
    signalId: signal.id,
    trigger: signal.trigger === 'request' ? 'request' : 'app',
  });

  /** Observations contributed by one signal — many, when it carries a whole-cache snapshot. */
  function rowsForSignal(signal, tag) {
    const payload = state.payloads.get(signal.id);
    const base = baseRow(signal);

    if (tag === 'cache' && payload && typeof payload === 'object' && Array.isArray(payload.items)) {
      return payload.items.map((item) => ({
        ...base,
        ...cacheFieldsOf(item),
        // In a whole-cache snapshot the entry's own id is the only identity it has; the signal
        // name identifies the snapshot, not the entry.
        key: (typeof item.id === 'string' && item.id) || signal.name,
        fromSnapshot: true,
      }));
    }
    if (tag === 'cache' && payload && typeof payload === 'object') {
      return [{ ...base, ...cacheFieldsOf(payload), key: signal.name, fromSnapshot: false }];
    }
    return [{
      ...base,
      key: signal.name,
      storage: '',
      scopes: [],
      expired: null,
      kind: null,
      storageKey: null,
      bytes: typeof signal.bytes === 'number' && signal.bytes > 0 ? signal.bytes : null,
      // `undefined` is "not fetched yet" and `null` is "there is nothing"; the detail panel
      // fetches on the first and says so on the second.
      value: signal.dataRef ? (state.payloads.has(signal.id) ? payload : undefined) : null,
      fromSnapshot: false,
    }];
  }

  /** One entry per key, carrying every observation of it in causal order. */
  function browserGroups(tag) {
    const groups = new Map();
    for (const signal of signalsForTag(tag)) {
      for (const row of rowsForSignal(signal, tag)) {
        const group = groups.get(row.key) || { key: row.key, rows: [] };
        group.rows.push(row);
        groups.set(row.key, group);
      }
    }
    for (const group of groups.values()) {
      group.rows.sort((a, b) => a.mono - b.mono);
      group.latest = group.rows[group.rows.length - 1];
    }
    const ordered = [...groups.values()].sort((a, b) => a.latest.mono - b.latest.mono);
    return state.newestFirst ? ordered.reverse() : ordered;
  }

  /**
   * Percent-decoded for display only.
   *
   * A storage key is usually escaped by whatever scheme the app uses to make it a safe filename,
   * so it arrives full of `%2F`. Reading past that is work the eye should not have to do; the
   * undecoded original stays on the row's title.
   */
  const readableKey = (key) => {
    try {
      return decodeURIComponent(String(key));
    } catch {
      return String(key);
    }
  };

  /**
   * The distinctive tail of a key.
   *
   * Cache keys share their head — a namespace, a method, a version prefix — so a list truncated
   * from the left is a list of identical rows. The last two segments are what tells them apart;
   * the whole key stays on the line below and in the title.
   */
  function shortKey(key) {
    const readable = readableKey(key).replace(/\/+$/, '');
    const parts = readable.split('/').filter(Boolean);
    if (parts.length <= 2) return readable;
    return parts.slice(-2).join('/');
  }

  // --- facets ---------------------------------------------------------------

  /**
   * Filter chips built from the rows themselves.
   *
   * `storage` and `scope` are the app's words, so a hardcoded set would be wrong for every app
   * but the one it was written against. A facet with nothing to choose between — one value, or
   * none — is not drawn: a chip you can only leave on is not a filter.
   */
  function facetsFor(groups) {
    const storages = [...new Set(groups.map((g) => g.latest.storage).filter(Boolean))].sort();
    const scopes = [...new Set(groups.flatMap((g) => g.latest.scopes))].sort();
    const expiries = [...new Set(groups.map((g) => g.latest.expired).filter((e) => e !== null))];
    const kinds = [...new Set(groups.map((g) => g.latest.kind).filter(Boolean))]
      .map((k) => k.toLowerCase()).sort();
    const facets = [];
    if (kinds.length > 1) facets.push({ field: 'kind', label: 'last change', values: kinds });
    if (storages.length > 1) facets.push({ field: 'storage', label: 'storage', values: storages });
    if (scopes.length > 1) facets.push({ field: 'scope', label: 'scope', values: scopes });
    if (expiries.length) {
      facets.push({
        field: 'expired',
        label: 'state',
        values: ['live', 'expired'].filter(
          (v) => expiries.includes(v === 'expired'),
        ),
      });
    }
    return facets.filter((facet) => facet.values.length > 1 || facet.field !== 'expired');
  }

  function renderFacets(facets) {
    const box = $('browser-facets');
    box.innerHTML = '';
    for (const facet of facets) {
      const group = el('span', 'facet');
      group.appendChild(el('span', 'facet-label muted', facet.label));
      for (const value of facet.values) {
        const chip = el('button', 'chip chip-facet', value);
        chip.classList.toggle('on', state.browserFacets[facet.field] === value);
        chip.addEventListener('click', () => {
          // Clicking the chip that is already on clears the facet, so every filter can be
          // undone with the control that set it.
          state.browserFacets[facet.field] =
            state.browserFacets[facet.field] === value ? '' : value;
          renderBrowser();
        });
        group.appendChild(chip);
      }
      box.appendChild(group);
    }
  }

  function groupMatches(group) {
    const text = state.browserFilter.trim().toLowerCase();
    if (text) {
      // Both forms, because both are things a person types here: the readable key is what the
      // list shows, and the raw one is what you paste out of a log or a filename.
      const haystacks = [readableKey(group.key), group.key, group.latest.storageKey || '']
        .map((k) => String(k).toLowerCase());
      if (!haystacks.some((k) => k.includes(text))) return false;
    }
    const facets = state.browserFacets;
    if (facets.kind && String(group.latest.kind).toLowerCase() !== facets.kind) return false;
    if (facets.storage && group.latest.storage !== facets.storage) return false;
    if (facets.scope && !group.latest.scopes.includes(facets.scope)) return false;
    if (facets.expired === 'expired' && group.latest.expired !== true) return false;
    if (facets.expired === 'live' && group.latest.expired !== false) return false;
    return true;
  }

  // --- key list -------------------------------------------------------------

  /** `cleared` and `removed` mean the value is gone; the dot says so before you read the row. */
  const isGone = (row) => row.kind === 'Cleared' || row.kind === 'Removed';

  function keyItem(group, tag) {
    const latest = group.latest;
    const item = el('li', 'bkey');
    item.dataset.key = group.key;
    item.classList.toggle('sel', group.key === state.browserKey);

    // Only where the app said something. A tag that reports no freshness at all — `state`, say —
    // would otherwise get a column of identical hollow dots, which reads as a status and is not
    // one. The column collapses instead.
    const dot = el('span', 'bkey-dot');
    if (isGone(latest)) {
      dot.classList.add('gone');
      dot.title = `${latest.kind.toLowerCase()} — the value is gone`;
    } else if (latest.expired === true) {
      dot.classList.add('expired');
      dot.title = 'expired';
    } else if (latest.expired === false) {
      dot.classList.add('live');
      dot.title = 'live';
    } else if (latest.kind) {
      dot.title = 'the app did not say whether this is still live';
    } else {
      dot.classList.add('bkey-dot-none');
    }
    item.appendChild(dot);

    const text = el('span', 'bkey-text');
    const name = el('span', 'bkey-name', shortKey(group.key));
    name.title = latest.storageKey || group.key;
    text.appendChild(name);

    const meta = [];
    if (latest.storage) meta.push(latest.storage);
    if (latest.scopes.length) meta.push(latest.scopes.join(', '));
    if (group.rows.length > 1) meta.push(`${group.rows.length}×`);
    if (meta.length) text.appendChild(el('span', 'bkey-meta muted', meta.join(' · ')));
    item.appendChild(text);

    item.appendChild(ageNode(latest.ts, 'bkey-age muted mono'));

    item.addEventListener('click', () => {
      state.browserKey = group.key;
      state.browserObservation = null;
      renderBrowser();
    });
    return item;
  }

  function renderBrowser() {
    const tag = state.view;
    if (tag === 'all' || tag === 'network') return;

    const groups = browserGroups(tag);
    renderFacets(facetsFor(groups));

    const shown = groups.filter(groupMatches);
    const list = $('browser-list');
    list.innerHTML = '';
    for (const group of shown) list.appendChild(keyItem(group, tag));

    const empty = $('browser-list-empty');
    empty.hidden = shown.length > 0;
    empty.textContent = groups.length
      ? 'nothing matches these filters'
      : `no ${tag} signals in this session`;

    const observations = groups.reduce((n, g) => n + g.rows.length, 0);
    $('browser-count').textContent = groups.length
      ? `${shown.length}/${groups.length} keys · ${observations} observations`
      : '';

    // A pull is only offered where a provider has been proven to exist, so the button never
    // promises something the app cannot answer.
    const providers = providerNames(tag);
    $('browser-pull').hidden = providers.length === 0;

    const selected = shown.find((g) => g.key === state.browserKey)
      || shown.find((g) => g.key === state.browserKey)
      || null;
    renderBrowserDetail(
      selected || (state.browserKey ? groups.find((g) => g.key === state.browserKey) : null),
      groups,
      tag,
    );
  }

  // --- detail ---------------------------------------------------------------

  /**
   * The empty state, doing the orienting.
   *
   * "select a transaction" occupied more than half the window and told you something you could
   * already see. The session it is sitting in front of has an answer to "what happened here",
   * and the page already holds every number needed to give it — so nothing new is fetched and
   * this works identically on an archive recorded months ago.
   *
   * Every line is a way in, not a readout: a slow call selects it, an error filters to the
   * errors, a tag opens that tag's tab. An empty state that can only be read is a poster.
   */
  function glanceRow(label, value, onClick) {
    const row = el('div', `glance-row${onClick ? ' glance-click' : ''}`);
    row.appendChild(el('span', 'glance-label muted', label));
    row.appendChild(el('span', 'glance-value', value));
    if (onClick) {
      row.tabIndex = 0;
      row.addEventListener('click', onClick);
      row.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); onClick(); }
      });
    }
    return row;
  }

  function renderSessionGlance() {
    const pane = $('detail-empty');
    pane.innerHTML = '';
    /*
     * One rule: this panel ignores the text filter and honours the axis.
     *
     * The filter is a query, and the list and the header count already report it. The axis is a
     * change of *scope* — which part of the session you are looking at — and a panel headed
     * "this stretch" that counts the whole session is the kind of half-true number that makes a
     * reader stop trusting the rest of the page.
     */
    const txns = (state.allTransactions.length ? state.allTransactions : state.transactions)
      .filter((txn) => inRange(txn.mono));
    if (!txns.length && !state.signals.length) {
      pane.className = 'empty muted';
      pane.textContent = 'select a transaction';
      return;
    }
    pane.className = 'glance';

    const failed = txns.filter((t) => t.error || (t.status ?? 0) >= 400);

    const title = el('div', 'glance-title glance-title-row');
    title.appendChild(el('span', null, state.timeRange ? 'This stretch' : 'This session'));
    title.appendChild(bundleButton(
      'for AI',
      () => sessionBundle(),
      'Copy a digest of what is on screen — narrowing on the axis narrows this too',
    ));
    pane.appendChild(title);
    pane.appendChild(glanceRow('calls', String(txns.length)));
    if (failed.length) {
      pane.appendChild(glanceRow(
        'failed',
        `${failed.length} — show them`,
        () => { $('filter').value = 'status>=400 | has:error'; applyFilter(); },
      ));
    }

    // Hosts, because "which backend is this even talking to" is the first question on an app you
    // did not write.
    const hosts = new Map();
    for (const txn of txns) hosts.set(txn.host, (hosts.get(txn.host) || 0) + 1);
    const topHosts = [...hosts].sort((a, b) => b[1] - a[1]).slice(0, 3);
    for (const [host, n] of topHosts) {
      pane.appendChild(glanceRow('host', `${host} · ${n}`, () => {
        $('filter').value = `host:${host}`;
        applyFilter();
      }));
    }

    const slowest = [...txns].filter((t) => t.ms != null).sort((a, b) => b.ms - a.ms).slice(0, 3);
    if (slowest.length) {
      pane.appendChild(el('div', 'glance-title', 'Slowest'));
      for (const txn of slowest) {
        pane.appendChild(glanceRow(fmtMs(txn.ms), `${txn.method} ${txn.path}`, () => select(txn.id)));
      }
    }

    if (state.signals.length) {
      pane.appendChild(el('div', 'glance-title', 'Recorded alongside'));
      const byTag = new Map();
      for (const signal of state.signals) {
        const tag = tagOf(signal);
        byTag.set(tag, (byTag.get(tag) || 0) + 1);
      }
      for (const [tag, n] of [...byTag].sort((a, b) => b[1] - a[1])) {
        pane.appendChild(glanceRow(tag, `${n} — open`, () => selectView(tag)));
      }
    }

    const screen = state.current.find((signal) => tagOf(signal) === 'screen');
    if (screen) pane.appendChild(glanceRow('on screen', screen.name));
  }

  /** The same idea, for a tag browser: what is in here, and what changed most recently. */
  function renderTagGlance(groups, tag) {
    const pane = $('browser-detail');
    pane.className = 'browser-detail glance';
    pane.innerHTML = '';
    if (!groups.length) {
      pane.className = 'browser-detail';
      pane.appendChild(el('div', 'empty muted', 'select a key'));
      return;
    }

    const observations = groups.reduce((n, group) => n + group.rows.length, 0);
    pane.appendChild(el('div', 'glance-title', `${tag} in this session`));
    pane.appendChild(glanceRow('keys', String(groups.length)));
    pane.appendChild(glanceRow('observations', String(observations)));

    // Newest first regardless of the sort toggle: "what changed last" does not reverse.
    const byRecency = [...groups].sort((a, b) => b.latest.mono - a.latest.mono);
    pane.appendChild(el('div', 'glance-title', 'Changed most recently'));
    for (const group of byRecency.slice(0, 5)) {
      const row = glanceRow(
        readableKey(group.key),
        `${group.rows.length}\u00d7`,
        () => { state.browserKey = group.key; state.browserObservation = null; renderBrowser(); },
      );
      row.appendChild(ageNode(group.latest.ts, 'glance-age muted mono'));
      pane.appendChild(row);
    }

    const busiest = [...groups].sort((a, b) => b.rows.length - a.rows.length)[0];
    if (busiest && busiest.rows.length > 1) {
      pane.appendChild(el('div', 'glance-title', 'Changed most often'));
      pane.appendChild(glanceRow(
        readableKey(busiest.key),
        `${busiest.rows.length}\u00d7`,
        () => { state.browserKey = busiest.key; state.browserObservation = null; renderBrowser(); },
      ));
    }
  }

  function renderBrowserDetail(group, groups, tag) {
    const pane = $('browser-detail');
    pane.innerHTML = '';
    if (!group) {
      renderTagGlance(groups || [], tag || state.view);
      return;
    }
    pane.className = 'browser-detail';

    const rows = state.newestFirst ? [...group.rows].reverse() : group.rows;
    const chosen = group.rows.find((r) => r.signalId === state.browserObservation) || group.latest;

    const head = el('div', 'bdetail-head');
    head.appendChild(el('div', 'bdetail-key mono', readableKey(group.key)));
    const raw = chosen.storageKey || group.key;
    // The raw key stays on screen, not just in a tooltip: it is what the app actually stored
    // under, and it is what you paste into a log search.
    if (raw !== readableKey(group.key)) head.appendChild(el('div', 'bdetail-raw muted mono', raw));
    pane.appendChild(head);

    pane.appendChild(metaLine(chosen));
    pane.appendChild(valueBlock(chosen));
    if (group.rows.length > 1) pane.appendChild(historyBlock(group, rows));
  }

  function metaLine(row) {
    const line = el('div', 'bdetail-meta');
    const add = (text, cls) => line.appendChild(el('span', cls || 'bdetail-chip', text));
    if (row.kind) add(row.kind.toLowerCase(), `bdetail-chip bkind bkind-${row.kind.toLowerCase()}`);
    if (row.storage) add(row.storage);
    if (row.scopes.length) add(row.scopes.join(', '));
    if (row.expired !== null) add(row.expired ? 'expired' : 'live', `bdetail-chip ${row.expired ? 'bad' : 'good'}`);
    if (row.bytes != null) add(fmtBytes(row.bytes));
    // Provenance, for the same reason the timeline badges it: a snapshot pushed an hour ago read
    // as the current state of the cache is the mistake this view exists to prevent.
    line.appendChild(
      row.trigger === 'request'
        ? el('span', 'tl-trigger tl-pulled', 'pulled')
        : el('span', 'tl-trigger tl-pushed', 'pushed'),
    );
    line.appendChild(el('span', 'bdetail-clock muted mono', fmtClock(row.ts)));
    line.appendChild(ageNode(row.ts, 'bdetail-age muted mono'));
    return line;
  }

  const valueText = (value) => {
    if (typeof value === 'string') return value;
    try {
      return JSON.stringify(value, null, 2);
    } catch {
      return String(value);
    }
  };

  function valueBlock(row) {
    const box = el('div', 'bvalue');
    const head = el('div', 'bvalue-head');
    head.appendChild(el('span', 'rail-label', 'Value'));
    head.appendChild(el('span', 'spacer'));

    if (row.value === undefined) {
      // Not fetched yet — every tag but cache loads a payload only when someone opens it.
      head.appendChild(el('span', 'muted', 'loading…'));
      box.appendChild(head);
      const signal = state.signals.find((s) => s.id === row.signalId);
      loadPayload(signal).then(renderBrowser);
      return box;
    }

    const text = row.value === null ? null : valueText(row.value);
    if (text !== null) {
      const copy = el('button', 'btn btn-sm', 'copy');
      copy.addEventListener('click', () => {
        navigator.clipboard.writeText(text).then(() => {
          copy.textContent = 'copied';
          setTimeout(() => (copy.textContent = 'copy'), 1200);
        });
      });
      head.appendChild(copy);
    }
    box.appendChild(head);

    box.appendChild(
      text === null
        ? el('div', 'bvalue-none muted', isGone(row)
          ? 'no value — this observation records the entry going away'
          : 'this signal carried no payload')
        : el('pre', 'bvalue-body mono', text),
    );
    return box;
  }

  function historyBlock(group, rows) {
    const box = el('div', 'bhistory');
    box.appendChild(el('div', 'rail-label', `History (${group.rows.length})`));
    const list = el('ul', 'bhistory-list');
    const chosenId = state.browserObservation || group.latest.signalId;
    for (const row of rows) {
      const item = el('li', 'bhistory-item');
      item.classList.toggle('sel', row.signalId === chosenId);
      item.appendChild(el('span', 'bhistory-clock mono muted', fmtClock(row.ts)));
      item.appendChild(el('span', 'bhistory-kind', row.kind ? row.kind.toLowerCase() : 'observed'));
      if (row.bytes != null) item.appendChild(el('span', 'bhistory-bytes muted mono', fmtBytes(row.bytes)));
      item.addEventListener('click', () => {
        state.browserObservation = row.signalId;
        renderBrowser();
      });
      list.appendChild(item);
    }
    box.appendChild(list);
    return box;
  }

  // --- pulling --------------------------------------------------------------

  /**
   * Which names a "pull latest" should ask for.
   *
   * The daemon cannot enumerate an app's providers — it only learns a name once one has answered —
   * so this infers them from what the session already holds, and is deliberately conservative:
   *
   *   - anything that has answered a pull before is a provider, by proof;
   *   - anything that describes a whole cache (`items`) is the shape a provider answers with.
   *
   * Per-entry change rows are excluded, so a session with fifty cached keys does not fire fifty
   * doomed requests at the app.
   */
  function providerNames(tag) {
    const names = new Set();
    for (const signal of signalsForTag(tag)) {
      if (signal.trigger === 'request') {
        names.add(signal.name);
        continue;
      }
      const payload = state.payloads.get(signal.id);
      if (payload && typeof payload === 'object' && Array.isArray(payload.items)) {
        names.add(signal.name);
      }
    }
    return [...names];
  }

  async function pullProviders() {
    const tag = state.view;
    const status = $('browser-pull-status');
    const names = providerNames(tag);
    if (!names.length) {
      status.textContent =
        `no ${tag} provider has been seen yet — the app registers one with Inspector.registerProvider`;
      return;
    }
    $('browser-pull').disabled = true;
    status.classList.remove('browser-status-error');
    status.textContent = `pulling ${names.length}…`;
    const failures = [];
    for (const name of names) {
      try {
        const res = await fetch(
          `/api/sessions/${encodeURIComponent(state.sessionId || 'latest')}/signals/request`,
          {
            method: 'POST',
            headers: { 'X-Inspector-Control': '1', 'Content-Type': 'application/json' },
            body: JSON.stringify({ tag, name }),
          },
        );
        if (!res.ok) {
          // The app's own message names what it does have registered; surfacing it verbatim is
          // more use than "pull failed".
          const body = await res.text().catch(() => '');
          failures.push(`${name}: ${body.slice(0, 200) || res.status}`);
        }
      } catch (e) {
        failures.push(`${name}: ${e.message}`);
      }
    }
    $('browser-pull').disabled = false;
    if (failures.length) {
      status.textContent = failures.join(' · ');
      status.classList.add('browser-status-error');
      return;
    }
    status.textContent = `pulled ${names.length} at ${fmtClock(new Date().toISOString())}`;
    // The pulled rows arrive over the live socket; re-read so they are on screen either way.
    await loadSignals();
    await loadPayloadsFor(tag);
    renderBrowser();
  }

  /**
   * Spans for observations that claim to stay true.
   *
   * A `screen` signal is true at an instant; a `cache` snapshot claims to be true from its `mono`
   * until the next observation of the same `(tag, name)`. That difference is not in the schema
   * because it is derivable - consecutive rows for a key define the intervals - so it is computed
   * here, where it is a rendering rule.
   */
  function spanEndsFor(signals) {
    const ends = new Map();
    const byKey = new Map();
    for (const s of [...signals].sort((a, b) => a.mono - b.mono)) {
      const key = `${s.tag} ${s.name}`;
      const previous = byKey.get(key);
      if (previous) ends.set(previous.id, s.mono);
      byKey.set(key, s);
    }
    return ends;
  }

  /**
   * How many adjacent identical observations it takes before they are worth collapsing.
   *
   * Two is not clutter and hiding it behind a twisty costs more than it saves. Three is where a
   * run starts pushing other lanes off the screen.
   */
  const RUN_THRESHOLD = 3;

  /** What makes two adjacent rows "the same thing happening again". Transactions never group. */
  const runKeyOf = (entry) =>
    entry.kind === 'signal' ? `${entry.signal.tag}\u0000${entry.signal.name}` : null;

  /**
   * Groups *consecutive* identical observations.
   *
   * A state holder that emits on every keystroke produces dozens of adjacent rows differing only
   * in a payload you cannot see from the row — one real session had 48 in a row — and they push
   * the traffic the timeline exists to correlate clean off the screen.
   *
   * Only adjacent rows group. A run interrupted by a call or a screen change is information: it
   * says the state settled, something else happened, and it moved again. Collapsing across that
   * gap would erase the very ordering the merged view is for.
   */
  function timelineRuns(entries) {
    const runs = [];
    for (const entry of entries) {
      const key = runKeyOf(entry);
      const open = runs[runs.length - 1];
      if (key !== null && open && open.key === key) open.entries.push(entry);
      else runs.push({ key, entries: [entry] });
    }
    return runs;
  }

  /** Stable across re-renders: signal ids are, and the first of a run does not move. */
  const runIdOf = (run) => `${run.key}\u0000${run.entries[0].signal.id}`;

  function renderTimeline() {
    if (state.view !== 'all') return;
    const root = $('timeline');
    root.innerHTML = '';

    const ends = spanEndsFor(state.signals);
    const entries = [
      ...state.transactions.map((txn) => ({ kind: 'txn', mono: txn.mono, txn })),
      ...state.signals.map((signal) => ({ kind: 'signal', mono: signal.mono, signal })),
      ...state.markers.map((marker) => ({ kind: 'marker', mono: marker.mono, marker })),
    ].filter((entry) => inRange(entry.mono)).sort((a, b) => a.mono - b.mono);

    if (state.newestFirst) entries.reverse();

    $('timeline-empty').hidden = entries.length > 0;

    // Spans are scaled against the session's own duration, not a fixed divisor. A fixed one
    // made a cache snapshot held for 230ms draw narrower than an instantaneous screen event,
    // which reads as the opposite of what it means.
    const monos = entries.map((e) => e.mono);
    const first = monos.length ? Math.min(...monos) : 0;
    const last = monos.length ? Math.max(...monos) : 0;
    const span = Math.max(1, last - first);
    for (const run of timelineRuns(entries)) {
      const collapsible = run.entries.length >= RUN_THRESHOLD;
      if (collapsible && !state.expandedRuns.has(runIdOf(run))) {
        root.appendChild(runRow(run));
        continue;
      }
      if (collapsible) root.appendChild(runRow(run, { expanded: true }));
      for (const entry of run.entries) root.appendChild(timelineRow(entry, ends, last, span));
    }

    if (state.liveTail) {
      const pane = root.parentElement;
      pane.scrollTop = state.newestFirst ? 0 : pane.scrollHeight;
    }
  }

  /**
   * One row standing in for a run of identical observations, or the header above an expanded one.
   *
   * It reports the count and the wall of time the run covers, which is the part a collapsed run
   * must not lose: "48 times" and "48 times over 23 seconds" mean different things about the app.
   */
  function runRow(run, { expanded = false } = {}) {
    const first = run.entries[0].signal;
    const lane = laneFor(first.tag);
    const node = el('div', `tl-row tl-run lane-${lane}${expanded ? ' tl-run-open' : ''}`);
    node.dataset.kind = 'run';
    node.dataset.lane = lane;
    node.dataset.tag = first.tag;
    node.dataset.runId = runIdOf(run);
    node.tabIndex = 0;

    node.appendChild(el('span', 'tl-twisty', expanded ? '\u25be' : '\u25b8'));
    node.appendChild(el('span', 'tl-tag', first.tag));
    node.appendChild(el('span', 'tl-name', first.name));
    node.appendChild(el('span', 'tl-run-count mono', `\u00d7${run.entries.length}`));

    const monos = run.entries.map((entry) => entry.mono);
    const from = Math.min(...monos);
    const to = Math.max(...monos);
    const held = el('span', 'tl-mono muted mono', from === to ? `${from}ms` : `${from}\u2013${to}ms`);
    held.title = expanded
      ? 'collapse these observations'
      : `${run.entries.length} observations over ${to - from} ms — click to expand`;
    node.appendChild(held);

    const toggle = () => {
      const id = runIdOf(run);
      if (state.expandedRuns.has(id)) state.expandedRuns.delete(id);
      else state.expandedRuns.add(id);
      renderTimeline();
    };
    node.addEventListener('click', toggle);
    node.addEventListener('keydown', (event) => {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        toggle();
      }
    });
    return node;
  }

  function timelineRow(entry, ends, lastMono, sessionSpan) {
    if (entry.kind === 'marker') {
      const node = el('div', 'marker-divider', entry.marker.label);
      node.dataset.kind = 'marker';
      return node;
    }

    if (entry.kind === 'txn') {
      const node = rowFor(entry.txn);
      node.classList.add('tl-row');
      node.dataset.kind = 'txn';
      node.dataset.lane = 'traffic';
      return node;
    }

    const signal = entry.signal;
    const lane = laneFor(signal.tag);
    // Deliberately not `.row`: that class means "a transaction row", and `select()` matches
    // on dataset.id across all of them. Signal and transaction ids come from the same 8-hex
    // generator, so sharing the class would let a collision highlight the wrong thing.
    const node = el('div', `tl-row tl-signal lane-${lane}`);
    node.dataset.kind = 'signal';
    node.dataset.lane = lane;
    node.dataset.tag = signal.tag;
    node.dataset.id = signal.id;
    node.tabIndex = 0;

    // A point observation gets a dot; an interval claim gets a bar, drawn to the next observation
    // of the same key or to the end of what we have.
    const end = ends.get(signal.id);
    const isSpan = lane === 'cache' || lane === 'session';
    const glyph = el('span', `tl-glyph ${isSpan ? 'tl-span' : 'tl-point'}`);
    if (isSpan) {
      // Never narrower than a point glyph: an interval that looks smaller than an instant is
      // actively misleading.
      const held = (end ?? lastMono) - signal.mono;
      const width = 10 + Math.round(Math.min(1, held / sessionSpan) * 90);
      glyph.style.width = `${width}px`;
      glyph.title = end === undefined
        ? 'still current as far as this session knows'
        : `held for ${end - signal.mono} ms`;
    }
    node.appendChild(glyph);

    node.appendChild(el('span', 'tl-tag', signal.tag));
    node.appendChild(el('span', 'tl-name', signal.name));

    // Provenance, always visible. An app-start snapshot reported as live state is the exact
    // failure `trigger` exists to prevent, so the UI never leaves it to be inferred.
    const trigger = signal.trigger === 'request' ? 'pulled' : 'pushed';
    const badge = el('span', `tl-trigger tl-${trigger}`, trigger);
    badge.title = trigger === 'pulled'
      ? 'the host asked for this value'
      : 'the app pushed this and has not re-read it since';
    node.appendChild(badge);

    if (signal.dataRef) node.appendChild(el('span', 'tl-bytes muted mono', fmtBytes(signal.bytes)));
    node.appendChild(el('span', 'tl-mono muted mono', `${signal.mono}ms`));

    node.addEventListener('click', () => selectSignal(signal.id));
    return node;
  }

  /**
   * The rail panel answering "what screen, what is cached" without reading any rows.
   *
   * The last observation of each `(tag, name)`, whatever tab you are on — which is the point of
   * it being in the rail rather than inside one view.
   */
  function renderCurrent() {
    const list = $('current');
    const rows = state.current;
    $('now-strip').hidden = state.view !== 'all' || rows.length === 0;
    list.innerHTML = '';

    // Collapsed, the strip has to earn its line: how much there is, and how stale the oldest of
    // it is — which is the question the panel exists to answer and the one a count alone dodges.
    const oldest = rows.reduce(
      (worst, signal) => Math.max(worst, ageOf(signal.ts) ?? 0),
      0,
    );
    const byTag = new Map();
    for (const signal of rows) byTag.set(signal.tag, (byTag.get(signal.tag) || 0) + 1);
    const parts = [...byTag].map(([tag, n]) => `${n} ${tag}`);
    $('now-summary-text').textContent = rows.length
      ? `${parts.join(' · ')} — oldest ${fmtAge(oldest)}`
      : '';

    for (const signal of rows) {
      const item = el('li', 'current-item');
      item.dataset.tag = signal.tag;
      item.appendChild(el('span', 'tl-tag', signal.tag));
      item.appendChild(el('span', 'current-name', signal.name));
      item.appendChild(
        signal.trigger === 'request'
          ? el('span', 'tl-trigger tl-pulled', 'pulled')
          : el('span', 'tl-trigger tl-pushed', 'pushed'),
      );
      item.appendChild(ageNode(signal.ts, 'current-age muted'));
      item.addEventListener('click', () => selectSignal(signal.id));
      list.appendChild(item);
    }
  }

  /** Renders one signal into the detail pane, fetching its payload on demand. */
  async function selectSignal(id) {
    state.selectedSignalId = id;
    state.selectedId = null;
    const signal =
      state.signals.find((s) => s.id === id) || state.current.find((s) => s.id === id);
    if (!signal) return;

    for (const node of document.querySelectorAll('.tl-signal.sel')) node.classList.remove('sel');
    const row = document.querySelector(`.tl-signal[data-id="${id}"]`);
    if (row) row.classList.add('sel');

    const detail = $('detail');
    $('detail-empty').hidden = true;
    detail.hidden = false;
    detail.innerHTML = '';
    openDrawer();

    const head = el('div', 'detail-head');
    head.appendChild(el('span', 'tl-tag', signal.tag));
    head.appendChild(el('span', 'detail-title', signal.name));
    detail.appendChild(head);

    const trigger = signal.trigger === 'request' ? 'pulled on demand' : 'pushed by the app';
    detail.appendChild(
      el('div', 'detail-meta muted mono', `${signal.ts} - mono ${signal.mono}ms - ${trigger}`),
    );

    if (signal.trigger !== 'request') {
      detail.appendChild(
        el(
          'div',
          'detail-note muted',
          'Pushed by the app at that moment. It has not been re-read since, so this is not ' +
            'necessarily what the app holds now.',
        ),
      );
    }

    if (!signal.dataRef) {
      detail.appendChild(el('div', 'empty muted', 'no payload was captured for this signal'));
      return;
    }

    const pre = el('pre', 'body');
    pre.textContent = 'loading...';
    detail.appendChild(pre);

    const url =
      `/api/sessions/${encodeURIComponent(state.sessionId)}` +
      `/signals/${encodeURIComponent(id)}/data`;
    try {
      const res = await fetch(url);
      const text = await res.text();
      // Payloads are captured verbatim and never redacted; this shows what was recorded.
      pre.textContent = prettyJson(text);
    } catch (e) {
      pre.textContent = `could not read the payload: ${e.message}`;
    }
  }



  /** Re-reads the current-state panel. Cheap, and the derivation lives on the daemon. */
  async function refreshCurrent() {
    if (!state.sessionId) return;
    const id = encodeURIComponent(state.sessionId);
    state.current = await api(`/api/sessions/${id}/current`).catch(() => state.current);
    renderCurrent();
  }

  // --- interaction --------------------------------------------------------

  function select(id) {
    state.selectedId = id;
    for (const row of document.querySelectorAll('.row, .wf-row')) {
      row.classList.toggle('selected', row.dataset.id === id);
    }
    const txn = state.transactions.find((t) => t.id === id);
    if (txn) renderDetail(txn);
  }

  function move(delta) {
    // Display order, so j always moves down the screen regardless of sort direction.
    const rows = orderedRows();
    if (!rows.length) return;
    const current = rows.findIndex((t) => t.id === state.selectedId);
    const next = Math.min(rows.length - 1, Math.max(0, (current === -1 ? 0 : current + delta)));
    select(rows[next].id);
    document.querySelector('.row.selected')?.scrollIntoView({ block: 'nearest' });
  }

  let filterTimer = null;
  function applyFilter() {
    clearTimeout(filterTimer);
    filterTimer = setTimeout(() => {
      state.filter = $('filter').value;
      for (const chip of document.querySelectorAll('#chips .chip, #endpoint-chips .chip')) {
        chip.classList.toggle('active', chip.dataset.filter === state.filter);
      }
      loadTransactions();
    }, 150);
  }

  // --- server control -----------------------------------------------------

  /**
   * Stop and restart the daemon from the page.
   *
   * `stop` is genuinely destructive to the session in progress, so it confirms first. `restart`
   * does not: it is the recovery action, and a confirm dialog on the thing you reach for when
   * something is already wrong is just friction.
   */
  async function control(action) {
    const res = await fetch(`/api/server/${action}`, {
      method: 'POST',
      // Not decoration: the daemon rejects control requests without it, which is what stops any
      // other page in the browser from reaching a loopback daemon that has no authentication.
      headers: { 'X-Inspector-Control': '1' },
    });
    if (!res.ok) {
      let message = `${res.status}`;
      try { message = (await res.json()).error || message; } catch { /* keep the status */ }
      throw new Error(message);
    }
  }

  function showServerBanner(text, pending) {
    const node = $('server-banner');
    node.hidden = !text;
    node.textContent = text || '';
    node.classList.toggle('pending', !!pending);
    node.classList.remove('big');
    document.body.classList.remove('stopped');
  }

  /**
   * The daemon was stopped on purpose, and every number below it is now history.
   *
   * A stopped daemon otherwise looks exactly like an idle one: the rows are still there, the
   * counts still add up, and nothing says none of it will move again. So the banner says when the
   * last capture was — with an age that ticks, because "4m ago" becoming "40m ago" is the point —
   * and the page dims under it.
   *
   * There is no restart button, and that is not an omission: the process that would receive the
   * request is the one that just exited. The page redials on its own, so running `inspector
   * serve` is the whole of the fix and the banner clears itself when the daemon is back.
   */
  function showStoppedBanner() {
    const node = $('server-banner');
    node.replaceChildren();
    node.hidden = false;
    node.classList.remove('pending');
    node.classList.add('big');
    document.body.classList.add('stopped');

    node.appendChild(el('span', 'banner-title', 'daemon stopped'));
    const text = el('span', 'banner-text');
    text.append('nothing is being recorded');
    const last = lastCapture();
    if (last) {
      text.append(` — ${last.what} ${fmtClock(last.ts).slice(0, 8)}, `);
      text.appendChild(ageNode(last.ts, 'banner-age'));
    }
    text.append('. Everything below is stale.');
    node.appendChild(text);
    node.appendChild(el('span', 'spacer'));
    const hint = el('span', 'banner-hint muted');
    hint.append('run ');
    hint.appendChild(el('code', null, 'inspector serve'));
    hint.append(' to start it again — this page reconnects on its own');
    node.appendChild(hint);
  }

  /**
   * What "last capture" can honestly name. A row this page watched arrive is a capture from any
   * session, so it wins. Failing that, the newest row of the session on screen is still true, but
   * only of that session — and it is labelled as such rather than passed off as the daemon's.
   */
  function lastCapture() {
    if (state.lastCaptureTs) return { ts: state.lastCaptureTs, what: 'last capture' };
    const newest = [...state.transactions, ...state.signals]
      .map((r) => r.ts)
      .filter(Boolean)
      .sort()
      .pop();
    return newest ? { ts: newest, what: 'newest row in this session' } : null;
  }

  /** The page's own connection to the daemon, which is what the dot beside its controls means. */
  function setConn(kind) {
    const node = $('conn');
    node.dataset.state = kind;
    node.querySelector('.conn-label').textContent = kind;
  }

  async function requestStop() {
    setControlsEnabled(false);
    try {
      await control('stop');
      state.serverState = 'stopped';
      setConn('stopped');
      showStoppedBanner();
    } catch (e) {
      setControlsEnabled(true);
      showServerBanner(`Could not stop the daemon: ${e.message}`, false);
    }
  }

  async function requestRestart() {
    setControlsEnabled(false);
    try {
      await control('restart');
      state.serverState = 'restarting';
      showServerBanner('Restarting…', true);
      // The socket drops, reconnects on its own, and its onopen clears this banner. Nothing here
      // polls: the live connection already knows when the daemon is back.
    } catch (e) {
      setControlsEnabled(true);
      showServerBanner(`Could not restart the daemon: ${e.message}`, false);
    }
  }

  function setControlsEnabled(enabled) {
    $('server-stop').disabled = !enabled;
    $('server-restart').disabled = !enabled;
  }

  // --- replay: editing before sending ----------------------------------------------------------

  /*
   * `REPLAY.md` step 3. The daemon has accepted edits since replay shipped — `ReplayRequest` takes
   * `method`, `url`, `headers` and `body`, and `Replayer` applies them *before* asking the app to
   * sign, which `ReplayTest.an edited path is what gets signed` pins. Nothing exposed any of it.
   *
   * Three of the daemon's own refusal messages say "Edit the body to supply it", which until now
   * named a control that did not exist. That is the case this is most useful for: a request whose
   * body was truncated, streamed or outside the capture allowlist cannot be replayed as captured,
   * and supplying the body by hand is the only way to run it at all.
   */

  /** Hop-by-hop headers, mirroring `HOP_BY_HOP_HEADERS` in `Replay.kt`. Keep them in step. */
  const HOP_BY_HOP = new Set([
    'host', 'content-length', 'connection', 'keep-alive', 'transfer-encoding',
    'te', 'trailer', 'upgrade', 'proxy-authorization', 'proxy-authenticate', 'accept-encoding',
  ]);

  /**
   * The header set the editor opens with.
   *
   * The same filtering the daemon applies when no headers are sent, applied here instead — because
   * the moment the UI sends an explicit map, `Replayer` uses it *verbatim* and its own filtering
   * never runs. Seeding from the raw capture would therefore quietly reintroduce `Content-Length`
   * and `Host` from a request whose body the user is about to change.
   */
  function replayableHeaderLines(txn) {
    return Object.entries(txn.reqHeaders || {})
      .filter(([name]) => !HOP_BY_HOP.has(name.toLowerCase()))
      .map(([name, values]) => `${name}: ${values.join(', ')}`)
      .join('\n');
  }

  /**
   * Parses the header box back into a map.
   *
   * A line that is not `Name: value` is reported rather than skipped. Silently dropping one would
   * send a request missing a header the user believes they set, and the failure would look like a
   * server problem.
   */
  function parseHeaderLines(text) {
    const headers = {};
    const bad = [];
    for (const raw of text.split('\n')) {
      const line = raw.trim();
      if (!line) continue;
      const at = line.indexOf(':');
      if (at <= 0) { bad.push(line); continue; }
      headers[line.slice(0, at).trim()] = line.slice(at + 1).trim();
    }
    return { headers, bad };
  }

  /** Why the captured body is not in the box, when it is not. */
  function missingBodyNote(txn, body) {
    if (body !== null) return null;
    if (!txn.reqBytes) return null;
    const size = fmtBytes(txn.reqBytes);
    if (txn.reqBodyTruncated) {
      return `The captured body was cut at the capture cap (${size} total), so replaying it as ` +
        'captured is refused — supply it here in full to run this request.';
    }
    if (txn.reqBodyOmitted === 'streaming') {
      return `${size} was streamed and never buffered, by design. There is nothing captured to ` +
        'replay; supply the body here.';
    }
    if (txn.reqBodyOmitted === 'contentType') {
      return `${size} was not captured — its content type is outside the capture allowlist. ` +
        'Supply the body here, or enable captureAllBodies and re-capture.';
    }
    return `${size} was recorded on device but no body reached the archive.`;
  }

  function toggleReplayEditor(txn, pane, reqBody) {
    const existing = pane.querySelector('.replay-editor');
    if (existing) { existing.remove(); return; }

    const form = el('div', 'replay-editor');
    const note = missingBodyNote(txn, reqBody);

    const field = (label, node) => {
      const wrap = el('div', 'replay-field');
      wrap.appendChild(el('label', 'replay-label', label));
      wrap.appendChild(node);
      return wrap;
    };

    const method = el('input', 'replay-input replay-method');
    method.value = txn.method;
    method.spellcheck = false;

    const url = el('input', 'replay-input');
    url.value = urlOf(txn);
    url.spellcheck = false;

    const headers = el('textarea', 'replay-input replay-textarea');
    headers.value = replayableHeaderLines(txn);
    headers.spellcheck = false;
    headers.rows = 6;

    const body = el('textarea', 'replay-input replay-textarea');
    // As captured, so sending without touching anything sends what was sent.
    body.value = reqBody || '';
    body.spellcheck = false;
    body.rows = 6;

    const resign = el('input');
    resign.type = 'checkbox';
    resign.checked = true;
    const resignLabel = el('label', 'replay-resign');
    resignLabel.appendChild(resign);
    resignLabel.appendChild(el('span', null, ' regenerate per-request headers (re-sign)'));
    resignLabel.title =
      'The app signs what is actually sent, after these edits are applied. Turn this off only to ' +
      'reproduce the captured headers exactly.';

    form.appendChild(field('Method', method));
    form.appendChild(field('URL', url));
    form.appendChild(field('Headers — one Name: value per line', headers));
    if (note) form.appendChild(el('div', 'replay-note', note));
    form.appendChild(field('Body', body));
    form.appendChild(resignLabel);

    const problem = el('div', 'replay-error');
    problem.hidden = true;
    form.appendChild(problem);

    const send = el('button', 'btn', 'send');
    const cancel = el('button', 'btn btn-quiet', 'cancel');
    cancel.onclick = () => form.remove();
    const actions = el('div', 'replay-actions');
    actions.appendChild(send);
    actions.appendChild(cancel);
    form.appendChild(actions);

    send.onclick = () => {
      const parsed = parseHeaderLines(headers.value);
      if (parsed.bad.length) {
        problem.hidden = false;
        problem.textContent =
          `not Name: value — ${parsed.bad.slice(0, 3).join(' | ')}${parsed.bad.length > 3 ? ' …' : ''}`;
        return;
      }
      problem.hidden = true;
      runReplay(txn, pane, send, {
        method: method.value.trim() || txn.method,
        url: url.value.trim(),
        headers: parsed.headers,
        // Omitted only when there is nothing to send and nothing was sent. Present — even empty —
        // it counts as an override, which is what lifts the daemon's refusal on a body it could
        // not capture, and is also how somebody deliberately sends an empty one.
        body: body.value === '' && !txn.reqBytes ? null : body.value,
        resign: resign.checked,
      });
    };

    // Above the tabs, where the request it describes is, rather than below the response.
    pane.insertBefore(form, pane.querySelector('.dtabs'));
  }

  // --- replay --------------------------------------------------------------------------------

  /**
   * Re-sends a captured request and renders what came back.
   *
   * Re-signing is on by default because the requests worth replaying are usually signed, and a
   * verbatim replay of a signed request fails at the server in a way that reads as a backend fault
   * rather than as the tool having replayed single-use headers.
   */
  async function runReplay(txn, pane, button, edits = null) {
    const previous = button.textContent;
    button.disabled = true;
    button.textContent = 'replaying…';

    let panel = pane.querySelector('.replay-result');
    if (!panel) {
      /*
       * Anchored on `.dtabs`, which is a direct child of the pane.
       *
       * This used to anchor on `pane.querySelector('.section-title')`, and `querySelector`
       * searches the whole subtree: on a row with no redaction banner and no attempt chain — an
       * ordinary row, which is most of them — the first `.section-title` is the one inside a
       * `.dtab-panel`, a *grandchild*. `insertBefore` then throws NotFoundError and the replay
       * never ran. It worked on exactly the rows that happen to have a direct-child section title
       * above the tabs, which is why it survived: those are the interesting rows you reach for
       * when testing replay by hand.
       *
       * Nothing caught it because this script never pressed the button. It does now.
       */
      const anchor = pane.querySelector('.dtabs');
      panel = el('div', 'replay-result');
      // `insertBefore(node, null)` appends, so a pane without tabs still works.
      pane.insertBefore(el('div', 'section-title', 'Replay'), anchor);
      pane.insertBefore(panel, anchor);
    }
    panel.textContent = '';

    try {
      const res = await fetch('/api/replay', {
        method: 'POST',
        headers: { 'X-Inspector-Control': '1', 'Content-Type': 'application/json' },
        body: JSON.stringify({
          txnId: txn.id,
          session: state.sessionId || 'latest',
          resign: true,
          // Spread last so an editor's `resign: false` wins. With no edits this is exactly the
          // request the plain replay button has always sent.
          ...(edits || {}),
        }),
      });
      const result = await res.json();
      renderReplayResult(panel, result, edits !== null);
    } catch (e) {
      panel.appendChild(el('div', 'replay-error', `could not reach the daemon: ${e.message}`));
    } finally {
      button.disabled = false;
      button.textContent = previous;
    }
  }

  function renderReplayResult(panel, result, edited = false) {
    if (!result.ok) {
      panel.appendChild(el('div', 'replay-error', result.error || 'replay failed'));
      if (result.diagnosis) panel.appendChild(el('div', 'replay-diagnosis', result.diagnosis));
      return;
    }

    const head = el('div', 'replay-head');
    head.appendChild(el('span', `status s${statusClass(result.status)}`, result.status));
    head.appendChild(el('span', 'muted', fmtMs(result.ms)));
    // Which request this result belongs to. A 200 under a row whose capture was a 500 is a
    // different fact depending on whether anything was changed, and the panel sits directly
    // above the captured request's own tabs.
    if (edited) head.appendChild(el('span', 'replay-edited', 'edited'));
    panel.appendChild(head);

    // Showing which headers the app regenerated is the difference between trusting the result and
    // wondering whether it went out with the captured values.
    if (result.resignedHeaders && result.resignedHeaders.length) {
      panel.appendChild(el(
        'div',
        'replay-resigned muted',
        `regenerated by the app: ${result.resignedHeaders.join(', ')}`,
      ));
    } else {
      panel.appendChild(el('div', 'replay-resigned muted', 'sent as captured — nothing regenerated'));
    }

    // A diagnosis can accompany a successful send: a 401 is a completed request.
    if (result.diagnosis) panel.appendChild(el('div', 'replay-diagnosis', result.diagnosis));

    if (result.body) {
      const body = el('pre', 'body', result.body);
      if (result.bodyTruncated) body.appendChild(el('div', 'muted', '… truncated'));
      panel.appendChild(body);
    }
  }

  // --- settings: duplicate detection ------------------------------------------------------------

  function applyDuplicateWindow(valueMs) {
    const ms = Number.isFinite(valueMs) && valueMs >= 0 ? Math.round(valueMs) : 3000;
    state.duplicateWindowMs = ms;
    localStorage.setItem('inspector.duplicateWindowMs', String(ms));
    $('dup-window').value = ms;
    // The unfiltered set, matching what the list actually highlights. Counting the filtered rows
    // instead made the summary read 0 while the list showed two tinted rows behind the dialog.
    $('dup-summary').textContent = ms === 0
      ? 'off'
      : `${computeDuplicates(state.allTransactions, ms).size} row(s) in this session`;
    renderList();
  }

  // --- settings: endpoint shortcuts -------------------------------------------------------------

  function applyEndpointLimit(value) {
    const limit = Number.isFinite(value) && value >= 0 ? Math.round(value) : 10;
    state.endpointLimit = limit;
    localStorage.setItem('inspector.endpointChipLimit', String(limit));
    $('endpoint-limit').value = limit;

    const total = new Set(
      state.allTransactions.map((t) => lastSegment(t.path)).filter(Boolean),
    ).size;
    $('endpoint-summary').textContent = limit === 0
      ? 'hidden'
      : `showing ${Math.min(limit, total)} of ${total} in this session`;
    renderEndpointChips();
  }

  // --- settings: the MCP server ----------------------------------------------------------------

  /**
   * There is no start button on purpose.
   *
   * The MCP server reads stdio and stops at EOF, so a process the daemon spawned would have no
   * client on the other end. The editor owns that lifecycle. What is useful is proving the binary
   * answers, and killing a wedged one — the latter is just the peer list above.
   */
  let mcpInfo = null;

  async function loadMcp() {
    try {
      mcpInfo = await api('/api/mcp');
      const shown = mcpInfo.launcher
        ? `${mcpInfo.launcher} mcp --data ${mcpInfo.dataDir}`
        : [mcpInfo.command, ...(mcpInfo.args || [])].join(' ');
      $('mcp-command').textContent = shown;
    } catch (e) {
      $('mcp-command').textContent = `could not read MCP details: ${e.message}`;
    }
  }

  async function probeMcp() {
    const button = $('mcp-probe');
    const status = $('mcp-status');
    button.disabled = true;
    status.classList.remove('error');
    status.textContent = 'starting a throwaway server…';
    try {
      const res = await fetch('/api/mcp/probe', {
        method: 'POST',
        headers: { 'X-Inspector-Control': '1' },
      });
      const result = await res.json();
      if (result.ok) {
        status.textContent =
          `answered: ${result.toolCount} tools${result.serverName ? ` (${result.serverName})` : ''}`;
      } else {
        status.classList.add('error');
        status.textContent = result.error || 'no answer';
      }
    } catch (e) {
      status.classList.add('error');
      status.textContent = `could not reach the daemon: ${e.message}`;
    } finally {
      button.disabled = false;
    }
  }

  // --- settings: sibling inspector processes ------------------------------------------------

  /**
   * Lists other inspector processes and offers to kill them.
   *
   * The daemon identifies them by argv token, never by a substring of the command line, so the
   * two traps documented in docs/DAEMON.md are unreachable from here. The page just renders what
   * it is told.
   */
  async function loadPeers() {
    const list = $('peers');
    setPeersStatus('');
    try {
      const peers = await api('/api/peers');
      renderPeers(peers);
    } catch (e) {
      list.textContent = '';
      list.appendChild(el('div', 'muted', `could not list processes: ${e.message}`));
    }
  }

  function setPeersStatus(text, isError) {
    const node = $('peers-status');
    node.textContent = text || '';
    node.classList.toggle('error', !!isError);
  }

  function uptimeOf(startedEpochMs) {
    if (!startedEpochMs) return '';
    const seconds = Math.max(0, Math.round((Date.now() - startedEpochMs) / 1000));
    if (seconds < 60) return `${seconds}s`;
    if (seconds < 3600) return `${Math.floor(seconds / 60)}m`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)}h`;
    return `${Math.floor(seconds / 86400)}d`;
  }

  function renderPeers(peers) {
    const list = $('peers');
    list.textContent = '';

    if (!peers.length) {
      list.appendChild(el('div', 'muted', 'no inspector processes found'));
      return;
    }

    for (const peer of peers) {
      const row = el('div', 'peer');
      row.appendChild(el('span', `peer-role peer-role-${peer.role}`, peer.role));
      row.appendChild(el('span', 'peer-pid mono', `pid ${peer.pid}`));

      // `mcp` speaks over stdio and holds no port. Showing that plainly is the point: it is the
      // process people kill by accident.
      row.appendChild(el('span', 'peer-port mono muted',
        peer.port == null ? 'stdio' : `:${peer.port}`));

      const uptime = uptimeOf(peer.startedEpochMs);
      if (uptime) row.appendChild(el('span', 'peer-uptime muted', `up ${uptime}`));

      row.appendChild(el('span', 'spacer'));

      if (peer.self) {
        row.appendChild(el('span', 'peer-self', 'this daemon'));
        const hint = el('span', 'muted', 'use stop');
        hint.title = 'Stopping this daemon goes through Stop, which replies before shutting down.';
        row.appendChild(hint);
      } else {
        const kill = el('button', 'btn btn-sm btn-danger', 'kill');
        kill.title = `Terminate pid ${peer.pid}`;
        kill.addEventListener('click', () => killPeer(peer, false));
        row.appendChild(kill);
      }

      list.appendChild(row);
    }
  }

  async function killPeer(peer, force) {
    const what = `${peer.role} (pid ${peer.pid})`;
    if (!force && !confirm(`Kill ${what}?`)) return;

    setPeersStatus(`killing ${what}…`);
    try {
      const res = await fetch(`/api/peers/${peer.pid}/kill${force ? '?force=true' : ''}`, {
        method: 'POST',
        headers: { 'X-Inspector-Control': '1' },
      });

      if (!res.ok) {
        let message = `${res.status}`;
        try { message = (await res.json()).error || message; } catch { /* keep the status */ }
        setPeersStatus(message, true);
        await loadPeers();
        return;
      }

      // The signal is asynchronous: the process may take a moment to go. Re-list rather than
      // claiming success, so the row disappearing is what confirms it.
      setPeersStatus(`signalled ${what}`);
      setTimeout(loadPeers, 300);
    } catch (e) {
      setPeersStatus(`could not kill ${what}: ${e.message}`, true);
    }
  }

  async function loadServerInfo() {
    try {
      const info = await api('/api/server');
      // A daemon that cannot report its own argv cannot relaunch itself; say so on the button
      // rather than letting the click fail.
      $('server-restart').disabled = !info.canRestart;
      $('server-restart').title = info.canRestart
        ? 'Relaunch the daemon. The page reconnects on its own.'
        : 'This daemon cannot relaunch itself — restart it from your terminal.';
    } catch { /* the banner already covers an unreachable daemon */ }
  }

  /**
   * Follow new traffic, or hold the list where it is.
   *
   * Paused drops live rows rather than queueing them, so resuming re-reads the session: a list
   * that picked up from wherever the next message happened to land would be missing everything
   * that arrived while it was paused, with nothing on screen to say so.
   */
  function setLiveTail(on) {
    state.liveTail = on;
    const button = $('live-btn');
    button.classList.toggle('on', on);
    button.classList.toggle('paused', !on);
    button.setAttribute('aria-pressed', String(on));
    button.querySelector('.live-label').textContent = on ? 'live' : 'paused';
    button.title = on
      ? 'Following new traffic as it arrives. Click to pause.'
      : 'Paused: new traffic is not added to the list. Click to catch up and follow again.';
    if (on) loadTransactions();
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
        if (state.allSessionId === state.sessionId) state.allTransactions.push(message.txn);
        renderList();
      } else if (message.type === 'marker') {
        state.markers.push(message.marker);
        renderMarkers();
        renderList();
        renderTimeline();
      } else if (message.type === 'signal') {
        // Carries `dataRef` already: the daemon broadcasts the row it stored, not the one it
        // received. A viewer handed the received row would see every payload as missing.
        state.signals.push(message.signal);
        // A tag's first signal is the moment its tab becomes reachable, and the moment `all`
        // starts having something to merge. Both are decided by what the session holds, so the
        // bar is rebuilt rather than revealed.
        renderTabs();
        refreshCurrent();
        renderTimeline();
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
      if (state.serverState === 'running') {
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
      // A restart means a new process with a fresh session list; a reconnect after a blip may
      // also have missed rows. Either way the page's data is stale, so re-read it.
      if (wasAway) {
        loadServerInfo();
        loadSessions().then(loadTransactions);
      }
    };
  }

  // --- sessions -----------------------------------------------------------

  /**
   * Two clicks, not a browser dialog.
   *
   * Deleting from the archive is permanent — there is no trash — so it needs a deliberate second
   * action. `confirm()` would do that, but it also blocks the event loop and cannot be driven by
   * the UI probe, so the second click lives on the button itself and expires on its own.
   */
  function confirmThen(button, idle, armed, run) {
    if (button.dataset.armed === '1') {
      clearTimeout(Number(button.dataset.armTimer));
      button.dataset.armed = '0';
      button.textContent = idle;
      button.classList.remove('armed');
      run();
      return;
    }
    button.dataset.armed = '1';
    button.textContent = armed;
    button.classList.add('armed');
    button.dataset.armTimer = String(setTimeout(() => {
      button.dataset.armed = '0';
      button.textContent = idle;
      button.classList.remove('armed');
    }, 4000));
  }

  async function sessionApi(path, method) {
    const res = await fetch(path, { method, headers: { 'X-Inspector-Control': '1' } });
    const text = await res.text();
    if (!res.ok) {
      let message = text;
      try { message = JSON.parse(text).error || text; } catch { /* keep raw */ }
      throw new Error(message);
    }
    return JSON.parse(text);
  }

  function sessionStatus(text, isError) {
    const node = $('sessions-status');
    if (!node) return;
    node.textContent = text || '';
    node.classList.toggle('cache-status-error', !!isError);
  }

  async function deleteSession(id) {
    try {
      const result = await sessionApi(`/api/sessions/${encodeURIComponent(id)}`, 'DELETE');
      sessionStatus(`deleted ${id} · freed ${fmtBytes(result.freedBytes)}`, false);
      // The session on screen just stopped existing, so pick another before re-reading anything
      // that would ask the daemon about it.
      if (state.sessionId === id) {
        state.sessionId = null;
        state.selectedId = null;
        state.viewChosenForSession = false;
      }
      await afterSessionChange();
    } catch (e) {
      // The daemon's refusals name the reason — a live session, a bad id — so show them verbatim.
      sessionStatus(e.message, true);
    }
  }

  async function clearSessions() {
    try {
      const result = await sessionApi('/api/sessions/clear', 'POST');
      const kept = result.kept.length ? ` · kept ${result.kept.length} still recording` : '';
      sessionStatus(
        `deleted ${result.deleted.length} · freed ${fmtBytes(result.freedBytes)}${kept}`,
        false,
      );
      if (!result.kept.includes(state.sessionId)) {
        state.sessionId = null;
        state.selectedId = null;
        state.viewChosenForSession = false;
      }
      await afterSessionChange();
    } catch (e) {
      sessionStatus(e.message, true);
    }
  }

  /** Everything on screen was derived from a session list that has just changed. */
  async function afterSessionChange() {
    state.payloads.clear();
    state.browserKey = null;
    state.browserObservation = null;
    await loadSessions();
    renderSessionRows();
    if (state.sessionId) {
      await loadTransactions();
    } else {
      state.transactions = [];
      state.allTransactions = [];
      state.signals = [];
      state.current = [];
      state.markers = [];
      renderList();
      renderMarkers();
      renderCurrent();
      renderTabs();
      applyView();
    }
  }

  function renderSessionRows() {
    const box = $('session-rows');
    if (!box) return;
    box.innerHTML = '';
    if (!state.sessions.length) {
      box.appendChild(el('div', 'muted', 'no sessions'));
      return;
    }
    for (const meta of state.sessions) {
      const row = el('div', 'session-row');
      if (meta.sessionId === state.sessionId) row.classList.add('sel');

      const name = el('div', 'session-row-id mono', meta.sessionId);
      row.appendChild(name);

      const bits = [`${meta.txnCount ?? 0} calls`];
      if (meta.errorCount) bits.push(`${meta.errorCount} errors`);
      // `endedAt` is the only signal on this list of whether the app is still attached, and it
      // is exactly what decides whether the daemon will allow the delete.
      const live = !meta.endedAt;
      if (live) bits.push('recording');
      row.appendChild(el('div', 'session-row-meta muted', bits.join(' · ')));

      const remove = el('button', 'btn btn-sm btn-quiet', '✕');
      if (live) {
        remove.disabled = true;
        remove.title = 'still being written — disconnect the app first';
      } else {
        remove.title = `Delete ${meta.sessionId}. Permanent.`;
        remove.addEventListener('click', () => {
          confirmThen(remove, '✕', 'sure?', () => deleteSession(meta.sessionId));
        });
      }
      row.appendChild(remove);
      box.appendChild(row);
    }
  }

  document.addEventListener('keydown', (e) => {
    if (e.target.tagName === 'INPUT' || e.target.tagName === 'SELECT') {
      if (e.key === 'Escape') e.target.blur();
      return;
    }
    switch (e.key) {
      case 'j': move(1); break;
      case 'k': move(-1); break;
      case '/': case 'f': e.preventDefault(); $('filter').focus(); break;
      case 'o': setSortOrder(!state.newestFirst); break;
      case 'g': move(-1e9); break;
      case 'G': move(1e9); break;
      case 'Escape':
        state.selectedId = null;
        $('detail').hidden = true;
        $('detail-empty').hidden = false;
        renderSessionGlance();
        closeDrawer();
        for (const row of document.querySelectorAll('.row')) row.classList.remove('selected');
        break;
      case 'c': {
        const txn = state.transactions.find((t) => t.id === state.selectedId);
        if (txn) fetchBody(txn, 'req').then((b) => navigator.clipboard.writeText(curlFor(txn, b)));
        break;
      }
    }
  });

  $('filter').addEventListener('input', applyFilter);
  $('session-picker').addEventListener('change', (e) => {
    state.sessionId = e.target.value;
    state.selectedId = null;
    state.timeRange = null;
    showSessionLabel();
    loadTransactions();
  });
  $('live-btn').addEventListener('click', () => setLiveTail(!state.liveTail));
  // Scoped to the filter rail, not every `.chip` on the page. Order chips and facet chips are
  // chips too, and they carry no `data-filter` — a blanket handler set the filter box to
  // `undefined`, which parses as a bad filter and empties the whole list.
  for (const chip of document.querySelectorAll('#chips .chip')) {
    chip.addEventListener('click', () => {
      const value = chip.dataset.filter;
      $('filter').value = $('filter').value === value ? '' : value;
      applyFilter();
    });
  }

  // Two clicks, like deleting a session: stopping ends recording for every app on this machine.
  // It replaces a `confirm()` dialog, which blocks the whole page and is easy to dismiss by reflex.
  $('server-stop').addEventListener('click', () => {
    confirmThen($('server-stop'), 'stop', 'stop daemon?', requestStop);
  });
  $('server-restart').addEventListener('click', requestRestart);
  $('order-oldest').addEventListener('click', () => setSortOrder(false));
  $('order-newest').addEventListener('click', () => setSortOrder(true));

  $('browser-pull').addEventListener('click', pullProviders);
  $('browser-filter').addEventListener('input', (e) => {
    state.browserFilter = e.target.value;
    renderBrowser();
  });

  $('session-delete').addEventListener('click', () => {
    if (state.sessionId) {
      confirmThen($('session-delete'), '✕', 'delete session?', () => deleteSession(state.sessionId));
    }
  });
  $('sessions-clear').addEventListener('click', () => {
    confirmThen($('sessions-clear'), 'clear all', 'delete every session?', clearSessions);
  });
  $('sessions-refresh').addEventListener('click', async () => {
    await loadSessions();
    renderSessionRows();
  });

  (async function init() {
    setSortOrder(state.newestFirst);   // paints the chips to match the remembered preference
    wirePops();
    wireMarkerForm();
    wireAxis();
    $('drawer-close').addEventListener('click', closeDrawer);
    // The scrim is the whole point of a scrim: click anywhere off the drawer and it goes away.
    $('drawer-scrim').addEventListener('click', closeDrawer);

    $('now-toggle').addEventListener('click', () => {
      const strip = $('now-strip');
      const open = $('current').hidden;
      $('current').hidden = !open;
      strip.classList.toggle('open', open);
      $('now-toggle').setAttribute('aria-expanded', String(open));
    });

    $('open-settings').addEventListener('click', () => {
      $('settings').showModal();
      loadPeers();
      loadMcp();
      applyDuplicateWindow(state.duplicateWindowMs);   // refreshes the count for this session
      applyEndpointLimit(state.endpointLimit);
      renderSessionRows();
    });
    $('peers-refresh').addEventListener('click', loadPeers);
    $('dup-window').value = state.duplicateWindowMs;
    $('dup-window').addEventListener('change', (e) => applyDuplicateWindow(Number(e.target.value)));
    $('endpoint-limit').value = state.endpointLimit;
    $('endpoint-limit').addEventListener('change', (e) => applyEndpointLimit(Number(e.target.value)));
    $('mcp-probe').addEventListener('click', probeMcp);
    $('mcp-copy').addEventListener('click', () => {
      const text = $('mcp-command').textContent;
      navigator.clipboard.writeText(text).then(() => {
        const button = $('mcp-copy');
        button.textContent = 'copied';
        setTimeout(() => (button.textContent = 'copy command'), 1200);
      });
    });

    // Ages are wall-clock now, so they have to be repainted or they are wrong the moment they
    // are drawn. Only the text of the age nodes changes; nothing re-renders around them.
    setInterval(paintAges, 1000);

    await loadServerInfo();
    await loadSessions();
    await loadTransactions();
    connectLive();
  })();
})();
