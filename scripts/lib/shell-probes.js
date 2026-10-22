/* Probes of the page's shell, for render-web-ui.js: the rail and its addresses, the top bar, the tab
 * bar's `views ▾`, and the Sessions, Processes and Settings pages.
 *
 * The top bar, sessions, peers, stop and endpoint-limit probes moved here from render-web-ui.js when
 * the settings dialog became pages; each now opens its page through the rail first, as a person
 * would. Every probe that presses a button that sends something stubs `fetch` itself for that one
 * route and puts the real one back before it returns (docs/decisions/web-ui.md): the run where a
 * confirm step is broken is the run that would otherwise delete a session or stop the daemon this
 * script reads. The page is left on Traffic, on the session it started on.
 *
 * Each returns `[label, value]` pairs, one per line the caller prints, or `[label, value, 'note']`
 * for a line that is information rather than a check.
 */

const { choose, waitFor } = require('./long-session-probes');
const { probeSwitchWindowByRoute } = require('./current-guard-probes');
const { probeSessionRowTime } = require('./polish-probes');

const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const press = async (ctx, node, ms = 80) => {
  node.dispatchEvent(new ctx.window.MouseEvent('click', { bubbles: true, cancelable: true }));
  await pause(ms);
};
const shownPage = (doc) => [...doc.querySelectorAll('#pages > .page')].filter((p) => !p.hidden).map((p) => p.id);
const railLink = (doc, page) => doc.querySelector(`#rail .rail-item[data-page="${page}"]`);

/** Opens [page] through its rail link and waits for it to be the one shown. */
async function goTo(ctx, page) {
  await press(ctx, railLink(ctx.doc, page), 120);
  await waitFor(() => shownPage(ctx.doc).join() === `page-${page}`, 3000);
}

/**
 * Replaces the page's `fetch` for requests [match] accepts, answering each with [answer]'s
 * `[status, body]`, and counts them with what was sent. Everything else reaches the daemon.
 */
function stubFetch(ctx, match, answer) {
  const { window } = ctx;
  const real = window.fetch;
  const sent = [];
  window.fetch = (path, init = {}) => {
    if (!match(String(path), init)) return real(path, init);
    sent.push({ path: String(path), method: init.method || 'GET', headers: init.headers || {}, body: init.body });
    const [status, body] = answer(sent.length);
    // Node's `Response`: jsdom has none, and the page reads only `ok`, `status`, `json` and `text`.
    return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));
  };
  return { sent, restore: () => { window.fetch = real; } };
}
const hasHeader = (s) => s.headers['X-Inspector-Control'] === '1';
/**
 * Every request that deletes a session, by any route, so a page that broke the bulk delete into
 * single deletes is counted here rather than deleting sessions from the archive this probe reads.
 */
const deletesSessions = (p, init) => init.method === 'DELETE' || /\/api\/sessions\/(delete|clear)$/.test(p);
/** Every request that kills a process, the single route and the bulk one. */
const killsPeers = (p) => /\/api\/peers\/(\d+\/)?kill/.test(p);

/**
 * The top bar's controls, pressed. The picker's option, the count's noun, the connection dot and the
 * live toggle; stop, restart and delete moved to their pages, and the export menu to the toolbar's
 * `⋮` (`exportMenu` in lib/list-room-probes.js).
 */
async function probeTopBar(ctx) {
  const { window, doc, settle } = ctx;
  const lines = [];
  const option = doc.querySelector('#session-picker option');
  const hasSession = Boolean(option);
  lines.push(['sessionOption', !hasSession ? 'n/a (no sessions)' : option.title && option.textContent !== option.title
    && / · \d+ calls?$/.test(option.textContent)
    ? `yes ("${option.textContent}", id in the tooltip)`
    : `NO - "${option.textContent}"`]);
  const counts = doc.getElementById('counts').textContent;
  lines.push(['countsNoun', !hasSession ? 'n/a (no sessions)' : / of \d+ calls?$/.test(counts) ? `yes ("${counts}")` : `NO - "${counts}"`]);
  const conn = doc.getElementById('conn').dataset.state;
  lines.push(['conn', conn === 'connected' ? 'yes (connected)' : `NO - ${conn}`]);

  // Live: the button names its state, and resuming re-reads the session.
  const live = doc.getElementById('live-btn');
  await press(ctx, live);
  const paused = live.textContent.trim() === 'paused' && live.getAttribute('aria-pressed') === 'false';
  let reread = false;
  const realFetch = window.fetch;
  window.fetch = (path, init) => {
    if (String(path).includes('/transactions')) reread = true;
    return realFetch(path, init);
  };
  await press(ctx, live);
  await settle();
  window.fetch = realFetch;
  // Resuming re-reads the session on screen; with none there is nothing to re-read.
  lines.push(['liveToggle', paused && live.textContent.trim() === 'live' && (reread || !hasSession)
    ? `yes (live -> paused -> live${hasSession ? ', and resuming re-read the session' : '; no session to re-read'})`
    : `NO - paused ${paused}, back to "${live.textContent.trim()}", re-read ${reread}`]);
  return lines;
}

