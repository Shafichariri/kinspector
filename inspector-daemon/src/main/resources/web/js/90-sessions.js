/* sessions: the picker's label and face, switching, and the Sessions page's list, presets and deletes */
'use strict';

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

/**
 * The facts a session's label is made of: whether an app is attached right now, when it started,
 * the app, the device and how many calls it holds. The picker's face leads with the first two, and
 * the option text joins them all (`sessionLabel`).
 *
 * The dot marks a session an app is attached to right now, asked of `/api/recording` rather than
 * inferred from `endedAt`: a session whose app vanished has no `endedAt` and no app either.
 */
function sessionLabelParts(meta) {
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
  return {
    recording: (state.recording || []).includes(meta.sessionId),
    when,
    app: meta.appId,
    device: meta.device,
    count: `${count} ${count === 1 ? 'call' : 'calls'}`,
  };
}

/**
 * `● app · device · 09:41 today · 12 calls`.
 *
 * The option used to be the session id, which is a timestamp and three slugs run together — the
 * facts were in it, but in the order a folder name needs rather than the one a reader asks in.
 */
function sessionLabel(meta) {
  const { recording, when, app, device, count } = sessionLabelParts(meta);
  return `${recording ? '● ' : ''}${[app, device, when, count].filter(Boolean).join(' · ')}`;
}

/**
 * The face drawn under the session picker: `● 09:41 today`, which never shrinks, then
 * `app · device · N calls`, which ellipsizes, with the whole option text as the tooltip.
 *
 * The one writer of the face: whatever changes the selected session or its option's text — a load,
 * a switch, and a live count once the option follows one — repaints it by calling this. A closed
 * `<select>` clips its own text from the end, mid-word, which is where the start time was, and
 * cannot ellipsize; it lies transparent over this instead.
 */
function paintPickerFace() {
  const picker = $('session-picker');
  const face = document.querySelector('.picker-face');
  if (!face) return;
  const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
  const parts = meta ? sessionLabelParts(meta) : null;
  face.querySelector('.picker-time').textContent = parts
    ? `${parts.recording ? '● ' : ''}${parts.when || 'no start time'}`
    : 'no session';
  face.querySelector('.picker-rest').textContent = parts
    ? [parts.app, parts.device, parts.count].filter(Boolean).join(' · ')
    : '';
  const label = meta ? sessionLabel(meta) : 'No session';
  face.title = label;
  // The select lies over the face, so it is what a pointer hovers; it carries the tooltip too.
  picker.title = label;
}

/**
 * The selected session's option counts a live call as the header does, from `sessionTotal`.
 *
 * `txnCount` is the daemon's, as of the last `loadSessions`, and nothing re-read it while the
 * session recorded: the picker said `6 calls` under a header saying 31. Counting here costs no
 * request, where re-reading `/api/sessions` per call would. Other sessions' options are left as
 * loaded; the Sessions page is where every session's count belongs, if anywhere.
 */
function followLiveCount() {
  const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
  if (!meta) return;
  meta.txnCount = state.sessionTotal ?? (meta.txnCount ?? 0) + 1;
  const option = [...$('session-picker').options].find((o) => o.value === meta.sessionId);
  if (option) option.textContent = sessionLabel(meta);
  paintPickerFace();
}

/**
 * Which app the page is showing, in the window title — the picker already names it in the bar.
 * Also runs on switch, or it would name the old one.
 */
function showSessionLabel() {
  const meta = state.sessions.find((s) => s.sessionId === state.sessionId);
  document.title = meta ? `${meta.appId} · inspector` : 'inspector';
}

// --- switching ------------------------------------------------------------

/**
 * Shows session [id]: the picker's own `change` body, moved here so the picker, an address and the
 * Sessions page's `open` all switch through it, and every clear a switch makes — the selection, the
 * brushed stretch, and the signal side through `loadTransactions` — runs for all three.
 *
 * The address is rewritten in place, never pushed: Back walks places, not data, and a pushed switch
 * would make Back re-run a whole-session read.
 */
async function switchSession(id) {
  state.sessionId = id;
  state.selectedId = null;
  state.timeRange = null;
  showSessionLabel();
  $('session-picker').value = id;
  paintPickerFace();
  if (currentPage() === 'traffic') {
    // An address naming a call of this session keeps naming it; any other switch leaves the call's
    // page, since the call belongs to the session switched away from.
    const route = parseRoute(location.hash);
    const keep = route.page === 'txn' && route.session === id;
    replaceRoute(keep ? route : { ...route, page: 'traffic', session: id });
    if (!keep) showTxnFull(null);
  }
  if (currentPage() === 'sessions') renderSessionRows();
  statsSessionChanged();
  await loadTransactions();
}

/**
 * Names the folder this daemon archives to in the Sessions page's note: `<data>/sessions`, as
 * `DaemonConfig.sessionsDir` resolves it. The note said `~/.inspector/sessions` whatever `--data`
 * was. The data directory is read from `/api/mcp`, the route the Processes page already shows it
 * from, because it is the daemon's own config and not the self peer's argv, which has no `--data`
 * on a default start. Until it answers, and if it fails, the note keeps `<data dir>/sessions`,
 * which names no path rather than the wrong one. An export never gets here (`enterPage`), and its
 * Sessions page is hidden.
 */
