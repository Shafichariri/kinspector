/* browser detail: glances, one value and its history, pulls */
'use strict';

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
    'copy for agent',
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
  const tree = text !== null && typeof row.value === 'object'
    ? jsonTree(text, `sig:${row.signalId}`, 'Value', '')
    : null;
  if (tree) {
    // The tree carries its own copy; a second one in the header would copy the same thing.
    box.appendChild(head);
    box.appendChild(tree);
    return box;
  }
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
      const res = await source.fetch(
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