/**
 * Sessions can be deleted from the Sessions page, and one being recorded cannot. Nothing is really
 * deleted: the probe runs against whatever archive it is pointed at, so the destructive buttons are
 * pressed only against a stub, and only to see that they arm before they fire.
 */
async function probeSessionsPage(ctx) {
  const { window, doc, ORIGIN, settle, pageState } = ctx;
  const lines = [];
  await goTo(ctx, 'sessions');
  await waitFor(() => doc.querySelector('#session-rows .session-row'), 3000);
  const rows = () => [...doc.querySelectorAll('#session-rows .session-row')];
  lines.push(['session rows', rows().length, 'note']);
  lines.push(...probeSessionRowTime(ctx)); // lib/polish-probes.js; reads the rows, moves nothing

  // The folder the daemon archives to is `<data>/sessions`, as `config.json` is `<data>/config.json`,
  // so the truth is the config route's path and not the route the page reads. CI's fixture runs with
  // its own `--data`, where a page still saying `~/.inspector/sessions` is wrong.
  const folder = ((await fetch(`${ORIGIN}/api/config`).then((r) => r.json()).catch(() => ({}))).path || '')
    .replace(/config\.json$/, 'sessions');
  const note = () => doc.querySelector('#page-sessions .settings-note').textContent.replace(/\s+/g, ' ').trim();
  lines.push(['sessions folder', folder && await waitFor(() => note().includes(folder.replace(/\s+/g, ' ')), 3000)
    ? `yes (${folder})` : `NO - the note says "${note().slice(0, 70)}…", the daemon's folder is "${folder}"`]);

  // The daemon refuses to delete a session with no `endedAt`, so that is what must be disabled —
  // read from the daemon's own list, not from the page.
  const unended = (await fetch(`${ORIGIN}/api/sessions`).then((r) => r.json()).catch(() => []))
    .filter((meta) => !meta.endedAt).length;
  const disabled = rows().filter((r) => r.querySelector('.row-delete').disabled).length;
  lines.push(['deletes disabled', disabled === unended
    ? `yes (${disabled}, one per session with no endedAt)`
    : `NO - ${disabled} disabled, ${unended} sessions with no endedAt`]);

  const clearAll = doc.getElementById('sessions-clear');
  const idle = clearAll.textContent;
  // Stubbed as every destructive press here is: the run where the confirm step broke would clear
  // the archive this probe reads.
  const clearing = stubFetch(ctx, deletesSessions, () => [200, { deleted: [], kept: [], remaining: 0, freedBytes: 0 }]);
  await press(ctx, clearAll);
  clearing.restore();
  const armed = clearAll.textContent !== idle && clearAll.classList.contains('armed') && clearing.sent.length === 0;
  // Disarm without firing: a second click here would wipe the archive this probe is reading.
  clearAll.dataset.armed = '0';
  clearAll.textContent = idle;
  clearAll.classList.remove('armed');
  lines.push(['clear all arms', armed ? 'yes (one click armed it, it did not delete)' : 'NO - the first click was not a confirmation step']);

  // The header `✕` left the top bar for these rows on purpose: deleting is archive management.
  const withX = rows().filter((r) => r.querySelector('.row-delete')?.textContent === '✕').length;
  lines.push(['row delete', withX === rows().length && withX > 0 && !doc.getElementById('session-delete')
    ? `yes (${withX} rows each with a ✕, none in the top bar)`
    : `NO - ${withX} of ${rows().length} rows have a ✕, top bar ✕ ${Boolean(doc.getElementById('session-delete'))}`]);

  // A row's ✕ arms and names nothing sent on one click.
  const del = rows().map((r) => r.querySelector('.row-delete')).find((b) => !b.disabled);
  if (!del) {
    lines.push(['deleteArms', 'n/a (every session is recording)']);
  } else {
    const stub = stubFetch(ctx, deletesSessions, () => [200, {}]);
    await press(ctx, del);
    const delArmed = del.textContent === 'sure?' && del.classList.contains('armed') && stub.sent.length === 0;
    stub.restore();
    del.dataset.armed = '0';
    del.textContent = '✕';
    del.classList.remove('armed');
    lines.push(['deleteArms', delArmed ? 'yes ("sure?", nothing sent)' : `NO - "${del.textContent}", ${stub.sent.length} sent`]);
  }

  lines.push(['sessions bulk', await bulkDelete(ctx, rows)]);
  await goTo(ctx, 'traffic');
  await settle();
  return lines;

  /** Two ended sessions ticked, deleted in one request against a stub that refuses one. */
  async function bulkDelete() {
    const ticks = rows().filter((r) => !r.querySelector('input[type=checkbox]').disabled);
    if (ticks.length < 2) return 'n/a (fewer than two sessions that are not recording)';
    const shown = pageState().sessionId;
    const two = ticks.slice(0, 2).map((r) => r.dataset.id);
    // The one deleted is the one not on screen, so the page has no reason to move off its session.
    const [gone, kept] = two[0] === shown ? [two[1], two[0]] : [two[0], two[1]];
    for (const r of ticks.slice(0, 2)) await press(ctx, r.querySelector('input[type=checkbox]'), 20);
    const button = doc.getElementById('sessions-delete-selected');
    const label = button.textContent;
    const stub = stubFetch(ctx, deletesSessions, () => [200, {
      results: two.map((id) => (id === gone
        ? { id, outcome: 'deleted', freedBytes: 2048 }
        : { id, outcome: 'refused', error: 'probe: refused' })),
      deleted: [gone], remaining: 1, freedBytes: 2048,
    }]);
    let verdict;
    try {
      await press(ctx, button);
      const armedOnly = stub.sent.length === 0 && button.classList.contains('armed');
      await press(ctx, button, 300);
      await settle();
      const status = doc.getElementById('sessions-status').textContent;
      const sent = stub.sent[0];
      const body = sent && sent.body;
      verdict = label !== 'delete selected (2)' ? `NO - the button read "${label}" with two ticked`
        : !armedOnly ? `NO - the first click sent ${stub.sent.length} request(s)`
          : stub.sent.length !== 1 || sent.method !== 'POST' ? `NO - ${stub.sent.length} requests, the first a ${sent?.method}`
            : body !== JSON.stringify({ ids: two }) ? `NO - sent ${body}, where {"ids":${JSON.stringify(two)}} was due`
              : !hasHeader(sent) ? 'NO - sent without the control header'
                : !status.includes(gone) || !status.includes(kept) ? `NO - the status reads "${status}"`
                  : `yes (one click armed it, the second sent one POST {"ids":[2 ids]} with the header; the status names both: "${status.slice(0, 70)}…")`;
    } finally {
      stub.restore();
    }
    for (const r of rows()) {
      const tick = r.querySelector('input[type=checkbox]');
      if (tick.checked) await press(ctx, tick, 20);
    }
    if (pageState().sessionId !== shown) choose(ctx, shown);
    return verdict;
  }
}

