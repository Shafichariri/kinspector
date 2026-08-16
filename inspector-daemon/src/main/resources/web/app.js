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

  const curlFor = (txn, reqBody) => {
    let out = `curl -X ${txn.method} '${txn.scheme}://${txn.host}${txn.path}${txn.query ? '?' + txn.query : ''}'`;
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

  function renderList() {
    const list = $('list');
    list.innerHTML = '';

    // Oldest-first for display so marker dividers fall in causal order.
    const rows = [...state.transactions].sort((a, b) => a.mono - b.mono);
    $('list-empty').hidden = rows.length > 0;

    const markers = [...state.markers].sort((a, b) => a.mono - b.mono);
    let markerIndex = 0;

    for (const txn of rows) {
      while (markerIndex < markers.length && markers[markerIndex].mono <= txn.mono) {
        list.appendChild(el('div', 'marker-divider', markers[markerIndex].label));
        markerIndex++;
      }
      list.appendChild(rowFor(txn));
    }
    for (; markerIndex < markers.length; markerIndex++) {
      list.appendChild(el('div', 'marker-divider', markers[markerIndex].label));
    }

    if (state.liveTail) list.parentElement.scrollTop = list.parentElement.scrollHeight;
  }

  function rowFor(txn) {
    const cls = statusClass(txn.status);
    const row = el('div', 'row');
    row.dataset.id = txn.id;
    if (txn.id === state.selectedId) row.classList.add('selected');

    row.appendChild(el('span', `status-dot b${cls}`));
    row.appendChild(el('span', 'method', txn.method));

    const path = el('span', 'path', txn.path);
    if (txn.attempt > 1) {
      const badge = el('span', 'attempt', ` ·attempt ${txn.attempt}`);
      path.appendChild(badge);
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
    head.appendChild(el('span', 'path', `${txn.method} ${txn.path}`));
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

    pane.appendChild(el('div', 'section-title', 'Overview'));
    const kv = el('dl', 'kv');
    const put = (k, v) => { kv.appendChild(el('dt', null, k)); kv.appendChild(el('dd', null, v)); };
    put('URL', `${txn.scheme}://${txn.host}${txn.path}${txn.query ? '?' + txn.query : ''}`);
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

    appendSide(pane, 'Request', txn.reqHeaders, reqBody, txn.reqBytes, txn.reqBodyTruncated, txn.reqContentType);
    appendSide(pane, 'Response', txn.resHeaders, resBody, txn.resBytes, txn.resBodyTruncated, txn.resContentType);
  }

  function appendSide(pane, title, headers, body, totalBytes, truncated, contentType) {
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
    if (body === null) {
      pane.appendChild(
        el('div', 'muted', totalBytes
          ? `${fmtBytes(totalBytes)} not captured — content type outside the capture allowlist`
          : 'empty'),
      );
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
    const rows = [...state.transactions].sort((a, b) => a.mono - b.mono);
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
      setTimeout(connectLive, 1000);
    };
    ws.onopen = () => $('live-dot').classList.add('on');
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

  (async function init() {
    await loadSessions();
    await loadTransactions();
    connectLive();
  })();
})();
