/* Probes of the findings report on Traffic, live for `render-web-ui.js` (through `findings-probes.js`'s
 * `probeFindings`) and offline for `render-web-ui-archive.js`: the row badges, `⋮` → flagged rows
 * only, and the detail's Findings section, on the list and on `#/txn/{id}`.
 *
 * As on the Findings page, the page computes nothing, so each probe compares what Traffic drew with
 * the report the daemon answers the probe itself, or the one an export embeds: a row is badged exactly
 * when a finding names it, with its first finding's severity in words, and never "duplicate" or
 * "repeat", which the repeat tag owns (`daemon-and-mcp.md`). The payments session has 12 such rows of
 * 18; the probe session has none. Each returns `[label, verdict]` pairs, one per line the caller prints.
 */

const { LONG_SESSION_ID, choose, waitFor } = require('./long-session-probes');
const { PAYMENTS_SESSION_ID, PAYMENTS, SECRETS, pause, pageShown, text } = require('./findings-probes');

/** Each call's findings in reply order, dismissed ones left out, as the page builds `byTxn`. */
function byTxn(report) {
  const map = new Map();
  for (const f of report.findings) if (!f.dismissed) for (const id of f.txnIds) map.set(id, [...(map.get(id) || []), f]);
  return map;
}

/** Where a row's badge differs from [due]'s first severity, or says a word the repeat tag owns. */
function badgeFaults(rows, due) {
  const faults = [];
  for (const row of rows) {
    const badge = row.querySelector('.finding-badge');
    const findings = due.get(row.dataset.id);
    if (!badge !== !findings) { faults.push(`${row.dataset.id} ${badge ? 'badged, with no finding' : 'unbadged'}`); continue; }
    if (!badge) continue;
    const sev = findings[0].severity;
    const said = `${badge.textContent} ${badge.title}`;
    if (!badge.classList.contains(`sev-${sev}`) || text(badge.querySelector('.fb-long')) !== `⚑ ${sev}` || text(badge.querySelector('.fb-short')) !== '⚑'
      || /duplicate|repeat/i.test(said) || /\b(s[2-5x]|b[2-5x]|m-\w+)\b/.test(badge.className)) faults.push(`${row.dataset.id} "${said}" (${badge.className}), ${sev} due`);
  }
  return faults;
}

/** The detail's Findings section as drawn: its heading, and per finding its title, message, evidence lines and link. */
function detailDrawn(doc) {
  const box = doc.querySelector('#detail .detail-findings');
  const items = box ? [...box.querySelectorAll('.detail-finding')] : [];
  return { box, hidden: !box || box.hidden, heading: text(box && box.querySelector('.section-title')), text: text(box),
    items: items.map((i) => ({ id: i.dataset.finding, title: text(i.querySelector('.detail-finding-title')),
      message: text(i.querySelector('.finding-message')), lines: i.querySelectorAll('.finding-evidence li').length,
      link: i.querySelector('a.detail-finding-open') })) };
}

/** What [drawn] lacks of [txnId]'s findings in [report]: an empty list is a section drawn whole. */
function detailFaults(drawn, report, txnId) {
  const findings = byTxn(report).get(txnId) || [];
  const title = (f) => (report.rules.find((r) => r.id === f.ruleId) || {}).title;
  const faults = [];
  if (drawn.hidden || drawn.heading !== `Findings (${findings.length})`) faults.push(`heading "${drawn.heading}", hidden ${drawn.hidden}`);
  findings.forEach((f, i) => {
    const got = drawn.items[i] || {};
    const lines = f.evidence.filter((e) => e.txnId === txnId).length;
    if (got.id !== f.id || got.title !== title(f) || got.message !== f.message || got.lines !== lines || !got.link
      || text(got.link) !== 'open in Findings') faults.push(`${f.id}: "${got.title}", ${got.lines} of ${lines} lines, link ${got.link ? text(got.link) : 'none'}`);
  });
  return faults;
}

const TRAFFIC_LABELS = ['flagged none', 'flagged not remembered', 'findings badge', 'flagged only', 'findings detail', 'findings txn page',
  'findings reply redraws', 'findings traffic restored'];

/**
 * The report on Traffic: `⋮` → flagged rows only on a session with no findings (the long session when
 * the one shown has some, as the probe session's planted values are), and that
 * it writes nothing a reload would read; then on the payments session the row badges against the
 * daemon's own report, flagged rows only, the detail's section on the list and on `#/txn/{id}`, and a
 * stubbed reply that moves one badge, which must redraw the list and the section without reading the
 * call's bodies again. Leaves Traffic as it found it, flagged rows only off (`findings traffic restored`).
 */