/**
 * The Processes page: the process list, this daemon's stop and restart, and the banner a lost daemon
 * paints. Stop, restart and every kill are stubbed; the banner lines replay the page's own socket
 * handlers, whose redial opens a new stub socket a second later, so each is read straight after its
 * replay and the page is let reconnect before the next.
 */
async function probeProcessesPage(ctx) {
  const { window, doc, settle, lastSocket, pageState } = ctx;
  const lines = [];
  await goTo(ctx, 'processes');
  await waitFor(() => doc.querySelector('#peers .peer'), 3000);
  const peerRows = [...doc.querySelectorAll('#peers .peer')];
  // Whatever inspector processes this machine is running; not a property of the page.
  lines.push(['peers listed', peerRows.length, 'note']);
  lines.push(['peer roles', [...new Set(peerRows.map((n) => n.querySelector('.peer-role')?.textContent || '?'))].sort().join(', ') || 'none', 'note']);
  // This daemon is always one of the peers, so a list with no row marked as it checked nothing.
  const selfRows = peerRows.filter((n) => n.querySelector('.peer-self'));
  const selfKill = selfRows.filter((n) => [...n.querySelectorAll('button')].some((b) => b.textContent === 'kill')).length;
  lines.push(['self has kill btn', !selfRows.length ? 'NO - found no row marked as this daemon, so this checked nothing'
    : selfKill === 0 ? `yes (0 on ${selfRows.length} row) (must be 0)` : `NO - ${selfKill} (must be 0)`]);

  // Stop: one click arms, the second stops, and the page says so in a way it cannot miss.
  const stop = doc.getElementById('server-stop');
  const banner = doc.getElementById('server-banner');
  const stub = stubFetch(ctx, (p) => p.includes('/api/server/stop'), () => [200, {}]);
  await press(ctx, stop);
  const armedOnly = stop.textContent === 'stop daemon?' && stop.classList.contains('armed') && stub.sent.length === 0;
  await press(ctx, stop);
  await pause(80);
  stub.restore();
  lines.push(['stopArms', armedOnly ? 'yes (one click armed it, nothing sent)' : `NO - "${stop.textContent}", ${stub.sent.length} sent`]);
  lines.push(['stopped', stub.sent.length === 1 && !banner.hidden && banner.classList.contains('big')
    && banner.querySelector('.banner-title')?.textContent === 'daemon stopped'
    && doc.body.classList.contains('stopped') && doc.getElementById('conn').dataset.state === 'stopped'
    ? 'yes (banner, page dimmed, connection reads stopped)'
    : `NO - ${stub.sent.length} stop requests, banner "${banner.textContent.slice(0, 40)}"`]);
  // The banner must not offer a restart: the process that would receive it has exited.
  lines.push(['noRestartOffered', banner.querySelectorAll('button').length === 0 && /inspector serve/.test(banner.textContent)
    ? 'yes (says to run `inspector serve`, no button)'
    : 'NO - the banner offers a control nothing can answer']);
  lines.push(['lastCapture', banner.querySelector('.banner-age')
    ? `yes ("${banner.querySelector('.banner-text').textContent}")`
    : 'n/a (no row to date it by)']);
  // The daemon comes back: the page's own onopen must clear all of it.
  if (lastSocket() && lastSocket().onopen) lastSocket().onopen();
  await pause(150);
  await settle();
  lines.push(['recovers', banner.hidden && !doc.body.classList.contains('stopped')
    && doc.getElementById('conn').dataset.state === 'connected' && !stop.disabled
    ? 'yes (banner gone, page undimmed, controls back)'
    : 'NO - the page stayed stopped after the daemon came back']);

  lines.push(...await bannerLines());
  lines.push(['processes bulk', await bulkKill()]);
  await goTo(ctx, 'traffic');
  await settle();
  return lines;

  async function bannerLines() {
    const out = [];
    const lost = () => !banner.hidden && /^Lost the daemon/.test(banner.textContent);
    const says = () => (banner.hidden ? '(hidden)' : `"${banner.textContent.slice(0, 50)}"`);
    const reconnected = async () => {
      await waitFor(() => pageState().serverState === 'running' && banner.hidden, 6000);
      await settle();
    };

    // From running: a drop, a reopen, and a second drop, the roadmap's twice-in-a-row pair.
    let socket = lastSocket();
    socket.onclose();
    const first = lost() ? null : `the first drop left ${says()}`;
    socket.onopen();
    const cleared = banner.hidden ? null : `the reopen left ${says()}`;
    socket.onclose();
    const second = lost() ? null : `the second drop left ${says()}`;
    const wrong = [first, cleared, second].filter(Boolean);
    out.push(['banner every drop', wrong.length ? `NO - ${wrong.join('; ')}` : 'yes (drop: lost; reopen: cleared; drop again: lost)']);
    await reconnected();

    // Away, a stop that fails paints its own message; the next close must paint the lost one again.
    socket = lastSocket();
    socket.onclose();
    const failing = stubFetch(ctx, (p) => p.includes('/api/server/stop'), () => [500, { error: 'probe: refused' }]);
    await press(ctx, stop, 20);
    await press(ctx, stop, 60);
    failing.restore();
    const failed = /^Could not stop/.test(banner.textContent) ? null : `the failed stop left ${says()}`;
    socket.onclose();
    out.push(['banner after failure', failed ? `NO - ${failed}`
      : lost() ? 'yes ("Could not stop the daemon…", then the next close painted the lost banner)'
        : `NO - after the failed stop the next close left ${says()}`]);
    await reconnected();

    // A restart that never comes back: inside the grace it reads Restarting…, past it, lost.
    const restarting = stubFetch(ctx, (p) => p.includes('/api/server/restart'), () => [200, {}]);
    await window.requestRestart();
    restarting.restore();
    socket = lastSocket();
    socket.onclose();
    const young = /^Restarting/.test(banner.textContent) ? null : `inside the grace the close left ${says()}`;
    pageState().restartAt -= 60000;
    socket.onclose();
    out.push(['banner restart lost', young ? `NO - ${young}`
      : lost() ? 'yes ("Restarting…" inside the grace; 60 s on, the next close painted the lost banner)'
        : `NO - a restart 60 s old still reads ${says()} after a close`]);
    await reconnected();
    return out;
  }

  /** A stubbed list of this daemon, two servers and an MCP server; `all servers`, then kill. */
  async function bulkKill() {
    const peers = [
      { pid: 9001, role: 'serve', port: 9101, startedEpochMs: 2000, self: true },
      { pid: 9002, role: 'serve', port: 9102, startedEpochMs: 1000, self: false },
      { pid: 9003, role: 'serve', port: 9103, startedEpochMs: 3000, self: false },
      { pid: 9004, role: 'mcp', port: null, startedEpochMs: 1500, self: false },
    ];
    const listing = stubFetch(ctx, (p, init) => p.endsWith('/api/peers') && (init.method || 'GET') === 'GET', () => [200, peers]);
    const kills = stubFetch(ctx, killsPeers, () => [200, {
      results: [{ pid: 9002, outcome: 'signalled' }, { pid: 9003, outcome: 'noSuchProcess', error: 'no live process with pid 9003' }],
    }]);
    let verdict;
    try {
      await press(ctx, doc.getElementById('peers-refresh'), 200);
      await press(ctx, doc.querySelector('#page-processes [data-preset="servers"]'), 40);
      const ticked = [...doc.querySelectorAll('#peers .peer')]
        .filter((r) => r.querySelector('input[type=checkbox]').checked).map((r) => Number(r.dataset.pid));
      const button = doc.getElementById('peers-kill-selected');
      const label = button.textContent;
      await press(ctx, button);
      const armedOnly = kills.sent.length === 0 && button.classList.contains('armed');
      // Past the reload the kill schedules 300 ms on: the per-pid results must still be there,
      // or a refused kill leaves its row listed with no reason.
      await press(ctx, button, 700);
      const sent = kills.sent[0];
      const status = doc.getElementById('peers-status').textContent;
      const kept = status.includes('signalled 1 of 2') && status.includes('pid 9003 — noSuchProcess');
      const mcp = [...doc.querySelectorAll('#peers .peer')].find((r) => r.dataset.pid === '9004');
      await press(ctx, mcp.querySelector('input[type=checkbox]'), 40);
      const warned = !doc.getElementById('peers-mcp-warning').hidden;
      verdict = JSON.stringify(ticked) !== '[9002,9003]' ? `NO - all servers ticked ${JSON.stringify(ticked)}, where [9002,9003] was due`
        : label !== 'kill selected (2)' ? `NO - the button read "${label}"`
          : !armedOnly ? `NO - the first click sent ${kills.sent.length} request(s)`
            : kills.sent.length !== 1 || sent.body !== JSON.stringify({ pids: [9002, 9003], force: false })
              ? `NO - ${kills.sent.length} kill requests, the first ${sent?.body}`
              : !hasHeader(sent) ? 'NO - sent without the control header'
                : !kept ? `NO - after the reload the status read "${status}", where "signalled 1 of 2" and pid 9003's refusal were due`
                  : !warned ? 'NO - an MCP server ticked by hand drew no warning'
                    : 'yes (all servers ticked 9002 and 9003, not this daemon or MCP; one POST {"pids":[…]} on the second click; the per-pid results outlive the reload; MCP ticked by hand warns)';
    } finally {
      listing.restore();
      kills.restore();
    }
    await press(ctx, doc.getElementById('peers-refresh'), 300);
    return verdict;
  }
}