async function loadSessionsDir() {
  try {
    const { dataDir } = await api('/api/mcp');
    if (!dataDir) return;
    // A `\`-only path is a Windows daemon's; Java's `resolve` adds no separator after a root.
    const sep = dataDir.includes('\\') && !dataDir.includes('/') ? '\\' : '/';
    $('sessions-dir').textContent = `${dataDir.endsWith(sep) ? dataDir : dataDir + sep}sessions`;
  } catch { /* the placeholder stays: it is true, and a wrong path is not */ }
}

/** A control request's JSON answer, or its refusal, in the daemon's words, as an error. */
async function controlJson(path, options) {
  const res = await controlFetch(path, options);
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
    const result = await controlJson(`/api/sessions/${encodeURIComponent(id)}`, { method: 'DELETE' });
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
    const result = await controlJson('/api/sessions/clear');
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
  // The session on screen may just have been deleted; its values must not stand while the list loads.
  followSession();
  await loadSessions();
  renderSessionRows();
  statsSessionChanged();
  if (state.sessionId) {
    await loadTransactions();
  } else {
    // Through `forgetWholeSession`, so a load still in flight for the session just deleted is
    // dropped rather than drawing it back, and neither copy stays marked as a session's.
    forgetWholeSession();
    state.transactions = [];
    state.allTransactions = [];
    state.signals = [];
    state.allSignals = [];
    state.screens = [];
    state.current = [];
    state.markers = [];
    renderList();
    renderMarkers();
    renderCurrent();
    renderTabs();
    applyView();
  }
}

// --- the Sessions page --------------------------------------------------------------------------

/**
 * The sessions ticked on the Sessions page, by id. A set of ids rather than of rows, because the
 * list is redrawn on every load; an id the list no longer holds is dropped as it redraws.
 */
const selectedSessions = new Set();

/** A session an app is writing to: never ticked, never deleted, by a preset or by hand. */
const sessionIsRecording = (meta, recording) => !meta.endedAt || (recording || []).includes(meta.sessionId);

/**
 * The ids each preset selects, from [sessions], every one that is not recording. Pure.
 *
 * `keepLast` keeps the newest N by `startedAt` and selects the rest; `olderThanDays` selects every
 * session started more than that many days before `now`. A tie on `startedAt` is broken by the id,
 * so a preset selects the same sessions however the daemon ordered the list.
 */
function sessionPresets(sessions, recording, { keepLast = 10, olderThanDays = 7, now = Date.now() } = {}) {
  const startOf = (meta) => {
    const at = Date.parse(meta.startedAt);
    return Number.isFinite(at) ? at : 0;
  };
  const newestFirst = sessions.slice().sort((a, b) =>
    startOf(b) - startOf(a) || (a.sessionId < b.sessionId ? 1 : a.sessionId > b.sessionId ? -1 : 0));
  const deletable = (meta) => !sessionIsRecording(meta, recording);
  const cutoff = now - olderThanDays * 86400000;
  return {
    keepLast: newestFirst.slice(Math.max(0, keepLast)).filter(deletable).map((m) => m.sessionId),
    olderThan: newestFirst.filter((m) => deletable(m) && startOf(m) < cutoff).map((m) => m.sessionId),
  };
}

function renderSessionRows() {
  const box = $('session-rows');
  if (!box) return;
  box.innerHTML = '';
  const present = new Set(state.sessions.map((m) => m.sessionId));
  for (const id of [...selectedSessions]) if (!present.has(id)) selectedSessions.delete(id);
  if (!state.sessions.length) {
    const row = el('tr');
    const cell = el('td', 'muted', 'no sessions');
    cell.colSpan = 7;
    row.appendChild(cell);
    box.appendChild(row);
    updateSessionActions();
    return;
  }
  for (const meta of state.sessions) box.appendChild(sessionRow(meta));
  updateSessionActions();
}

