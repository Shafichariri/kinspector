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
    // Cache payloads, keyed by signal id. Fetched lazily and only for the cache view: a row's
    // fields live in its payload, which the signal list deliberately does not carry.
    cachePayloads: new Map(),
    cacheFilters: { key: '', storage: '', scope: '', expired: '', latestOnly: false },
    // 'traffic' | 'timeline' | 'cache'. Chosen per session rather than remembered: a session with
    // no signals has nothing to merge, so landing on an empty timeline would be worse than useless.
    // Sessions recorded before signals existed therefore behave exactly as they always did.
    view: 'traffic',
    selectedSignalId: null,
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
    const picker = $('session-picker');
    picker.innerHTML = '';
    for (const s of state.sessions) {
      const option = el('option', null, `${s.sessionId}  (${s.txnCount})`);
      option.value = s.sessionId;
      picker.appendChild(option);
    }
    if (!state.sessionId && state.sessions.length) state.sessionId = state.sessions[0].sessionId;
    if (state.sessionId) picker.value = state.sessionId;

    showSessionLabel();
  }

  /** Which app and device the rows belong to. Also runs on switch, or it would name the old one. */
  function showSessionLabel() {
    const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
    $('session-app').textContent = meta ? `${meta.appId} · ${meta.device}` : 'no sessions';
  }

  async function loadTransactions() {
    if (!state.sessionId) return;
    const q = new URLSearchParams({ filter: state.filter, limit: '2000' });
    try {
      const page = await api(`/api/sessions/${encodeURIComponent(state.sessionId)}/transactions?${q}`);
      state.transactions = page.items;
      $('counts').textContent = `${page.matched}/${page.total}`;
      showFilterError(null);
    } catch (e) {
      // Parser messages are written to be shown verbatim, so show them verbatim.
      showFilterError(e.message);
      state.transactions = [];
      $('counts').textContent = '';
    }
    state.markers = await api(`/api/sessions/${encodeURIComponent(state.sessionId)}/markers`).catch(() => []);
    await syncAllTransactions();
    await loadSignals();
    renderMarkers();
    renderList();
    renderTimeline();
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

    const has = state.signals.length > 0 || state.current.length > 0;
    $('view-switch').hidden = !has;
    // A cache tab on a session that never recorded one is a dead end, so it appears only when
    // there is something behind it.
    const hasCache = cacheSignals().length > 0;
    $('view-cache').hidden = !hasCache;
    if (!hasCache && state.view === 'cache') state.view = 'traffic';
    if (!has && state.view === 'timeline') state.view = 'traffic';
    // The merge is the value, so a session that has something to merge opens on it.
    if (has && !state.viewChosenForSession) state.view = 'timeline';
    state.viewChosenForSession = true;
    renderCurrent();
    applyView();
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
   * Transactions in display order — the single source of truth for both rendering and j/k
   * navigation, so "next row" always means the row visually below the current one.
   */
  const orderedRows = () => {
    const rows = [...state.transactions].sort((a, b) => a.mono - b.mono);
    return state.newestFirst ? rows.reverse() : rows;
  };

  function renderList() {
    const list = $('list');
    list.innerHTML = '';

    // Over *all* transactions, not the filtered view: a duplicate whose twin is filtered out is
    // still a duplicate, and making the highlight depend on the current filter would hide exactly
    // the case you go looking for.
    state.duplicates = computeDuplicates(state.allTransactions, state.duplicateWindowMs);
    renderEndpointChips();

    const rows = orderedRows();
    $('list-empty').hidden = rows.length > 0;

    // Build the interleaved sequence oldest-first, where "marker, then the rows after it" is the
    // only arrangement that makes causal sense, then reverse the whole thing. Reversing after
    // interleaving keeps each divider attached to the same rows: read downward in newest-first
    // and a divider below a row still means that row happened after the marker.
    const markers = [...state.markers].sort((a, b) => a.mono - b.mono);
    const sequence = [];
    let markerIndex = 0;
    for (const txn of [...state.transactions].sort((a, b) => a.mono - b.mono)) {
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

  function setSortOrder(newestFirst) {
    state.newestFirst = newestFirst;
    localStorage.setItem('inspector.newestFirst', newestFirst ? '1' : '0');
    const button = $('sort-order');
    button.textContent = newestFirst ? 'newest ↑' : 'oldest ↓';
    button.title = newestFirst
      ? 'Newest request at the top — click for oldest first'
      : 'Oldest request at the top — click for newest first';
    renderList();
    renderTimeline();
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
    $('endpoints-label').hidden = shortcuts.length === 0;

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

    const path = el('span', 'path', txn.path);
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

    pane.appendChild(el('div', 'section-title', 'Overview'));
    const kv = el('dl', 'kv');
    const put = (k, v) => { kv.appendChild(el('dt', null, k)); kv.appendChild(el('dd', null, v)); };
    put('URL', urlOf(txn));
    put('Status', txn.status ?? 'transport failure');
    if (txn.error) put('Error', txn.error);
    put('Duration', fmtMs(txn.ms));
    put('Request size', fmtBytes(txn.reqBytes));
    put('Response size', fmtBytes(txn.resBytes));
    put('Started', txn.ts);
    pane.appendChild(kv);

    if (txn.redacted && txn.redacted.length) {
      // Say it was removed, not that it was absent — otherwise a reader concludes no
      // credentials were sent.
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

    appendSide(pane, 'Request', txn, 'req', reqBody);
    appendSide(pane, 'Response', txn, 'res', resBody);
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

  function appendSide(pane, title, txn, side, body) {
    const headers = side === 'req' ? txn.reqHeaders : txn.resHeaders;
    const truncated = side === 'req' ? txn.reqBodyTruncated : txn.resBodyTruncated;
    const contentType = side === 'req' ? txn.reqContentType : txn.resContentType;
    const totalBytes = side === 'req' ? txn.reqBytes : txn.resBytes;

    pane.appendChild(el('div', 'section-title', `${title} headers`));
    const box = el('div', 'headers');
    const entries = Object.entries(headers || {});
    if (!entries.length) {
      box.appendChild(el('div', 'muted', 'none'));
    } else {
      for (const [name, values] of entries) {
        const line = el('div');
        line.appendChild(el('span', 'hname', `${name}: `));
        line.appendChild(el('span', null, values.join(', ')));
        box.appendChild(line);
      }
    }
    pane.appendChild(box);

    pane.appendChild(el('div', 'section-title', `${title} body`));
    const absent = bodyAbsenceReason(txn, side, body);
    if (absent !== null) {
      pane.appendChild(el('div', 'muted', absent));
      return;
    }
    if (truncated) {
      pane.appendChild(el('div', 'banner', `truncated at ${fmtBytes(body.length)} of ${fmtBytes(totalBytes)}`));
    }
    const isJson = (contentType || '').includes('json') || /^\s*[{[]/.test(body);
    pane.appendChild(el('pre', 'body', isJson ? prettyJson(body) : body));
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
    const timeline = state.view === 'timeline';
    const cache = state.view === 'cache';
    const traffic = !timeline && !cache;
    $('list').hidden = !traffic;
    $('list-empty').hidden = !traffic || state.transactions.length > 0;
    $('timeline').hidden = !timeline;
    $('timeline-empty').hidden = true;
    $('cache').hidden = !cache;
    $('view-traffic').classList.toggle('active', traffic);
    $('view-timeline').classList.toggle('active', timeline);
    $('view-cache').classList.toggle('active', cache);
    if (timeline) renderTimeline();
    if (cache) renderCache();
  }

  // --- cache view ---------------------------------------------------------

  /**
   * A table of what the app had cached, and when.
   *
   * Inspector does not define what a cache row contains — `tag` is app-defined and so is the
   * payload — so this reads a set of conventional field names and degrades rather than failing:
   * `storage`, `scopes` (or `scope`), `expired`, `key`, `value`, `payloadBytes`. A payload that
   * uses none of them still gets a row with its time, name and raw value; an app that follows the
   * convention gets the full table. Documented in INTEGRATION.md §12d.
   *
   * Two payload shapes both feed this table, because both are useful and apps emit both:
   *   - a **whole-cache snapshot**, `{ items: [ … ] }`, which expands to one row per entry;
   *   - a **single entry**, one row, typically pushed as the entry changes.
   */
  const cacheSignals = () =>
    state.signals.filter((signal) => String(signal.tag).toLowerCase() === 'cache');

  /**
   * Payloads are fetched only for this view, and only once per signal.
   *
   * The signal list deliberately omits payloads — a session can hold thousands — but every column
   * except time and key lives inside one, so the table cannot be drawn without them.
   */
  async function loadCachePayloads() {
    const missing = cacheSignals().filter(
      (signal) => signal.dataRef && !state.cachePayloads.has(signal.id),
    );
    if (!missing.length || !state.sessionId) return;
    const id = encodeURIComponent(state.sessionId);
    await Promise.all(
      missing.map(async (signal) => {
        try {
          const res = await fetch(`/api/sessions/${id}/signals/${encodeURIComponent(signal.id)}/data`);
          state.cachePayloads.set(signal.id, JSON.parse(await res.text()));
        } catch {
          // A payload that will not parse is still a row; it just has nothing to put in the
          // columns. Recording the failure stops us retrying it on every render.
          state.cachePayloads.set(signal.id, null);
        }
      }),
    );
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

  function cacheRowsFor(signal) {
    const payload = state.cachePayloads.get(signal.id);
    const base = {
      ts: signal.ts,
      mono: signal.mono,
      signalId: signal.id,
      trigger: signal.trigger === 'request' ? 'request' : 'app',
    };
    if (payload && typeof payload === 'object' && Array.isArray(payload.items)) {
      return payload.items.map((item) => ({
        ...base,
        ...cacheFieldsOf(item),
        // In a whole-cache snapshot the entry's own id is the only identity it has; the signal
        // name identifies the snapshot, not the entry.
        key: (typeof item.id === 'string' && item.id) || signal.name,
        fromSnapshot: true,
      }));
    }
    if (payload && typeof payload === 'object') {
      return [{ ...base, ...cacheFieldsOf(payload), key: signal.name, fromSnapshot: false }];
    }
    return [{
      ...base,
      key: signal.name,
      storage: '',
      scopes: [],
      expired: null,
      value: payload == null ? null : payload,
      bytes: null,
      kind: null,
      storageKey: null,
      fromSnapshot: false,
    }];
  }

  function allCacheRows() {
    const rows = [];
    for (const signal of cacheSignals()) rows.push(...cacheRowsFor(signal));
    rows.sort((a, b) => a.mono - b.mono);
    return state.newestFirst ? rows.reverse() : rows;
  }

  function cacheRowMatches(row) {
    const f = state.cacheFilters;
    if (f.key && !String(row.key).toLowerCase().includes(f.key.toLowerCase())) return false;
    if (f.storage && row.storage !== f.storage) return false;
    if (f.scope && !row.scopes.includes(f.scope)) return false;
    if (f.expired === 'yes' && row.expired !== true) return false;
    if (f.expired === 'no' && row.expired !== false) return false;
    return true;
  }

  /** Collapses to the most recent row per key, which is what "what is cached now" means. */
  function latestPerKey(rows) {
    const latest = new Map();
    for (const row of rows) {
      const previous = latest.get(row.key);
      if (!previous || row.mono >= previous.mono) latest.set(row.key, row);
    }
    const collapsed = [...latest.values()].sort((a, b) => a.mono - b.mono);
    return state.newestFirst ? collapsed.reverse() : collapsed;
  }

  /** Long enough to recognise a value, short enough that a row stays one line. */
  const CACHE_VALUE_CLIP = 160;

  const cacheValueText = (value) => {
    if (value === null || value === undefined) return '—';
    if (typeof value === 'string') return value;
    try {
      return JSON.stringify(value);
    } catch {
      return String(value);
    }
  };

  function cacheValueCell(row) {
    const cell = el('td', 'cache-col-value');
    if (row.bytes != null) cell.appendChild(el('span', 'cache-bytes muted', `${fmtBytes(row.bytes)} `));
    const full = cacheValueText(row.value);
    const clipped = full.length > CACHE_VALUE_CLIP;
    const short = clipped ? `${full.slice(0, CACHE_VALUE_CLIP)}…` : full;
    const text = el('span', 'cache-value mono', short);
    if (clipped) {
      text.classList.add('cache-value-clip');
      text.title = 'click to expand';
      text.addEventListener('click', () => {
        const open = text.classList.toggle('cache-value-open');
        text.textContent = open ? full : short;
      });
    }
    cell.appendChild(text);
    return cell;
  }

  function cacheRow(row) {
    const tr = el('tr', 'cache-row');
    tr.dataset.key = row.key;
    tr.dataset.signalId = row.signalId;
    if (row.expired === true) tr.classList.add('cache-row-expired');

    const time = el('td', 'cache-col-time mono muted', fmtClock(row.ts));
    time.title = `mono ${row.mono}ms`;
    tr.appendChild(time);

    const key = el('td', 'cache-col-key');
    key.appendChild(el('span', 'cache-key', row.key));
    if (row.storageKey) key.title = row.storageKey;
    if (row.kind) {
      key.appendChild(el('span', `cache-kind cache-kind-${row.kind.toLowerCase()}`, row.kind.toLowerCase()));
    }
    // Provenance, for the same reason the timeline badges it: a snapshot pushed an hour ago read
    // as the current state of the cache is the mistake this view exists to prevent.
    key.appendChild(
      row.trigger === 'request'
        ? el('span', 'tl-trigger tl-pulled', 'pulled')
        : el('span', 'tl-trigger tl-pushed', 'pushed'),
    );
    tr.appendChild(key);

    tr.appendChild(el('td', 'cache-col-storage', row.storage || '—'));
    tr.appendChild(el('td', 'cache-col-scope', row.scopes.join(', ') || '—'));

    const expired = el('td', 'cache-col-expired', row.expired === null ? '—' : row.expired ? 'yes' : 'no');
    if (row.expired === true) expired.classList.add('cache-expired');
    tr.appendChild(expired);

    tr.appendChild(cacheValueCell(row));
    return tr;
  }

  /**
   * Options come from the rows themselves rather than a fixed list, because `storage` and `scope`
   * are the app's words. A hardcoded set would be wrong for every app but the one it was written
   * against.
   */
  function refreshCacheFilterOptions(rows) {
    const fill = (id, values, selected) => {
      const select = $(id);
      const wanted = ['', ...values];
      const current = [...select.options].map((o) => o.value);
      if (current.length === wanted.length && current.every((v, i) => v === wanted[i])) return;
      select.innerHTML = '';
      select.appendChild(el('option', '', 'any'));
      for (const value of values) {
        const option = el('option', '', value);
        option.value = value;
        select.appendChild(option);
      }
      select.value = values.includes(selected) ? selected : '';
    };
    fill('cache-filter-storage', [...new Set(rows.map((r) => r.storage).filter(Boolean))].sort(),
      state.cacheFilters.storage);
    fill('cache-filter-scope', [...new Set(rows.flatMap((r) => r.scopes))].sort(),
      state.cacheFilters.scope);
  }

  function renderCache() {
    if (state.view !== 'cache') return;
    const all = allCacheRows();
    refreshCacheFilterOptions(all);

    let rows = all.filter(cacheRowMatches);
    if (state.cacheFilters.latestOnly) rows = latestPerKey(rows);

    const body = $('cache-rows');
    body.innerHTML = '';
    for (const row of rows) body.appendChild(cacheRow(row));
    $('cache-empty').hidden = rows.length > 0;
    $('cache-empty').textContent = all.length
      ? 'no rows match these filters'
      : 'no cache signals in this session';
  }

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
  function cacheProviderNames() {
    const names = new Set();
    for (const signal of cacheSignals()) {
      if (signal.trigger === 'request') {
        names.add(signal.name);
        continue;
      }
      const payload = state.cachePayloads.get(signal.id);
      if (payload && typeof payload === 'object' && Array.isArray(payload.items)) {
        names.add(signal.name);
      }
    }
    return [...names];
  }

  async function pullCaches() {
    const status = $('cache-pull-status');
    const names = cacheProviderNames();
    if (!names.length) {
      status.textContent =
        'no cache provider has been seen yet — the app registers one with Inspector.registerProvider';
      return;
    }
    $('cache-pull').disabled = true;
    status.textContent = `pulling ${names.length}…`;
    const failures = [];
    for (const name of names) {
      try {
        const res = await fetch(
          `/api/sessions/${encodeURIComponent(state.sessionId || 'latest')}/signals/request`,
          {
            method: 'POST',
            headers: { 'X-Inspector-Control': '1', 'Content-Type': 'application/json' },
            body: JSON.stringify({ tag: 'cache', name }),
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
    $('cache-pull').disabled = false;
    if (failures.length) {
      status.textContent = failures.join(' · ');
      status.classList.add('cache-status-error');
      return;
    }
    status.classList.remove('cache-status-error');
    status.textContent = `pulled ${names.length} at ${fmtClock(new Date().toISOString())}`;
    // The pulled rows arrive over the live socket; re-read so they are on screen either way.
    await loadSignals();
    await loadCachePayloads();
    renderCache();
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

  function renderTimeline() {
    if (state.view !== 'timeline') return;
    const root = $('timeline');
    root.innerHTML = '';

    const ends = spanEndsFor(state.signals);
    const entries = [
      ...state.transactions.map((txn) => ({ kind: 'txn', mono: txn.mono, txn })),
      ...state.signals.map((signal) => ({ kind: 'signal', mono: signal.mono, signal })),
      ...state.markers.map((marker) => ({ kind: 'marker', mono: marker.mono, marker })),
    ].sort((a, b) => a.mono - b.mono);

    if (state.newestFirst) entries.reverse();

    $('timeline-empty').hidden = entries.length > 0;

    // Spans are scaled against the session's own duration, not a fixed divisor. A fixed one
    // made a cache snapshot held for 230ms draw narrower than an instantaneous screen event,
    // which reads as the opposite of what it means.
    const monos = entries.map((e) => e.mono);
    const first = monos.length ? Math.min(...monos) : 0;
    const last = monos.length ? Math.max(...monos) : 0;
    const span = Math.max(1, last - first);
    for (const entry of entries) {
      root.appendChild(timelineRow(entry, ends, last, span));
    }

    if (state.liveTail) {
      const pane = root.parentElement;
      pane.scrollTop = state.newestFirst ? 0 : pane.scrollHeight;
    }
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

  /** The rail panel answering "what screen, what is cached" without reading any rows. */
  function renderCurrent() {
    const list = $('current');
    const label = $('current-label');
    const rows = state.current;
    label.hidden = rows.length === 0;
    list.hidden = rows.length === 0;
    list.innerHTML = '';

    const newest = rows.length ? Math.max(...rows.map((r) => r.mono)) : 0;
    for (const signal of rows) {
      const item = el('li', 'current-item');
      item.dataset.tag = signal.tag;
      item.appendChild(el('span', 'tl-tag', signal.tag));
      item.appendChild(el('span', 'current-name', signal.name));
      // Age is measured against the newest observation, not the wall clock: `mono` is the
      // device's monotonic clock and has no relationship to this machine's.
      const age = newest - signal.mono;
      const trigger = signal.trigger === 'request' ? 'pulled' : 'pushed';
      const note = el(
        'span',
        `current-age muted ${age > 30000 ? 'stale' : ''}`,
        age === 0 ? trigger : `${trigger} ${fmtMs(age)} earlier`,
      );
      note.title = 'age relative to the newest observation in this session';
      item.appendChild(note);
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
    for (const row of document.querySelectorAll('.row')) {
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
      for (const chip of document.querySelectorAll('.chip')) {
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
  }

  async function requestStop() {
    if (!confirm('Stop the daemon? Traffic will stop being recorded until you start it again.')) return;
    setControlsEnabled(false);
    try {
      await control('stop');
      state.serverState = 'stopped';
      showServerBanner('Daemon stopped. Run `inspector serve` to start it again.', false);
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

  // --- replay --------------------------------------------------------------------------------

  /**
   * Re-sends a captured request and renders what came back.
   *
   * Re-signing is on by default because the requests worth replaying are usually signed, and a
   * verbatim replay of a signed request fails at the server in a way that reads as a backend fault
   * rather than as the tool having replayed single-use headers.
   */
  async function runReplay(txn, pane, button) {
    const previous = button.textContent;
    button.disabled = true;
    button.textContent = 'replaying…';

    let panel = pane.querySelector('.replay-result');
    if (!panel) {
      pane.insertBefore(el('div', 'section-title', 'Replay'), pane.querySelector('.section-title'));
      panel = el('div', 'replay-result');
      pane.insertBefore(panel, pane.querySelector('.section-title').nextSibling);
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
        }),
      });
      const result = await res.json();
      renderReplayResult(panel, result);
    } catch (e) {
      panel.appendChild(el('div', 'replay-error', `could not reach the daemon: ${e.message}`));
    } finally {
      button.disabled = false;
      button.textContent = previous;
    }
  }

  function renderReplayResult(panel, result) {
    if (!result.ok) {
      panel.appendChild(el('div', 'replay-error', result.error || 'replay failed'));
      if (result.diagnosis) panel.appendChild(el('div', 'replay-diagnosis', result.diagnosis));
      return;
    }

    const head = el('div', 'replay-head');
    head.appendChild(el('span', `status s${statusClass(result.status)}`, result.status));
    head.appendChild(el('span', 'muted', fmtMs(result.ms)));
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
        if ($('view-switch').hidden) {
          $('view-switch').hidden = false;
        }
        refreshCurrent();
        renderTimeline();
      }
    };
    ws.onclose = () => {
      $('live-dot').classList.remove('on');
      // A deliberate stop is not a connection problem, so do not keep dialling — and do not
      // overwrite the banner that says the daemon is gone on purpose.
      if (state.serverState === 'stopped') return;
      if (state.serverState === 'running') {
        showServerBanner('Lost the daemon — reconnecting…', true);
      }
      setTimeout(connectLive, 1000);
    };
    ws.onopen = () => {
      $('live-dot').classList.add('on');
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
    showSessionLabel();
    loadTransactions();
  });
  $('live-tail').addEventListener('change', (e) => { state.liveTail = e.target.checked; });
  for (const chip of document.querySelectorAll('.chip')) {
    chip.addEventListener('click', () => {
      const value = chip.dataset.filter;
      $('filter').value = $('filter').value === value ? '' : value;
      applyFilter();
    });
  }

  $('server-stop').addEventListener('click', requestStop);
  $('server-restart').addEventListener('click', requestRestart);
  $('sort-order').addEventListener('click', () => setSortOrder(!state.newestFirst));

  for (const button of [$('view-traffic'), $('view-timeline'), $('view-cache')]) {
    button.addEventListener('click', async () => {
      state.view = button.dataset.view;
      // Payloads are only needed by the cache table, so they are fetched on the way in rather
      // than for every session that happens to have cache rows.
      if (state.view === 'cache') await loadCachePayloads();
      applyView();
    });
  }

  $('cache-pull').addEventListener('click', pullCaches);

  for (const [id, field] of [
    ['cache-filter-key', 'key'],
    ['cache-filter-storage', 'storage'],
    ['cache-filter-scope', 'scope'],
    ['cache-filter-expired', 'expired'],
  ]) {
    $(id).addEventListener('input', () => {
      state.cacheFilters[field] = $(id).value;
      renderCache();
    });
  }
  $('cache-latest-only').addEventListener('change', () => {
    state.cacheFilters.latestOnly = $('cache-latest-only').checked;
    renderCache();
  });

  (async function init() {
    setSortOrder(state.newestFirst);   // paints the button to match the remembered preference

    $('open-settings').addEventListener('click', () => {
      $('settings').showModal();
      loadPeers();
      loadMcp();
      applyDuplicateWindow(state.duplicateWindowMs);   // refreshes the count for this session
      applyEndpointLimit(state.endpointLimit);
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

    await loadServerInfo();
    await loadSessions();
    await loadTransactions();
    connectLive();
  })();
})();