/**
 * The Settings page: this browser's endpoint-chip limit, driven through its input as a person sets
 * it, and the daemon's config.json editor against the real `GET /api/config` and a stubbed `PUT`.
 */
async function probeSettingsPage(ctx) {
  const { window, doc, ORIGIN, settle } = ctx;
  const lines = [];
  const chipCount = () => doc.querySelectorAll('#endpoint-chips .chip').length;
  const chipsBefore = chipCount();
  await goTo(ctx, 'settings');
  // Through the real `change` event: it covers the listener, the localStorage write and the
  // re-render together. Asserting on `state.endpointLimit` would only prove a number was stored.
  const input = doc.getElementById('endpoint-limit');
  const setLimit = async (n) => {
    input.value = String(n);
    input.dispatchEvent(new window.Event('change', { bubbles: true }));
    await settle();
  };
  const original = input.value;
  await setLimit(3);
  const capped = chipCount();
  const summary = doc.getElementById('endpoint-summary').textContent;
  await setLimit(0);
  const off = chipCount() === 0 && doc.getElementById('endpoint-chips').hidden;
  await setLimit(original);
  lines.push(['limit 3 caps to', capped === 3 ? 'yes (3) (must be 3)' : `NO - ${capped} (must be 3)`]);
  lines.push(['limit 0 hides', String(off)]);
  lines.push(['limit restored to', chipCount() === chipsBefore ? `yes (${chipCount()})` : `NO - ${chipCount()}, was ${chipsBefore}`]);
  lines.push(['limit summary', summary, 'note']);
  lines.push(['settings config', await configEditor()]);
  await goTo(ctx, 'traffic');
  await settle();
  return lines;

  async function configEditor() {
    const config = await fetch(`${ORIGIN}/api/config`).then((r) => r.json());
    await waitFor(() => doc.querySelector('#config-fields input[data-key="maxSessions"]'), 3000);
    const field = (key) => doc.querySelector(`#config-fields input[data-key="${key}"]`);
    const nowOf = (key) => field(key)?.closest('.config-row')?.querySelector('.config-now')?.textContent || '';
    const shown = ['port', 'maxSessions', 'maxTotalMb'].filter((k) => nowOf(k).includes(String(config.effective[k])));
    if (shown.length !== 3) return `NO - the running values shown are ${['port', 'maxSessions', 'maxTotalMb'].map(nowOf).join(' | ')}`;
    if (!field('port').readOnly && !field('port').disabled) return 'NO - the port can be edited';
    const save = doc.getElementById('config-save');
    const status = doc.getElementById('config-status');
    const sessions = field('maxSessions');
    sessions.value = String(config.effective.maxSessions + 1);
    const ok = stubFetch(ctx, (p, init) => p.endsWith('/api/config') && init.method === 'PUT', () => [200, config]);
    await press(ctx, save, 200);
    ok.restore();
    const put = ok.sent[0];
    if (ok.sent.length !== 1 || !hasHeader(put) || put.headers['Content-Type'] !== 'application/json'
      || JSON.parse(put.body || '{}').maxSessions !== config.effective.maxSessions + 1) {
      return `NO - ${ok.sent.length} PUTs, header ${put ? hasHeader(put) : '-'}, body ${put?.body}`;
    }
    // A save redraws the form from the daemon's answer, so the field is a new node now.
    field('maxSessions').value = String(config.effective.maxSessions + 2);
    const refused = 'probe: maxSessions must be a whole number of at least 1';
    const bad = stubFetch(ctx, (p, init) => p.endsWith('/api/config') && init.method === 'PUT', () => [400, { error: refused }]);
    await press(ctx, save, 200);
    bad.restore();
    const said = status.textContent;
    const verdict = said === refused && status.classList.contains('error')
      ? `yes (running ${config.effective.port}/${config.effective.maxSessions}/${config.effective.maxTotalMb} shown, port read-only; one PUT with the header and a JSON body; a 400 shows "${refused.slice(7, 28)}…")`
      : `NO - after a 400 the form says "${said}"`;
    await window.loadConfig();
    return verdict;
  }
}