async function probeFindingsTraffic(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const out = new Map(TRAFFIC_LABELS.map((label) => [label, 'n/a (not reached)']));
  const picker = doc.getElementById('session-picker');
  const sessionId = picker.value;
  const daemon = async (id) => (await fetch(`${ORIGIN}/api/sessions/${encodeURIComponent(id)}/findings?limit=5000`)).json();
  const click = async (node, ms = 200) => {
    node.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
    await pause(ms);
  };
  const rows = () => [...doc.querySelectorAll('#list .row')];
  const flagged = () => window.eval('findingsState.flaggedOnly');
  const held = (id) => window.eval(`findingsState.sessionId === ${JSON.stringify(id)} && findingsState.report !== null`);
  const band = doc.getElementById('flagged-note');
  const item = doc.getElementById('flagged-item');
  const asked = [];
  let stub = null;   // a findings reply the page gets instead of the daemon's
  const realFetch = window.fetch;
  window.fetch = (path, init) => {
    const url = new URL(path, ORIGIN);
    asked.push(url.pathname);
    if (stub && url.pathname.endsWith('/findings')) {
      return Promise.resolve(new Response(JSON.stringify(stub), { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }
    return realFetch(path, init);
  };
  try {
    if (!sessionId || !item) return TRAFFIC_LABELS.map((label) => [label, sessionId ? 'NO - found no flagged rows only item, so this checked nothing' : 'n/a (no session)']);
    let quiet = sessionId;
    let own = await daemon(sessionId);
    if (own.findingCount > 0 && [...picker.options].some((o) => o.value === LONG_SESSION_ID)) {
      quiet = LONG_SESSION_ID;
      choose(ctx, quiet);
      own = await daemon(quiet);
    }
    await waitFor(() => held(quiet) && ctx.pageState().signalSessionId === quiet && rows().length > 0, 8000);
    const all = rows().length;
    const stored = () => JSON.stringify([Object.entries(window.localStorage), Object.entries(window.sessionStorage), window.location.hash]);
    const before = stored();
    await click(item);
    const empty = doc.getElementById('list-empty');
    const showAll = [...empty.querySelectorAll('button')].find((b) => text(b) === 'show all calls');
    const said = text(empty);
    const bandSaid = text(band);
    const after = stored();
    if (own.findingCount > 0) out.set('flagged none', `n/a (${quiet} has ${own.findingCount} findings)`);
    else {
      const ok = flagged() && rows().length === 0 && !empty.hidden && said.startsWith('No calls with findings among the calls loaded.') && showAll
        && !band.hidden && bandSaid.startsWith('Only calls with findings: 0 of');
      if (showAll) await click(showAll);
      out.set('flagged none', ok && !flagged() && rows().length === all && band.hidden
        ? `yes (${quiet}: "${said}"; band "${bandSaid.slice(0, 60)}…"; show all calls gave back ${all} rows)`
        : `NO - on: ${rows().length} rows, empty "${said}" (shown ${!empty.hidden}), band "${bandSaid}"; after show all: ${rows().length} of ${all} rows, on ${flagged()}`);
    }
    if (flagged()) await click(item);
    out.set('flagged not remembered', before === after && !flagged()
      ? `yes (local and session storage and the address unchanged by the toggle: ${JSON.parse(after)[0].length} stored keys)`
      : `NO - the toggle wrote ${after} where ${before} was`);

    if (![...picker.options].some((o) => o.value === PAYMENTS_SESSION_ID)) {
      for (const label of TRAFFIC_LABELS.slice(2, 7)) out.set(label, `n/a (no session ${PAYMENTS_SESSION_ID})`);
      if (quiet !== sessionId) choose(ctx, sessionId);
    } else {
      const report = await daemon(PAYMENTS_SESSION_ID);
      const due = byTxn(report);
      choose(ctx, PAYMENTS_SESSION_ID);
      await waitFor(() => held(PAYMENTS_SESSION_ID) && rows().length === report.total && doc.querySelectorAll('#list .finding-badge').length === due.size, 8000);
      const badged = rows().filter((r) => r.querySelector('.finding-badge'));
      const faults = badgeFaults(rows(), due);
      out.set('findings badge', !badged.length ? 'NO - found no badged row, so this checked nothing'
        : !faults.length && badged.length === PAYMENTS.rows && due.size === PAYMENTS.rows
          ? `yes (${badged.length} of ${rows().length} rows badged, each its first finding's severity in words: ${[...new Set(badged.map((r) => text(r.querySelector('.fb-long'))))].join(', ')}; none says duplicate or repeat)`
          : `NO - ${badged.length} rows badged, ${due.size} due (the fixture's ${PAYMENTS.rows}); ${faults.slice(0, 4).join('; ')}`);

      await click(item);
      const kept = rows().map((r) => r.dataset.id);
      const onBand = band.hidden ? '(hidden)' : text(band);
      await click(item);
      out.set('flagged only', kept.length === PAYMENTS.rows && kept.every((id) => due.has(id)) && band.hidden && rows().length === report.total
        && onBand.startsWith(`Only calls with findings: ${PAYMENTS.rows} of ${report.total} calls in this session.`)
        ? `yes (${kept.length} rows, band "${onBand.slice(0, 60)}…"; off: ${rows().length} rows, band hidden)`
        : `NO - on: ${kept.length} rows (${kept.filter((id) => !due.has(id)).length} unflagged), band "${onBand}"; off: ${rows().length} of ${report.total}, band shown ${!band.hidden}`);

      // The detail: a row with two findings, then a row with none, then `open in Findings` and Back.
      const [twice] = [...due].find(([, list]) => list.length > 1) || [...due][0];
      const plain = rows().find((r) => !due.has(r.dataset.id));
      await click(rows().find((r) => r.dataset.id === twice), 400);
      await waitFor(() => !detailDrawn(doc).hidden, 3000);
      const drawn = detailDrawn(doc);
      const leaked = SECRETS.filter((v) => drawn.text.includes(v));
      const detailFault = detailFaults(drawn, report, twice);
      await click(plain, 400);
      const none = detailDrawn(doc);
      await click(rows().find((r) => r.dataset.id === twice), 400);
      const open = detailDrawn(doc).items[0] && detailDrawn(doc).items[0].link;
      if (open) await click(open, 400);
      const atFindings = await waitFor(() => pageShown(doc, 'findings') && doc.querySelector(`#findings-body .finding-target[data-finding="${drawn.items[0].id}"]`), 5000);
      const at = window.location.hash;
      window.history.back();
      const back = await waitFor(() => pageShown(doc, 'traffic'), 3000);
      out.set('findings detail', !drawn.items.length ? 'NO - found no finding in the detail, so this checked nothing'
        : !detailFault.length && !leaked.length && none.hidden && !none.items.length && atFindings && back
          ? `yes (${twice}: "${drawn.heading}", ${drawn.items.map((i) => `${i.title} with ${i.lines} evidence lines`).join(', ')}; ${plain.dataset.id}: none; open in Findings marked ${at}, Back returned)`
          : `NO - ${detailFault.join('; ') || 'drawn whole'}; plants ${leaked.join(', ') || 'none'}; an unflagged row's section hidden ${none.hidden} with ${none.items.length}; open in Findings ${atFindings} at ${at}, Back ${back}`);

      await window.navigate({ page: 'txn', txn: twice });
      await waitFor(() => doc.getElementById('panes').classList.contains('txn-full') && !detailDrawn(doc).hidden, 5000);
      const full = detailFaults(detailDrawn(doc), report, twice);
      const fullAt = window.location.hash;
      window.history.back();
      await waitFor(() => !doc.getElementById('panes').classList.contains('txn-full'), 3000);
      out.set('findings txn page', !full.length ? `yes (${fullAt} drew "${detailDrawn(doc).heading || 'Findings'}" as the list's detail does)` : `NO - at ${fullAt}: ${full.join('; ')}`);

      // A reply that moves one call's badge redraws the list and the section, and reads no body again.
      await click(rows().find((r) => r.dataset.id === twice), 600);
      const bodies = () => asked.filter((p) => p.includes(`/transactions/${twice}/body/`)).length;
      const head = doc.querySelector('#detail .detail-head');
      const rowNode = rows().find((r) => r.dataset.id === twice);
      const read = bodies();
      stub = { ...report, findings: report.findings.map((f) => (f.txnIds.includes(twice) ? { ...f, severity: 'info' } : f)) };
      await window.findingsLoad();
      await pause(300);
      const moved = rows().find((r) => r.dataset.id === twice);
      const movedSaid = text(moved && moved.querySelector('.fb-long'));
      const sectionSaid = [...doc.querySelectorAll('#detail .detail-findings .finding-badge')].map(text);
      const redrawn = !rowNode.isConnected && doc.querySelector('#detail .detail-head') === head;
      stub = null;
      await window.findingsLoad();
      await pause(300);
      const restoredNode = rows().find((r) => r.dataset.id === twice);
      await window.findingsLoad();
      await pause(300);
      const kept2 = rows().find((r) => r.dataset.id === twice) === restoredNode;
      out.set('findings reply redraws', redrawn && movedSaid === '⚑ info' && sectionSaid.every((s) => s === '⚑ info') && sectionSaid.length > 0
        && bodies() === read && kept2
        ? `yes (the badge moved to "${movedSaid}" and the section with it, the call's head kept, ${bodies() - read} body reads; an unchanged reply drew nothing)`
        : `NO - row redrawn ${!rowNode.isConnected}, head kept ${doc.querySelector('#detail .detail-head') === head}, badge "${movedSaid}", section ${sectionSaid.join(', ')}; `
          + `${bodies() - read} body reads (0 due); an unchanged reply left the row ${kept2}`);
      doc.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
      choose(ctx, sessionId);
      await waitFor(() => ctx.pageState().sessionId === sessionId && ctx.pageState().signalSessionId === sessionId, 8000);
    }
    await ctx.settle();
  } finally {
    stub = null;
    window.fetch = realFetch;
    if (flagged()) window.eval('findingsSetFlagged(false)');
  }
  const after = ctx.pageState();
  const clean = pageShown(doc, 'traffic') && after.sessionId === sessionId && after.liveTail && !flagged() && band.hidden && window.location.hash.startsWith('#/traffic');
  out.set('findings traffic restored', clean
    ? `yes (Traffic, ${sessionId}, live, flagged rows only off, ${window.location.hash})`
    : `NO - Traffic ${pageShown(doc, 'traffic')}, session ${after.sessionId}, live ${after.liveTail}, flagged ${flagged()}, ${window.location.hash}`);
  return [...out];
}


/**
 * The payments session's export, network forbidden, on Traffic as it opens: the rows badged from the
 * embedded report, and `#/txn/{id}` drawing a call's Findings section. Leaves the page on Traffic.
 */
async function probeFindingsTrafficOffline(window, doc, archive, attempts) {
  const report = archive.findings;
  if (!report) return ['findings badge in export', 'findings detail in export'].map((label) => [label, 'NO - the export embeds no findings']);
  const before = attempts.length;
  const due = byTxn(report);
  const rows = () => [...doc.querySelectorAll('#list .row')];
  await waitFor(() => doc.querySelectorAll('#list .finding-badge').length === due.size, 5000);
  const badged = rows().filter((r) => r.querySelector('.finding-badge'));
  const faults = badgeFaults(rows(), due);
  const lines = [['findings badge in export', !badged.length ? 'NO - found no badged row, so this checked nothing'
    : !faults.length && badged.length === PAYMENTS.rows && attempts.length === before
      ? `yes (${badged.length} of ${rows().length} rows badged from the embedded report; no network attempt)`
      : `NO - ${badged.length} rows badged, ${due.size} due; ${faults.slice(0, 4).join('; ')}; ${attempts.length - before} network attempts`]];
  const [id] = [...due].find(([, list]) => list.length > 1) || [...due][0] || [];
  if (!id) return [...lines, ['findings detail in export', 'NO - the export names no call with a finding, so this checked nothing']];
  window.location.hash = `#/txn/${id}`;
  await waitFor(() => doc.getElementById('panes').classList.contains('txn-full') && !detailDrawn(doc).hidden, 5000);
  const drawn = detailDrawn(doc);
  const wrong = detailFaults(drawn, report, id);
  lines.push(['findings detail in export', !wrong.length && attempts.length === before
    ? `yes (#/txn/${id} drew "${drawn.heading}": ${drawn.items.map((i) => i.title).join(', ')}; no network attempt)`
    : `NO - at ${window.location.hash}: ${wrong.join('; ') || 'drawn whole'}; ${attempts.length - before} network attempts`]);
  window.location.hash = '#/traffic';
  await waitFor(() => !doc.getElementById('panes').classList.contains('txn-full'), 3000);
  return lines;
}

module.exports = { TRAFFIC_LABELS, probeFindingsTraffic, probeFindingsTrafficOffline };
