'use strict';
/* Probes of the web UI polish, for render-web-ui.js: what the polish rules draw, read in jsdom.
 * Sizes and widths are Chrome's to judge (`lib/polish-cases.js`); these read the markup.
 *
 * Each returns `[label, value]` pairs, as `lib/shell-probes.js` does, and leaves the page as it was.
 */

/**
 * The Sessions page's row leads with the start time, as the picker does: the label's first child is
 * `.session-row-time` holding `sessionLabelParts(meta).when`, and the app follows it. An error-free
 * session's errors cell reads `0`, as Stats prints `0%`, rather than nothing. Read on the page as
 * drawn; which sessions are error-free is the daemon's list (`state.sessions`), never the cells read.
 */
function probeSessionRowTime(ctx) {
  const { window, doc, pageState } = ctx;
  const rows = [...doc.querySelectorAll('#session-rows .session-row')];
  if (!rows.length) return [['session row time', 'NO - found no session row, so this checked nothing']];
  const sessions = pageState().sessions;
  const bad = [];
  let errorFree = 0;
  for (const row of rows) {
    const meta = sessions.find((m) => m.sessionId === row.dataset.id);
    if (!meta) { bad.push(`${row.dataset.id} is not in the session list`); continue; }
    const when = window.sessionLabelParts(meta).when;
    const first = row.querySelector('.session-row-label')?.firstElementChild;
    if (!first || !first.classList.contains('session-row-time') || first.textContent !== when) {
      bad.push(`${meta.sessionId} leads with ${first ? `${first.className} "${first.textContent}"` : 'no element'}, not the time "${when}"`);
    }
    if (!meta.errorCount) {
      errorFree++;
      const cell = row.querySelector('.session-errors');
      if (!cell || cell.textContent !== '0') bad.push(`${meta.sessionId} has no errors and its cell reads "${cell ? cell.textContent : 'nothing'}"`);
    }
  }
  return [['session row time', bad.length ? `NO - ${bad.join('; ')}`
    : `yes (${rows.length} rows lead with their start time; ${errorFree ? `${errorFree} error-free read 0` : 'n/a for 0: every session has errors'})`]];
}

const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const shown = (doc) => [...doc.querySelectorAll('#pages > .page')].filter((p) => !p.hidden).map((p) => p.id.replace(/^page-/, ''));
// Each page's key, as the design gave them: a second copy on purpose, so a key moved in `PAGE_KEYS`
// is a red line here rather than a legend that agrees with whatever the table says.
const KEYS = { traffic: 't', stats: 's', findings: 'f', diff: 'd', sessions: 'a', processes: 'p', settings: ',' };

