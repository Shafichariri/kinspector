/* processes: the Processes page — daemon control and banners, sibling processes, MCP and server info */
'use strict';

// --- server control -----------------------------------------------------

/**
 * Stop and restart the daemon from the page.
 *
 * `stop` is genuinely destructive to the session in progress, so it confirms first. `restart`
 * does not: it is the recovery action, and a confirm dialog on the thing you reach for when
 * something is already wrong is just friction.
 */
async function control(action) {
  const res = await controlFetch(`/api/server/${action}`);
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
 *
 * Read from the whole session, never `transactions`: under a filter that kept only older calls,
 * the filtered list dated the session by the newest call the filter happened to keep.
 */
function lastCapture() {
  if (state.lastCaptureTs) return { ts: state.lastCaptureTs, what: 'last capture' };
  const newest = [...state.allTransactions, ...state.allSignals]
    .map((r) => r.ts)
    .filter(Boolean)
    .sort()
    .pop();
  return newest ? { ts: newest, what: 'newest row in this session' } : null;
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
    // The socket drops and reconnects on its own, and its onopen clears this banner. Nothing here
    // polls: the live connection knows when the daemon is back, and a close after the grace says
    // it is not coming back (`RESTART_GRACE_MS`).
    state.restartAt = Date.now();
    showServerBanner('Restarting…', true);
  } catch (e) {
    setControlsEnabled(true);
    showServerBanner(`Could not restart the daemon: ${e.message}`, false);
  }
}

function setControlsEnabled(enabled) {
  $('server-stop').disabled = !enabled;
  $('server-restart').disabled = !enabled;
}

// --- the MCP server ------------------------------------------------------------------------

/**
 * There is no start button on purpose.
 *
 * The MCP server reads stdio and stops at EOF, so a process the daemon spawned would have no
 * client on the other end. The editor owns that lifecycle. What is useful is proving the binary
 * answers, and killing a wedged one — the latter is just the process list above it.
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
    const res = await controlFetch('/api/mcp/probe');
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

// --- sibling inspector processes ----------------------------------------------------------------

/**
 * Lists inspector processes, this daemon among them, and offers to kill the others.
 *
 * The daemon identifies them by argv token, never by a substring of the command line, so the
 * two traps documented in docs/DAEMON.md are unreachable from here, and it re-verifies each pid as
 * it kills it. The page renders what it is told and sends back pids, one request for any number.
 */
let peerList = [];
/** The pids ticked, from the list as last drawn. */
const selectedPeers = new Set();