/**
 * The rail and the addresses it writes: what a load with no address shows, each rail link, Back,
 * a view and a session named by an address, an address this build does not know, and row 4's
 * switch-window line run again with the switch made through the address.
 */
async function probeRoutes(ctx, atLoad) {
  const { window, doc, settle, pageState } = ctx;
  const lines = [];
  // The page had a left rail once, holding the filter and a Now panel, and lost it because it spent
  // a fifth of the window on views it could not act on — the Now panel alone ran 1341px. The rail
  // now is navigation, which applies on every page; a view control put back in it fails here.
  const rail = doc.getElementById('rail');
  const controls = rail ? [...rail.querySelectorAll('a, button, input, select, textarea, [tabindex], .chip')] : [];
  const stray = controls.filter((c) => !(c.matches('a.rail-item[href^="#/"]') || c.id === 'rail-toggle'));
  lines.push(['rail is navigation', !rail ? 'NO - there is no #rail'
    : !controls.some((c) => c.matches('a.rail-item[href^="#/"]')) ? 'NO - found no rail link, so this checked nothing'
    : stray.length ? `NO - the rail holds ${stray.map((c) => c.id || c.tagName.toLowerCase()).join(', ')}`
      : `yes (${controls.length - 1} links and the collapse button, nothing else)`]);
  lines.push(['route default', atLoad]);

  const wrongPages = [];
  const links = [...doc.querySelectorAll('#rail .rail-item')];
  if (!links.length) wrongPages.push('found no rail link, so this checked nothing');
  for (const link of links) {
    await goTo(ctx, link.dataset.page);
    const shown = shownPage(doc);
    if (window.location.hash !== link.getAttribute('href') || shown.join() !== `page-${link.dataset.page}`
      || link.getAttribute('aria-current') !== 'page') {
      wrongPages.push(`${link.dataset.page}: hash ${window.location.hash}, shown ${shown.join(',') || 'none'}`);
    }
  }
  lines.push(['route pages', wrongPages.length ? `NO - ${wrongPages.join('; ')}` : `yes (each of ${links.length} rail links wrote its href and showed only its page)`]);

  await goTo(ctx, 'sessions');
  await goTo(ctx, 'processes');
  window.history.back();
  const back = await waitFor(() => shownPage(doc).join() === 'page-sessions', 3000);
  lines.push(['route back', back ? 'yes (sessions -> processes -> Back shows sessions)' : `NO - Back showed ${shownPage(doc).join(',')}, ${window.location.hash}`]);
  await goTo(ctx, 'traffic');
  await settle();

  // A view by address, then a tab written as one: pushed, so Back returns to it.
  window.location.hash = '#/traffic/path';
  const pathShown = await waitFor(() => pageState().view === 'path'
    && doc.querySelector('#tabs .tab.active')?.dataset.view === 'path', 3000);
  const before = window.history.length;
  await press(ctx, doc.querySelector('#tabs .tab[data-view="all"]'), 300);
  const wrote = window.location.hash === '#/traffic/all' && window.history.length === before + 1;
  lines.push(['route view', pathShown && wrote ? 'yes (#/traffic/path chose the path tab; the timeline tab pushed #/traffic/all)'
    : `NO - path chosen ${pathShown}, after the tab ${window.location.hash} with history ${before} -> ${window.history.length}`]);
  await press(ctx, doc.querySelector('#tabs .tab[data-view="network"]'), 300);
  await settle();

  window.location.hash = '#/nope';
  const fellBack = await waitFor(() => window.location.hash === '#/traffic' && shownPage(doc).join() === 'page-traffic', 3000);
  lines.push(['route unknown', fellBack ? 'yes (#/nope showed traffic and was rewritten #/traffic)' : `NO - ${window.location.hash}, shown ${shownPage(doc).join(',')}`]);

  lines.push(['route session', await routeSession()]);
  lines.push(['route switch window', await probeSwitchWindowByRoute(ctx)]);
  window.history.replaceState(null, '', '#/traffic');
  await settle();
  return lines;

  /** The fixture's other session, named by an address; then a picker switch rewrites it in place. */
  async function routeSession() {
    const picker = doc.getElementById('session-picker');
    const from = picker.value;
    const other = [...picker.options].map((o) => o.value).find((v) => v !== from);
    if (!other) return 'n/a (one session)';
    window.location.hash = `#/traffic/network?session=${encodeURIComponent(other)}`;
    const switched = await waitFor(() => picker.value === other && pageState().sessionId === other
      && pageState().signalSessionId === other, 20000);
    await settle();
    const length = window.history.length;
    choose(ctx, from);
    await waitFor(() => pageState().signalSessionId === from, 20000);
    await settle();
    const rewritten = window.location.hash.endsWith(`session=${encodeURIComponent(from)}`) && window.history.length === length;
    return switched && rewritten
      ? `yes (the address switched to ${other}; the picker's switch back rewrote session= in place)`
      : `NO - switched ${switched}; after the picker ${window.location.hash}, history ${length} -> ${window.history.length}`;
  }
}