function sessionRow(meta) {
  const row = el('tr', 'session-row');
  row.dataset.id = meta.sessionId;
  if (meta.sessionId === state.sessionId) row.classList.add('sel');
  // `endedAt` and `/api/recording` together are what decide whether the daemon will allow the
  // delete: an app attached now, or a session whose writer never closed it.
  const live = sessionIsRecording(meta, state.recording);
  const parts = sessionLabelParts(meta);

  const tick = el('input');
  tick.type = 'checkbox';
  tick.checked = selectedSessions.has(meta.sessionId);
  tick.disabled = live;
  tick.setAttribute('aria-label', `Select ${meta.sessionId}`);
  tick.addEventListener('change', () => {
    if (tick.checked) selectedSessions.add(meta.sessionId);
    else selectedSessions.delete(meta.sessionId);
    updateSessionActions();
  });
  row.appendChild(cellOf(tick, 'col-check'));

  const name = el('td', 'session-name');
  name.appendChild(el('div', 'session-row-label', [parts.app, parts.when].filter(Boolean).join(' · ')));
  name.appendChild(el('div', 'session-row-id mono muted', meta.sessionId));
  name.title = meta.sessionId;
  row.appendChild(name);
  row.appendChild(el('td', 'col-secondary', meta.device || ''));
  row.appendChild(el('td', 'num mono', String(meta.txnCount ?? 0)));
  row.appendChild(el('td', 'col-secondary num mono', meta.errorCount ? String(meta.errorCount) : ''));
  row.appendChild(el('td', live ? 'session-state recording' : 'session-state muted', live ? 'recording' : 'ended'));

  const actions = el('td', 'col-actions');
  const open = el('button', 'btn btn-sm btn-quiet', 'open');
  open.type = 'button';
  open.title = `Show ${meta.sessionId} on the Traffic page`;
  open.addEventListener('click', () => navigate({ page: 'traffic', session: meta.sessionId }));
  actions.appendChild(open);
  // Text in the icon box, not an SVG: arming swaps the text to `sure?` (`confirmThen`).
  const remove = el('button', 'btn btn-sm btn-quiet btn-icon row-delete', '✕');
  remove.type = 'button';
  if (live) {
    remove.disabled = true;
    remove.title = 'still being written — disconnect the app first';
  } else {
    remove.title = `Delete ${meta.sessionId}. Permanent.`;
    remove.addEventListener('click', () => {
      confirmThen(remove, '✕', 'sure?', () => deleteSession(meta.sessionId));
    });
  }
  actions.appendChild(remove);
  row.appendChild(actions);
  return row;
}

const cellOf = (child, cls) => {
  const cell = el('td', cls);
  cell.appendChild(child);
  return cell;
};

/** The bulk button's count and the select-all box, from the selection as it stands. */
function updateSessionActions() {
  const button = $('sessions-delete-selected');
  if (!button) return;
  const n = selectedSessions.size;
  if (button.dataset.armed !== '1') button.textContent = `delete selected (${n})`;
  button.disabled = n === 0;
  const deletable = state.sessions.filter((m) => !sessionIsRecording(m, state.recording));
  const all = $('sessions-all');
  all.checked = deletable.length > 0 && deletable.every((m) => selectedSessions.has(m.sessionId));
  all.indeterminate = n > 0 && !all.checked;
  all.disabled = deletable.length === 0;
}

/** A preset replaces the selection rather than adding to it, so what it ticks is what it says. */
function selectSessions(ids) {
  selectedSessions.clear();
  for (const id of ids) selectedSessions.add(id);
  renderSessionRows();
}

/**
 * Deletes every ticked session in one request, and says what happened to each: a batch that
 * reported one status would hide the one the daemon refused, and its reason.
 */
async function deleteSelectedSessions() {
  const order = state.sessions.map((m) => m.sessionId);
  const ids = order.filter((id) => selectedSessions.has(id));
  if (!ids.length) return;
  sessionStatus(`deleting ${ids.length}…`, false);
  let reply;
  try {
    reply = await controlJson('/api/sessions/delete', { json: { ids } });
  } catch (e) {
    sessionStatus(e.message, true);
    return;
  }
  const results = reply.results || [];
  const refused = results.filter((r) => r.outcome !== 'deleted');
  const node = $('sessions-status');
  node.textContent = `deleted ${results.length - refused.length} of ${results.length}` +
    (reply.freedBytes ? ` · freed ${fmtBytes(reply.freedBytes)}` : '');
  node.classList.toggle('cache-status-error', refused.length > 0);
  const list = el('ul', 'page-results');
  for (const r of results) {
    const line = el('li', r.outcome === 'deleted' ? 'muted' : 'error');
    line.textContent = `${r.id} — ${r.outcome}${r.error ? `: ${r.error}` : ''}`;
    list.appendChild(line);
  }
  node.appendChild(list);
  for (const id of reply.deleted || []) selectedSessions.delete(id);
  if ((reply.deleted || []).includes(state.sessionId)) {
    state.sessionId = null;
    state.selectedId = null;
    state.viewChosenForSession = false;
  }
  await afterSessionChange();
}

function wireSessionsPage() {
  $('sessions-all').addEventListener('change', (e) => {
    selectSessions(e.target.checked
      ? state.sessions.filter((m) => !sessionIsRecording(m, state.recording)).map((m) => m.sessionId)
      : []);
  });
  const presets = () => sessionPresets(state.sessions, state.recording, {
    keepLast: Number($('preset-keep-n').value),
    olderThanDays: Number($('preset-older-days').value),
  });
  $('preset-keep').addEventListener('click', () => selectSessions(presets().keepLast));
  $('preset-older').addEventListener('click', () => selectSessions(presets().olderThan));
  $('sessions-delete-selected').addEventListener('click', () => {
    const button = $('sessions-delete-selected');
    confirmThen(button, `delete selected (${selectedSessions.size})`,
      `delete ${selectedSessions.size}? permanent`, deleteSelectedSessions);
  });
  $('sessions-clear').addEventListener('click', () => {
    confirmThen($('sessions-clear'), 'clear all', 'delete every session?', clearSessions);
  });
  $('sessions-refresh').addEventListener('click', async () => {
    await loadSessions();
    renderSessionRows();
  });
}