async function loadPeers() {
  setPeersStatus('');
  try {
    renderPeers(await api('/api/peers'));
  } catch (e) {
    peerList = [];
    const list = $('peers');
    list.textContent = '';
    list.appendChild(messageRow(`could not list processes: ${e.message}`));
    updatePeerActions();
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

/**
 * The pids each preset ticks. Pure. None ever ticks this daemon, which is stopped through Stop,
 * and only `all servers` could reach an `mcp` process if roles changed: an MCP server is the
 * editor's connection, the process people kill by accident, so it is ticked only by hand.
 *
 * `stale` is every other non-MCP process that started before this daemon did, read from what
 * `/api/peers` already reports; a liveness probe would be a server feature of its own.
 */
function peerPresets(peers) {
  const self = peers.find((p) => p.self);
  const others = peers.filter((p) => !p.self);
  const notMcp = others.filter((p) => p.role !== 'mcp');
  const before = (p) => self && self.startedEpochMs != null && p.startedEpochMs != null
    && p.startedEpochMs < self.startedEpochMs;
  return {
    servers: others.filter((p) => p.role === 'serve').map((p) => p.pid),
    others: notMcp.map((p) => p.pid),
    stale: notMcp.filter(before).map((p) => p.pid),
  };
}

const messageRow = (text) => {
  const row = el('tr');
  const cell = el('td', 'muted', text);
  cell.colSpan = 7;
  row.appendChild(cell);
  return row;
};

function renderPeers(peers) {
  peerList = peers;
  const pids = new Set(peers.filter((p) => !p.self).map((p) => p.pid));
  for (const pid of [...selectedPeers]) if (!pids.has(pid)) selectedPeers.delete(pid);
  const list = $('peers');
  list.textContent = '';
  if (!peers.length) list.appendChild(messageRow('no inspector processes found'));
  for (const peer of peers) list.appendChild(peerRow(peer));
  const self = peers.find((p) => p.self);
  if (self) {
    $('server-info').textContent = `pid ${self.pid} · ${self.port == null ? 'stdio' : `port ${self.port}`}` +
      `${self.startedEpochMs ? ` · up ${uptimeOf(self.startedEpochMs)}` : ''}${self.dataDir ? ` · ${self.dataDir}` : ''}`;
  }
  updatePeerActions();
}

function peerRow(peer) {
  const row = el('tr', 'peer');
  row.dataset.pid = String(peer.pid);
  const tick = el('input');
  tick.type = 'checkbox';
  tick.disabled = peer.self;
  tick.checked = selectedPeers.has(peer.pid);
  tick.setAttribute('aria-label', `Select ${peer.role} pid ${peer.pid}`);
  tick.addEventListener('change', () => {
    if (tick.checked) selectedPeers.add(peer.pid);
    else selectedPeers.delete(peer.pid);
    updatePeerActions();
  });
  row.appendChild(cellOf(tick, 'col-check'));
  row.appendChild(cellOf(el('span', `peer-role peer-role-${peer.role}`, peer.role)));
  row.appendChild(el('td', 'peer-pid mono', String(peer.pid)));
  // `mcp` speaks over stdio and holds no port. Showing that plainly is the point: it is the
  // process people kill by accident.
  row.appendChild(el('td', 'col-secondary peer-port mono muted', peer.port == null ? 'stdio' : `:${peer.port}`));
  row.appendChild(el('td', 'col-secondary peer-uptime muted', uptimeOf(peer.startedEpochMs)));
  const data = el('td', 'col-secondary peer-data mono muted', peer.dataDir || '');
  data.title = peer.dataDir || '';
  row.appendChild(data);

  const actions = el('td', 'col-actions');
  if (peer.self) {
    actions.appendChild(el('span', 'peer-self', 'this daemon'));
    const hint = el('span', 'muted', ' use stop');
    hint.title = 'Stopping this daemon goes through Stop, which replies before shutting down.';
    actions.appendChild(hint);
  } else {
    const kill = el('button', 'btn btn-sm btn-danger', 'kill');
    kill.type = 'button';
    kill.title = `Terminate pid ${peer.pid}`;
    kill.addEventListener('click', () => confirmThen(kill, 'kill', 'sure?', () => killPeers([peer.pid])));
    actions.appendChild(kill);
  }
  row.appendChild(actions);
  return row;
}

/** The bulk button's count, the select-all box, and the warning while an MCP server is ticked. */
function updatePeerActions() {
  const button = $('peers-kill-selected');
  const n = selectedPeers.size;
  if (button.dataset.armed !== '1') button.textContent = `kill selected (${n})`;
  button.disabled = n === 0;
  const killable = peerList.filter((p) => !p.self);
  const all = $('peers-all');
  all.checked = killable.length > 0 && killable.every((p) => selectedPeers.has(p.pid));
  all.indeterminate = n > 0 && !all.checked;
  all.disabled = killable.length === 0;
  $('peers-mcp-warning').hidden = !peerList.some((p) => p.role === 'mcp' && selectedPeers.has(p.pid));
}

function selectPeers(pids) {
  selectedPeers.clear();
  for (const pid of pids) selectedPeers.add(pid);
  renderPeers(peerList);
}

/**
 * Kills [pids] in one request, the row's `kill` included, and says what happened to each. The
 * signal is asynchronous, so the list is read again rather than a success claimed: the row going is
 * what confirms it.
 */
async function killPeers(pids) {
  const what = pids.length === 1 ? `pid ${pids[0]}` : `${pids.length} processes`;
  setPeersStatus(`killing ${what}…`);
  let reply;
  try {
    const res = await controlFetch('/api/peers/kill', { json: { pids, force: false } });
    reply = await res.json();
    if (!res.ok) throw new Error(reply.error || String(res.status));
  } catch (e) {
    setPeersStatus(`could not kill ${what}: ${e.message}`, true);
    return;
  }
  const results = reply.results || [];
  const failed = results.filter((r) => r.outcome !== 'signalled');
  const node = $('peers-status');
  setPeersStatus(`signalled ${results.length - failed.length} of ${results.length}`, failed.length > 0);
  const list = el('ul', 'page-results');
  for (const r of results) {
    list.appendChild(el('li', r.outcome === 'signalled' ? 'muted' : 'error',
      `pid ${r.pid} — ${r.outcome}${r.error ? `: ${r.error}` : ''}`));
  }
  node.appendChild(list);
  for (const r of results) selectedPeers.delete(r.pid);
  setTimeout(loadPeers, 300);
}

function wireProcessesPage() {
  $('peers-refresh').addEventListener('click', loadPeers);
  $('peers-all').addEventListener('change', (e) => {
    selectPeers(e.target.checked ? peerList.filter((p) => !p.self).map((p) => p.pid) : []);
  });
  for (const button of document.querySelectorAll('#page-processes [data-preset]')) {
    button.addEventListener('click', () => selectPeers(peerPresets(peerList)[button.dataset.preset] || []));
  }
  $('peers-kill-selected').addEventListener('click', () => {
    const n = selectedPeers.size;
    confirmThen($('peers-kill-selected'), `kill selected (${n})`, `kill ${n}?`, () => killPeers([...selectedPeers]));
  });
  // Two clicks, like deleting a session: stopping ends recording for every app on this machine.
  // It replaces a `confirm()` dialog, which blocks the whole page and is easy to dismiss by reflex.
  $('server-stop').addEventListener('click', () => {
    confirmThen($('server-stop'), 'stop', 'stop daemon?', requestStop);
  });
  $('server-restart').addEventListener('click', requestRestart);
  $('mcp-probe').addEventListener('click', probeMcp);
  $('mcp-copy').addEventListener('click', () => {
    const text = $('mcp-command').textContent;
    navigator.clipboard.writeText(text).then(() => {
      const button = $('mcp-copy');
      button.textContent = 'copied';
      setTimeout(() => (button.textContent = 'copy command'), 1200);
    });
  });
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