/** What a load with no address showed, read before any probe moves the page. */
function routeAtLoad(ctx) {
  const { doc, window } = ctx;
  const shown = shownPage(doc);
  const current = [...doc.querySelectorAll('#rail .rail-item[aria-current="page"]')].map((a) => a.dataset.page);
  return shown.join() === 'page-traffic' && current.join() === 'traffic' && /^(#\/traffic)?(\?|$)/.test(window.location.hash)
    ? 'yes (no address: traffic shown, the other three hidden, traffic is the current rail link)'
    : `NO - shown ${shown.join(',')}, current ${current.join(',') || 'none'}, hash "${window.location.hash}"`;
}

/** `views ▾` lists every tab's view with its count, and choosing one shows it and closes the menu. */
async function probeTabsMenu(ctx) {
  const { doc, settle, pageState } = ctx;
  const more = doc.getElementById('tabs-more');
  if (!more) return 'NO - no #tabs-more';
  const tabs = [...doc.querySelectorAll('#tabs .tab')].map((t) => ({ view: t.dataset.view, count: t.querySelector('.tab-count')?.textContent }));
  const items = () => [...more.querySelectorAll('.menu-item')];
  const listed = items().map((i) => i.dataset.view);
  const missing = tabs.filter((t) => !listed.includes(t.view) || (t.count && !items().find((i) => i.dataset.view === t.view).textContent.includes(t.count)));
  if (missing.length || listed.length !== tabs.length) {
    return `NO - the menu lists ${listed.join(',')}; the tabs are ${tabs.map((t) => t.view).join(',')}`;
  }
  const target = tabs.find((t) => t.view !== pageState().view && t.view !== 'network') || tabs[0];
  await press(ctx, more.querySelector('button'), 40);
  const item = items().find((i) => i.dataset.view === target.view);
  await press(ctx, item, 400);
  const chosen = pageState().view === target.view && doc.getElementById('tabs-more').querySelector('.menu').hidden;
  await press(ctx, doc.querySelector('#tabs .tab[data-view="network"]'), 300);
  await settle();
  return chosen ? `yes (${tabs.length} views with their counts; choosing ${target.view} showed it and closed the menu)`
    : `NO - after choosing ${target.view} the view is ${pageState().view}`;
}

/** The picker's face: the selected option's time, whole, its text as the tooltip; and after a switch. */
async function probePickerFace(ctx) {
  const { doc, settle, pageState } = ctx;
  const picker = doc.getElementById('session-picker');
  const read = () => {
    const option = picker.options[picker.selectedIndex];
    const time = (option?.textContent.split(' · ').find((part) => /^\d\d:\d\d /.test(part))) || '';
    const face = doc.querySelector('.picker-face');
    const shown = face.querySelector('.picker-time').textContent.replace(/^● /, '');
    return { ok: shown === time && face.title === option.textContent, shown, title: face.title, text: option.textContent };
  };
  const first = read();
  const from = picker.value;
  const other = [...picker.options].map((o) => o.value).find((v) => v !== from);
  if (!other) return first.ok ? `yes ("${first.shown}"; one session, no switch)` : `NO - the face shows "${first.shown}" for "${first.text}"`;
  choose(ctx, other);
  await waitFor(() => pageState().signalSessionId === other, 20000);
  const second = read();
  choose(ctx, from);
  await waitFor(() => pageState().signalSessionId === from, 20000);
  await settle();
  return first.ok && second.ok && first.title !== second.title
    ? `yes ("${first.shown}", then "${second.shown}" after the switch, each with its option as the tooltip)`
    : `NO - before: "${first.shown}" for "${first.text}"; after the switch: "${second.shown}", title "${second.title}" for "${second.text}"`;
}

module.exports = {
  probeTopBar, probeSessionsPage, probeProcessesPage, probeSettingsPage, probeRoutes, routeAtLoad,
  probeTabsMenu, probePickerFace,
};