/** Presses [keys] one by one on [target] (the body unless given), as keydowns that bubble to the document. */
async function keys(ctx, list, target = ctx.doc.body) {
  for (const key of list) {
    target.dispatchEvent(new ctx.window.KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
    await pause(30);
  }
}

async function waitFor(ready, ms = 3000) {
  const deadline = Date.now() + ms;
  while (Date.now() < deadline && !ready()) await pause(50);
  return ready();
}

/**
 * `g` and a page's letter reach each page from Traffic, its heading focused as `navigate` focuses it,
 * and `g t` comes back; on Traffic, `g g` selects the first row of `orderedRows()`, from the last.
 */
async function pageKeysLive(ctx) {
  const { window, doc } = ctx;
  const pages = window.eval('PAGES').map((p) => p.id);
  const bad = [];
  let reached = 0;
  for (const page of pages.filter((p) => p !== 'traffic')) {
    // Nothing focused, so a heading focused by an earlier probe cannot pass for one `navigate` focused.
    doc.activeElement?.blur?.();
    await keys(ctx, ['g', KEYS[page]]);
    const there = await waitFor(() => shown(doc).join() === page && doc.activeElement?.id === `${page}-title`);
    if (there) reached++;
    else bad.push(`g ${KEYS[page]} left ${shown(doc).join() || 'no page'} shown, focus on ${doc.activeElement?.id || doc.activeElement?.tagName}`);
    await keys(ctx, ['g', 't']);
    if (!await waitFor(() => shown(doc).join() === 'traffic')) bad.push(`g t from ${page} left ${shown(doc).join()} shown`);
  }
  const rows = window.eval('orderedRows()'); // a `const`, which only the page's global scope can name
  if (rows.length < 2) return bad.length ? `NO - ${bad.join('; ')}` : `n/a for g g (${rows.length} rows); ${reached} pages reached`;
  // The last row clicked, so `g g` has somewhere to move from. `move` scrolls the row it selects into
  // view, which jsdom does not implement and every browser does; stubbed only while this runs.
  const last = doc.querySelector(`#list .row[data-id="${rows[rows.length - 1].id}"]`);
  last?.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await pause(100);
  const fromLast = ctx.pageState().selectedId === rows[rows.length - 1].id;
  const proto = window.Element.prototype;
  const scrolls = Object.prototype.hasOwnProperty.call(proto, 'scrollIntoView');
  if (!scrolls) proto.scrollIntoView = () => {};
  await keys(ctx, ['g', 'g']);
  if (!scrolls) delete proto.scrollIntoView;
  const top = ctx.pageState().selectedId;
  if (!fromLast || top !== rows[0].id) bad.push(`g g selected ${top ?? 'nothing'}${fromLast ? '' : ' (the last row was not selected first)'}, the first row is ${rows[0].id}`);
  await keys(ctx, ['Escape']);
  return bad.length ? `NO - ${bad.join('; ')}` : `yes (${reached} pages by g and their letter, each heading focused, g t back; g g from the last row to ${rows[0].id})`;
}

/**
 * `?` on Settings shows the legend, outside the Traffic page (which is hidden there), listing `g g`
 * and exactly the page keys of the pages this build offers; Escape closes it.
 */
async function keysLegend(ctx) {
  const { window, doc } = ctx;
  const pop = doc.getElementById('keys-pop');
  await keys(ctx, ['g', ',']);
  if (!await waitFor(() => shown(doc).join() === 'settings')) return `NO - g , left ${shown(doc).join()} shown, so ? was not tried on Settings`;
  await keys(ctx, ['?']);
  const inTraffic = doc.getElementById('page-traffic').contains(pop);
  let hiddenBy = null;
  for (let node = pop; node && node !== doc; node = node.parentElement) if (node.hidden) { hiddenBy = node.id || node.tagName; break; }
  const due = window.eval('PAGES').map((p) => `g ${KEYS[p.id]} ${p.label}`);
  const listed = [...doc.querySelectorAll('#keys-pages li')].map((li) => li.textContent.replace(/\s+/g, ' ').trim());
  const top = /g g\/G top\/bottom/.test(pop.textContent.replace(/\s+/g, ' '));
  await keys(ctx, ['Escape']);
  const closed = pop.hidden;
  await keys(ctx, ['g', 't']);
  await waitFor(() => shown(doc).join() === 'traffic');
  const bad = [];
  if (inTraffic || hiddenBy) bad.push(inTraffic ? 'the legend is inside #page-traffic, hidden on Settings' : `hidden by ${hiddenBy}`);
  if (!listed.length) bad.push('found no page key in #keys-pages, so this checked nothing');
  else if (listed.slice().sort().join('|') !== due.slice().sort().join('|')) bad.push(`lists ${listed.join(', ')}; due ${due.join(', ')}`);
  if (!top) bad.push('no g g line');
  if (!closed) bad.push('Escape left it open');
  return bad.length ? `NO - ${bad.join('; ')}` : `yes (shown on Settings outside the Traffic page: g g and ${listed.length} page keys, ${listed.join(', ')}; Escape closed it)`;
}

/**
 * Keys typed in the replay editor's body are text: `f`, `o` and `g` `s` leave the focus in the
 * textarea, the order as it was and the page on Traffic. The guard skipped only inputs and selects,
 * so `f` moved focus to the filter and lost the letter, and `o` reversed the list.
 */
async function keysSkipTextarea(ctx) {
  const { window, doc } = ctx;
  const row = doc.querySelector('#list .row');
  if (!row) return 'NO - found no row to open the replay editor from, so this checked nothing';
  row.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  await pause(300);
  const click = (node) => node.dispatchEvent(new window.MouseEvent('click', { bubbles: true }));
  const trigger = [...doc.querySelectorAll('#detail .detail-head .btn')].find((b) => b.textContent === 'replay ▾');
  if (trigger) click(trigger);
  await pause(40);
  const item = [...doc.querySelectorAll('#detail .menu-item')].find((b) => b.textContent === 'edit & replay');
  if (!item) return 'NO - found no edit & replay item, so this checked nothing';
  click(item);
  await waitFor(() => doc.querySelectorAll('.replay-editor textarea').length === 2);
  const body = doc.querySelectorAll('.replay-editor textarea')[1];
  if (!body) return 'NO - the replay editor drew no body textarea, so this checked nothing';
  const newestFirst = ctx.pageState().newestFirst;
  const hash = window.location.hash;
  body.focus();
  await keys(ctx, ['f', 'o', 'g', 's'], body);
  await pause(200);
  const focus = doc.activeElement === body ? 'the body' : doc.activeElement?.id || doc.activeElement?.tagName;
  const order = ctx.pageState().newestFirst;
  const page = shown(doc).join();
  const address = window.location.hash;
  // Back as it was found, whatever the keys did: order, page, the editor shut and no row selected.
  if (order !== newestFirst) window.setSortOrder(newestFirst);
  if (page !== 'traffic') { await window.navigate({ page: 'traffic' }); await pause(200); }
  if (doc.querySelector('.replay-editor')) click(item);
  body.blur();
  await keys(ctx, ['Escape']);
  return focus === 'the body' && order === newestFirst && page === 'traffic' && address === hash
    ? `yes (f, o, g s typed in the body: focus stayed, order ${newestFirst ? 'newest' : 'oldest'} first, page traffic)`
    : `NO - after f, o, g s in the body: focus on ${focus}, order flipped ${order !== newestFirst}, page ${page}, address ${address}`;
}

/** Each rail link's title names its key, `Stats (g s)`; its `aria-label` is the bare name. */
function railKeyTitles(ctx) {
  const links = [...ctx.doc.querySelectorAll('#rail .rail-item')];
  if (!links.length) return 'NO - found no rail link, so this checked nothing';
  const bad = links.filter((a) => {
    const label = a.getAttribute('aria-label');
    return a.title !== `${label} (g ${KEYS[a.dataset.page]})` || /\(g /.test(label || '(g ');
  }).map((a) => `${a.dataset.page}: title "${a.title}", aria-label "${a.getAttribute('aria-label')}"`);
  return bad.length ? `NO - ${bad.join('; ')}` : `yes (${links.length} links: ${links.map((a) => a.title).join(', ')}; aria-labels bare)`;
}

/**
 * Replaces the page's `fetch` for requests [match] accepts, answering each with [answer]'s
 * `[status, body]`, and keeps what was sent; everything else reaches the daemon. A second copy of
 * `lib/shell-probes.js`'s, which requires this file and so cannot be required from it.
 */
function stubFetch(window, match, answer) {
  const real = window.fetch;
  const sent = [];
  window.fetch = (path, init = {}) => {
    if (!match(String(path), init)) return real(path, init);
    sent.push({ path: String(path), method: init.method || 'GET', headers: init.headers || {}, body: init.body });
    const [status, reply] = answer(sent.length);
    return Promise.resolve(new Response(JSON.stringify(reply), { status, headers: { 'Content-Type': 'application/json' } }));
  };
  return { sent, restore: () => { window.fetch = real; } };
}

/**
 * Stats on a session with no calls says the list's sentence for it (`noCallsWhy`): `/stats` answered
 * by the probe with `total: 0`, the probe session as ended, then placed in `state.recording`, then no
 * session at all. Each sentence is due from the state the probe set, never from what was drawn. The
 * table is read again from the daemon after, and the session and recording put back.
 */
async function statsEmptyWhy(ctx) {
  const { window, doc } = ctx;
  const state = ctx.pageState();
  const { sessionId, recording } = state;
  if (!sessionId) return 'n/a (no session to empty)';
  await window.navigate({ page: 'stats' });
  const empty = doc.getElementById('stats-empty');
  const said = () => (empty.hidden ? '' : empty.textContent.trim());
  const stub = stubFetch(window, (p) => /\/stats\?/.test(p), () => [200, { total: 0, matched: 0, routeCount: 0, routes: [] }]);
  const read = async (due) => {
    await window.statsLoad();
    await waitFor(() => said() === due, 1500);
    return said();
  };
  const seen = [];
  try {
    state.recording = (recording || []).filter((id) => id !== sessionId);
    seen.push(['ended', await read('no calls were recorded in this session'), 'no calls were recorded in this session']);
    state.recording = [...state.recording, sessionId];
    seen.push(['recording', await read('the app is connected — no calls yet'), 'the app is connected — no calls yet']);
    state.sessionId = null;
    seen.push(['no session', await read('waiting for an app with inspector-stream to connect'), 'waiting for an app with inspector-stream to connect']);
  } finally {
    stub.restore();
    state.sessionId = sessionId;
    state.recording = recording;
    await window.statsLoad();
    await window.navigate({ page: 'traffic' });
    await pause(150);
  }
  const wrong = seen.filter(([, got, due]) => got !== due);
  return wrong.length ? `NO - ${wrong.map(([what, got, due]) => `${what}: "${got}", not "${due}"`).join('; ')}`
    : `yes (${seen.map(([what, got]) => `${what}: "${got}"`).join('; ')})`;
}

/** The Sessions page with no session says why, and what would make one; then the rows are drawn again. */
function sessionsEmptyWhy(ctx) {
  const { window, doc } = ctx;
  const state = ctx.pageState();
  const sessions = state.sessions;
  const due = 'No sessions yet. A session is recorded when an app with inspector-stream connects to this daemon.';
  state.sessions = [];
  window.renderSessionRows();
  const said = doc.getElementById('session-rows')?.textContent.trim();
  state.sessions = sessions;
  window.renderSessionRows();
  const back = doc.querySelectorAll('#session-rows .session-row').length;
  if (said !== due) return `NO - an empty list reads "${said}", not "${due}"`;
  return back === sessions.length ? `yes ("${said}"; ${back} rows drawn again)` : `NO - ${back} rows drawn again of ${sessions.length}`;
}

/**
 * Settings' counts follow a load, not only an entry: on `#/settings`, the whole-session copy emptied
 * and the counts painted from it (none), then `loadTransactions()` awaited. The counts due are the
 * calls' as the load left them, in the page's words, and must not be zero: a scan of no calls proves
 * nothing. On the CI fixture they read `3 rows` and `showing 10 of 13 endpoints`.
 */
async function settingsCountsFollowLoad(ctx) {
  const { window, doc } = ctx;
  const state = ctx.pageState();
  if (!state.sessionId) return 'n/a (no session to load)';
  await window.navigate({ page: 'settings' });
  const read = () => [doc.getElementById('dup-summary').textContent, doc.getElementById('endpoint-summary').textContent];
  try {
    state.allTransactions = [];
    window.paintSettingsCounts();
    const emptied = read();
    await window.loadTransactions();
    const n = window.computeDuplicates(state.allTransactions, state.duplicateWindowMs).size;
    const total = new Set(state.allTransactions.map((t) => window.lastSegment(t.path)).filter(Boolean)).size;
    const due = [`${n} ${n === 1 ? 'row' : 'rows'} in this session`,
      `showing ${Math.min(state.endpointLimit, total)} of ${total} ${total === 1 ? 'endpoint' : 'endpoints'} in this session`];
    const got = read();
    if (!n || !total) return `NO - found ${n} repeated rows and ${total} endpoints after the load, so this checked nothing`;
    return got[0] === due[0] && got[1] === due[1]
      ? `yes ("${emptied.join('", "')}" with no calls, then "${got.join('", "')}" once the load landed)`
      : `NO - after the load "${got.join('", "')}", due "${due.join('", "')}"`;
  } finally {
    await window.navigate({ page: 'traffic' });
    await pause(150);
  }
}

/**
 * Settings' route templates box, against `PUT /api/config` stubbed by the probe itself (`web-ui.md`: a
 * probe that presses a button stubs `fetch`), so the daemon's file is never written. The box holds the
 * file's list and counts the daemon's compiled one; a save that changes retention alone sends no
 * `routeTemplates`; two lines typed among blank ones and spaces send exactly `["/a/{x}","/b"]`, alone,
 * with the control header; a 400 is shown as the daemon words it. The form is read again after.
 */
async function routesEditor(ctx) {
  const { window, doc } = ctx;
  const earlier = window.eval('configReply');
  await window.navigate({ page: 'settings' });
  await waitFor(() => window.eval('configReply') !== earlier && doc.getElementById('route-templates'), 5000);
  const reply = window.eval('configReply');
  const area = () => doc.getElementById('route-templates');
  if (!reply || !area()) return `NO - found no route templates box${reply ? '' : ' (the config was never read)'}, so this checked nothing`;
  const listed = (reply.file && reply.file.routeTemplates) || [];
  const n = reply.effective.routeTemplates.length;
  const dueNow = `running: ${n} ${n === 1 ? 'template' : 'templates'}`;
  const shownList = area().value;
  const now = area().closest('.config-row').querySelector('.config-now')?.textContent;
  const status = doc.getElementById('config-status');
  const refusal = "routeTemplates entry 'x': a template starts with '/', as in \"/articles/{slug}\"";
  let answer = [200, reply];
  const stub = stubFetch(window, (p, init) => /\/api\/config$/.test(p) && init.method === 'PUT', () => answer);
  const save = async () => {
    doc.getElementById('config-form').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
    await waitFor(() => !/saving/.test(status.textContent), 3000);
    await pause(50);
  };
  const bad = [];
  try {
    const retention = doc.querySelector('#config-fields input[data-key="maxSessions"]');
    retention.value = String(Number(retention.value) + 1);
    await save();
    const first = stub.sent[0] && JSON.parse(stub.sent[0].body);
    if (!first || !('maxSessions' in first) || 'routeTemplates' in first) bad.push(`a retention save sent ${first ? JSON.stringify(first) : 'nothing'}`);
    area().value = '  /a/{x}\n\n /b \n';
    await save();
    const second = stub.sent[1];
    const body = second && JSON.parse(second.body);
    if (!body || JSON.stringify(body) !== JSON.stringify({ routeTemplates: ['/a/{x}', '/b'] })) bad.push(`two lines sent ${body ? JSON.stringify(body) : 'nothing'}`);
    else if (second.headers['X-Inspector-Control'] !== '1') bad.push('two lines were sent without X-Inspector-Control');
    answer = [400, { error: refusal }];
    area().value = 'x';
    await save();
    if (status.textContent !== refusal || !status.classList.contains('error')) bad.push(`a 400 read "${status.textContent}"${status.classList.contains('error') ? '' : ', not as an error'}`);
  } finally {
    stub.restore();
    await window.loadConfig();
    await window.navigate({ page: 'traffic' });
    await pause(150);
  }
  if (shownList !== listed.join('\n')) bad.push(`the box held "${shownList}", the file lists ${JSON.stringify(listed)}`);
  if (now !== dueNow) bad.push(`beside it "${now}", not "${dueNow}"`);
  return bad.length ? `NO - ${bad.join('; ')}`
    : `yes ("${dueNow}"; retention alone sent no routeTemplates; two lines sent ${stub.sent[1].body} with the header; the 400 shown verbatim)`;
}

/** The Stats note links Settings, where the templates are edited, and the export's words for it are hidden live. */
async function statsNoteLink(ctx) {
  const { window, doc } = ctx;
  await window.navigate({ page: 'stats' });
  const link = doc.querySelector('#stats-note a[href="#/settings"]');
  const words = doc.querySelector('#stats-note .archive-only');
  const shows = (node) => Boolean(node) && window.getComputedStyle(node).display !== 'none'
    && window.getComputedStyle(node.closest('.live-only') || node).display !== 'none';
  const seen = [shows(link), shows(words), link && link.textContent];
  await window.navigate({ page: 'traffic' });
  await pause(100);
  if (!link) return 'NO - the Stats note has no link to #/settings';
  return seen[0] && !seen[1] ? `yes ("${seen[2]}" shown, href #/settings; the export's words hidden)`
    : `NO - the link ${seen[0] ? 'shown' : 'hidden'}, the export's words ${seen[1] ? 'shown' : 'hidden'}`;
}

/**
 * In an export the Stats note names `config.json` and shows no link to Settings, which an export does
 * not have. Read on the page as opened; back on Traffic after.
 */
async function probeStatsNoteExport(window, doc) {
  await window.navigate({ page: 'stats' });
  const live = doc.querySelector('#stats-note .live-only');
  const words = doc.querySelector('#stats-note .archive-only');
  const display = (node) => (node ? window.getComputedStyle(node).display : 'absent');
  const seen = [display(live), display(words), words ? words.textContent.replace(/\s+/g, ' ').trim() : ''];
  await window.navigate({ page: 'traffic' });
  if (!live || !words) return `NO - the Stats note has ${live ? '' : 'no .live-only span'}${!live && !words ? ' and ' : ''}${words ? '' : 'no .archive-only span'}`;
  return seen[0] === 'none' && seen[1] !== 'none' && seen[2].includes('config.json')
    ? `yes (the Settings link hidden; "${seen[2]}" shown)`
    : `NO - the Settings link's display is ${seen[0]}, the export's words' ${seen[1]} ("${seen[2]}")`;
}

/**
 * The page keys on the probe page, from Traffic and back to it, the legend shut, no prefix pending
 * and no row selected, then the empty states, Settings' counts, the route templates box and the
 * Stats note's link. Wired right after the Stats page, so `chip marks active` is the next guard to
 * read the shared page (the Pace rule: it was shown red with these lines present).
 */
async function probePageKeys(ctx) {
  const lines = [['page keys live', await pageKeysLive(ctx)], ['keys legend', await keysLegend(ctx)],
    ['keys skip textarea', await keysSkipTextarea(ctx)], ['rail key titles', railKeyTitles(ctx)]];
  ctx.pageState().pageKeyAt = null;
  lines.push(['stats empty why', await statsEmptyWhy(ctx)], ['sessions empty why', sessionsEmptyWhy(ctx)],
    ['settings counts follow load', await settingsCountsFollowLoad(ctx)],
    ['routes editor', await routesEditor(ctx)], ['stats note link', await statsNoteLink(ctx)]);
  return lines;
}

/**
 * The export's page keys: `g s` opens Stats and `g f` Findings, `g a` and `g p` (live pages, which an
 * export does not offer) leave the page where it was, and the legend lists Traffic, Stats and Findings
 * only. Back on Traffic.
 */
async function probeKeysInExport(window, doc) {
  const ctx = { window, doc };
  const bad = [];
  await keys(ctx, ['g', 'f']);
  if (!await waitFor(() => shown(doc).join() === 'findings')) bad.push(`g f left ${shown(doc).join()} shown`);
  await keys(ctx, ['g', 's']);
  if (!await waitFor(() => shown(doc).join() === 'stats')) bad.push(`g s left ${shown(doc).join()} shown`);
  for (const letter of ['a', 'p']) {
    await keys(ctx, ['g', letter]);
    await pause(200);
    if (shown(doc).join() !== 'stats' || window.location.hash !== '#/stats') bad.push(`g ${letter} moved to ${shown(doc).join()} (${window.location.hash})`);
  }
  await keys(ctx, ['?']);
  const listed = [...doc.querySelectorAll('#keys-pages li')].map((li) => li.dataset.page);
  const open = !doc.getElementById('keys-pop').hidden;
  await keys(ctx, ['Escape']);
  await keys(ctx, ['g', 't']);
  await waitFor(() => shown(doc).join() === 'traffic');
  if (!open) bad.push('? opened no legend');
  else if (listed.join() !== 'traffic,stats,findings') bad.push(`the legend lists ${listed.join(', ') || 'nothing'}, not traffic, stats, findings`);
  return bad.length ? `NO - ${bad.join('; ')}` : 'yes (g f opened Findings, g s Stats; g a and g p left it there; the legend lists Traffic, Stats and Findings)';
}

module.exports = { probeSessionRowTime, probePageKeys, probeKeysInExport, probeStatsNoteExport };
