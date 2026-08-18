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
    renderMarkers();
    renderList();
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
    state.duplicates = computeDuplicates(state.transactions, state.duplicateWindowMs);

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
    $('dup-summary').textContent = ms === 0
      ? 'off'
      : `${computeDuplicates(state.transactions, ms).size} row(s) in this session`;
    renderList();
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
        renderList();
      } else if (message.type === 'marker') {
        state.markers.push(message.marker);
        renderMarkers();
        renderList();
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

  (async function init() {
    setSortOrder(state.newestFirst);   // paints the button to match the remembered preference

    $('open-settings').addEventListener('click', () => {
      $('settings').showModal();
      loadPeers();
      loadMcp();
      applyDuplicateWindow(state.duplicateWindowMs);   // refreshes the count for this session
    });
    $('peers-refresh').addEventListener('click', loadPeers);
    $('dup-window').value = state.duplicateWindowMs;
    $('dup-window').addEventListener('change', (e) => applyDuplicateWindow(Number(e.target.value)));
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
