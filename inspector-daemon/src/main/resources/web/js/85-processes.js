/* processes: daemon control, banners, MCP, peers and server info */
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
  const res = await source.fetch(`/api/server/${action}`, {
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
  const newest = [...state.transactions, ...state.allSignals]
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
    const res = await source.fetch('/api/mcp/probe', {
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
    const res = await source.fetch(`/api/peers/${peer.pid}/kill${force ? '?force=true' : ''}`, {
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
