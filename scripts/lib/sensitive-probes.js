/* Probes of the sensitive-data findings on the Findings page and the Settings page, live for
 * `render-web-ui.js` (through `findings-probes.js`'s `probeFindings`) and offline for
 * `render-web-ui-archive.js`.
 *
 * The probe session holds three planted values, as `ProbeFixture.kt` writes them: a card number in the
 * checkout POST's body beside a `cvc`, an API key in the product GET's query, and an IBAN in a list in
 * the wallet balance's response. The daemon finds each and masks it; the page computes nothing. So the
 * live probe compares what the page drew with what `GET …/findings` and `GET /api/config` answer the
 * probe itself, and drives the parts that are the page's own: the no-hosts hint, the dismiss form,
 * the dismissed fold with `restore`, Escape inside the form, and the first-party hosts box on Settings.
 * Every `PUT /api/config` is answered here and never reaches the daemon ("a probe that presses a
 * button must stub `fetch` itself", `web-ui.md`), and so is the config read a dismissal makes, so the
 * probe daemon's `config.json` is never written, which `sensitive restored` checks.
 *
 * Each returns `[label, verdict]` pairs, one per line the caller prints.
 */

const { choose, waitFor } = require('./long-session-probes');

/** The planted values, and the parts of each a mask must never show beside its own few characters. */
const PLANTED = ['4000056655665556', 'GB82WEST12345698765432', 'probe-key-not-real'];
const PLANTED_PARTS = [...PLANTED, '400005665566', '0566556655', 'WEST12345698', '12345698765', 'probe-key', 'not-real'];
/** The two hints the planted card and IBAN must be drawn as. */
const MASKS = ['•••• 5556', 'GB82 •••• 5432'];

const LIVE_LABELS = [
  'sensitive masked on page', 'sensitive coverage line', 'sensitive rules listed', 'sensitive no hosts hint',
  'sensitive dismiss reach', 'sensitive dismiss', 'sensitive dismissed fold', 'sensitive escape', 'sensitive hosts patch', 'sensitive restored',
];
const OFFLINE_LABELS = ['sensitive in export masked', 'dismiss hidden in export'];

/** A dismissal the stubbed config already holds, which a new one must keep and `restore` must leave. */
const KEPT = { ruleId: 'sensitive.email', route: 'GET https://api.example.invalid/v3/shop/feed', location: 'resBody',
  path: '$.contact', reason: 'kept by the probe', at: '2026-01-01' };

const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const text = (node) => (node ? node.textContent.replace(/\s+/g, ' ').trim() : '');

/** Two JSON values equal whatever their keys' order. */
function sameJson(a, b) {
  const canon = (v) => (Array.isArray(v) ? v.map(canon)
    : v && typeof v === 'object' ? Object.keys(v).sort().reduce((o, k) => ({ ...o, [k]: canon(v[k]) }), {}) : v);
  return JSON.stringify(canon(a)) === JSON.stringify(canon(b));
}

/** The dismissal a finding's own key makes: its rule, route, location and grouped path. */
const keyOf = (f) => ({ ruleId: f.ruleId, route: f.route, location: f.location ?? null, path: f.path ?? null });

/** [report] with [target] marked dismissed for [reason], counted as the daemon counts one. */
function withDismissed(report, target, reason) {
  return {
    ...report,
    findingCount: report.findingCount - 1,
    dismissedCount: (report.dismissedCount || 0) + 1,
    [target.severity]: report[target.severity] - 1,
    findings: report.findings.map((f) => (f.id === target.id ? { ...f, dismissed: { reason, at: '2026-01-01' } } : f)),
  };
}

/** The Findings page's sensitive part as drawn. */
function drawnSensitive(doc) {
  const section = doc.querySelector('#findings-body section.findings-category[data-category="sensitive"]');
  return {
    section,
    articles: section ? [...section.querySelectorAll('article.finding')] : [],
    rule: (id) => (section ? section.querySelector(`section.findings-rule[data-rule="${id}"]`) : null),
  };
}

/** What [subtree]'s text shows of the plants and of [masks]: the leak check, live and offline. */
function leakVerdict(subtree, masks) {
  const said = subtree ? subtree.textContent : '';
  const leaked = PLANTED_PARTS.filter((part) => said.includes(part));
  const shown = masks.filter((m) => said.includes(m));
  const planted = MASKS.filter((m) => said.includes(m));
  if (!masks.length) return 'NO - found no masked hint in the reply, so this checked nothing';
  return !leaked.length && shown.length === masks.length && planted.length === MASKS.length
    ? `yes (${said.length} characters drawn, ${shown.length} masked hints such as "${MASKS.join('", "')}", none of ${PLANTED_PARTS.length} planted values or parts)`
    : `NO - the page shows ${leaked.join(', ') || 'no plant'}; ${shown.length} of ${masks.length} masked hints drawn, ${planted.join(', ') || 'neither planted mask'}`;
}

/** The live probes: see the file's header. `ctx` is `render-web-ui.js`'s shell context. */
async function probeSensitive(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const out = new Map(LIVE_LABELS.map((label) => [label, 'n/a (not reached)']));
  const sessionId = doc.getElementById('session-picker').value;
  if (!sessionId) return LIVE_LABELS.map((label) => [label, 'n/a (no session)']);
  const ask = async (path) => (await fetch(`${ORIGIN}${path}`)).json();
  const reportPath = `/api/sessions/${encodeURIComponent(sessionId)}/findings?limit=5000&includeDismissed=true`;
  const report = await ask(reportPath);
  const config = await ask('/api/config');
  const sensitive = report.findings.filter((f) => f.category === 'sensitive' && !f.dismissed);
  if (!sensitive.length) return LIVE_LABELS.map((label) => [label, `n/a (the daemon reports no sensitive finding on ${sessionId})`]);
  const press = async (node, ms = 150) => {
    node.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
    await pause(ms);
  };
  const submit = (form) => form.dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
  const type = (input, value) => {
    input.value = value;
    input.dispatchEvent(new window.Event('input', { bubbles: true }));
  };
  const reload = async () => { await window.eval('findingsLoad()'); await pause(100); };

  // Every request the page sends, in order. A PUT to the config never leaves the page; the config read
  // and the findings reply are the probe's while it sets one.
  const sent = [];
  const stubs = { config: null, put: { status: 200, body: {} }, findings: null };
  const answer = (status, body) => new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
  const realFetch = window.fetch;
  window.fetch = (path, init = {}) => {
    const url = new URL(path, ORIGIN);
    const method = (init.method || 'GET').toUpperCase();
    let body;
    try { body = init.body ? JSON.parse(init.body) : undefined; } catch { body = init.body; }
    sent.push({ method, path: url.pathname, query: url.search, body, control: Boolean(init.headers && init.headers['X-Inspector-Control']) });
    if (url.pathname === '/api/config' && method === 'PUT') return Promise.resolve(answer(stubs.put.status, stubs.put.body));
    if (url.pathname === '/api/config' && stubs.config) return Promise.resolve(answer(200, stubs.config));
    if (url.pathname.endsWith('/findings') && stubs.findings) return Promise.resolve(answer(200, stubs.findings));
    return realFetch(path, init);
  };
  const selectedBefore = ctx.pageState().selectedId;
  const lastCaptureTs = ctx.pageState().lastCaptureTs;
  try {
    await window.navigate({ page: 'findings' });
    const due = sensitive.length;
    await waitFor(() => drawnSensitive(doc).articles.length === due, 8000);
    let page = drawnSensitive(doc);

    // The subtree holds every masked hint the daemon sent and none of the planted values.
    const masks = sensitive.flatMap((f) => f.evidence).map((e) => e.masked).filter(Boolean);
    const leak = leakVerdict(doc.getElementById('page-findings'), masks);
    const keyRule = page.rule('sensitive.secret-in-url');
    out.set('sensitive masked on page', leak.startsWith('yes') && keyRule && keyRule.querySelector('article.finding')
      ? leak.replace('yes (', 'yes (the secret-in-url finding drawn; ') : leak.startsWith('yes') ? 'NO - the secret-in-url finding is not drawn' : leak);

    // The daemon's coverage line under the category's title, as given, counting the session's bodies.
    const cover = report.coverage && report.coverage.sensitive;
    const coverDrawn = text(page.section && page.section.querySelector('.findings-coverage'));
    out.set('sensitive coverage line', !cover || !cover.line ? 'NO - the daemon sent no sensitive coverage line, so this checked nothing'
      : coverDrawn === cover.line && cover.bodies > 0 && new RegExp(`^Scanned (all |\\d+ of )${cover.bodies} bodies`).test(coverDrawn)
        ? `yes ("${coverDrawn.slice(0, 90)}…", ${cover.bodies} bodies)`
        : `NO - drew "${coverDrawn}"; the daemon says "${cover.line}" over ${cover.bodies} bodies`);

    // Every shipped sensitive rule, from the catalogue Settings is drawn from, has its line.
    const catalogue = ((config.effective && config.effective.findings && config.effective.findings.rules) || []).filter((r) => r.category === 'sensitive');
    const states = { found: 0, none: 0, off: 0 };
    const wrong = catalogue.filter((entry) => {
      const rule = report.rules.find((r) => r.id === entry.id);
      const node = page.rule(entry.id);
      if (!rule || !node) return true;
      const head = text(node.querySelector('.findings-rule-head'));
      const drawn = node.querySelectorAll('article.finding, li.finding-dismissed').length;
      if (!rule.enabled) { states.off++; return !head.includes('— disabled in config.json') || drawn > 0; }
      if (rule.count === 0) { states.none++; return !head.includes('— checked, none found') || drawn > 0; }
      states.found++;
      return !head.includes(`(${rule.count})`) || drawn !== rule.count;
    }).map((r) => r.id);
    out.set('sensitive rules listed', !catalogue.length ? 'NO - the catalogue holds no sensitive rule, so this checked nothing'
      : !wrong.length ? `yes (${catalogue.length} rules: ${states.found} with findings, ${states.none} checked, none found, ${states.off} disabled in config.json)`
        : `NO - drawn otherwise or missing: ${wrong.join(', ')}`);

    // With no first-party hosts, a hint under the coverage line links Settings; with some, none.
    const hintOf = () => drawnSensitive(doc).section && drawnSensitive(doc).section.querySelector('.findings-hosts-hint');
    if (!cover || cover.firstPartyHosts !== 0) {
      out.set('sensitive no hosts hint', `n/a (the daemon has ${cover ? cover.firstPartyHosts : 'no'} first-party hosts)`);
    } else {
      const hint = hintOf();
      const link = hint && hint.querySelector('a');
      const under = hint && hint.previousElementSibling && hint.previousElementSibling.classList.contains('findings-coverage');
      stubs.findings = { ...report, coverage: { ...report.coverage, sensitive: { ...cover, firstPartyHosts: 2 } } };
      await reload();
      const withHosts = Boolean(hintOf());
      stubs.findings = null;
      await reload();
      out.set('sensitive no hosts hint', hint && link && link.getAttribute('href') === '#/settings' && under && !withHosts
        && text(hint).startsWith('No first-party hosts set, so nothing is raised for where it was sent')
        ? `yes ("${text(hint)}", under the coverage line, ${link.getAttribute('href')}; none with two hosts set)`
        : `NO - hint "${text(hint)}", link ${link && link.getAttribute('href')}, under the coverage line ${Boolean(under)}; drawn with hosts set ${withHosts}`);
    }

    // Dismiss: nothing without a reason; a refusal shown under the form; then one PUT of the fresh
    // list plus this finding's key, after a config read, and the findings read again. The target's
    // grouped path differs from its call's, so a key from the evidence would not match.
    const target = sensitive.find((f) => f.route && f.evidence.some((e) => e.path && e.path !== f.path)) || sensitive.find((f) => f.route);
    const article = () => doc.querySelector(`#findings-body article.finding[data-finding="${target.id}"]`);
    stubs.config = { ...config, file: { findingDismissals: [KEPT] } };
    const open = article() && article().querySelector('.finding-dismiss');
    if (!open) {
      for (const label of ['sensitive dismiss reach', 'sensitive dismiss']) out.set(label, `NO - found no dismiss control on ${target.id}, so this checked nothing`);
    } else {
      await press(open);
      const form = article().querySelector('form.dismiss-form');
      // Before the reason is asked for: the dismissal hides every finding with this key, in every session.
      const reach = text(form && form.querySelector('.dismiss-scope'));
      const title = (report.rules.find((r) => r.id === target.ruleId) || {}).title;
      const first = form && form.firstElementChild && form.firstElementChild.classList.contains('dismiss-scope');
      const parts = [`every "${title}" finding`, `on ${target.route}`, `at ${target.location} ${target.path}`, 'in every session', 'not only this one'];
      const missing = parts.filter((part) => !reach.includes(part));
      out.set('sensitive dismiss reach', reach && first && !missing.length
        ? `yes ("${reach}", above the reason)`
        : `NO - ${reach ? `"${reach}" lacks ${missing.join(', ')}${first ? '' : '; not above the reason'}` : 'found no reach line in the form, so this checked nothing'}`);
      const reason = form && form.querySelector('.dismiss-reason');
      const send = form && form.querySelector('.dismiss-send');
      let mark = sent.length;
      const emptyOff = Boolean(send) && send.disabled;
      if (form) submit(form);
      await pause(300);
      const emptySent = sent.slice(mark).filter((r) => r.path === '/api/config').length;
      const why = 'a published test value in the probe fixture';
      stubs.put = { status: 400, body: { error: 'findingDismissals entry 2 refused by the probe' } };
      if (reason) type(reason, why);
      if (form) submit(form);
      await waitFor(() => form && form.querySelector('.dismiss-error'), 3000);
      const refusal = text(form && form.querySelector('.dismiss-error'));
      const keptOpen = Boolean(form) && form.isConnected && reason.value === why;
      stubs.put = { status: 200, body: {} };
      mark = sent.length;
      if (form) submit(form);
      await waitFor(() => form && !form.isConnected && sent.slice(mark).some((r) => r.path.endsWith('/findings')), 5000);
      await pause(200);
      const after = sent.slice(mark);
      const read = after.findIndex((r) => r.method === 'GET' && r.path === '/api/config');
      const puts = after.filter((r) => r.method === 'PUT');
      const put = after.findIndex((r) => r.method === 'PUT');
      const reread = after.findIndex((r, i) => i > put && r.path.endsWith('/findings'));
      const body = puts[0] && puts[0].body;
      const entry = body && body.findingDismissals && body.findingDismissals[1];
      const dueEntry = { ...keyOf(target), reason: why };
      const sameEntry = entry && /^\d{4}-\d{2}-\d{2}$/.test(entry.at) && sameJson({ ...entry, at: undefined }, { ...dueEntry, at: undefined })
        && Object.keys(body).join() === 'findingDismissals' && body.findingDismissals.length === 2 && sameJson(body.findingDismissals[0], KEPT);
      out.set('sensitive dismiss', emptyOff && emptySent === 0 && refusal.includes('refused by the probe') && keptOpen
        && read >= 0 && read < put && puts.length === 1 && puts[0].control && sameEntry && reread > put && article() !== null
        ? `yes (no reason sent nothing; a refusal read "${refusal}" with the form open; then one PUT ${JSON.stringify(entry)} after the kept one, `
          + `after a config read, the findings read again, the finding still drawn as the daemon's reply says)`
        : `NO - empty: disabled ${emptyOff}, ${emptySent} config requests; refusal "${refusal}", open ${keptOpen}; `
          + `config read at ${read}, ${puts.length} PUTs at ${put} (control ${puts[0] && puts[0].control}), re-read at ${reread}; sent ${JSON.stringify(body)}; due ${JSON.stringify(dueEntry)} after the kept one`);
    }

    // Dismissed by the daemon's reply: folded at the end of its rule, with its reason and `restore`,
    // which sends the fresh list without this finding's key.
    stubs.findings = withDismissed(report, target, 'kept by the probe');
    await reload();
    page = drawnSensitive(doc);
    const ruleNode = page.rule(target.ruleId);
    const fold = ruleNode && ruleNode.querySelector('details.findings-dismissed');
    const line = fold && fold.querySelector(`li.finding-dismissed[data-finding="${target.id}"]`);
    const restore = line && line.querySelector('.finding-restore');
    const inGroup = Boolean(article());
    let restored = 'not pressed';
    if (restore) {
      stubs.config = { ...config, file: { findingDismissals: [KEPT, { ...keyOf(target), reason: 'kept by the probe', at: '2026-01-01' }] } };
      const mark = sent.length;
      await press(restore, 300);
      await waitFor(() => sent.slice(mark).some((r) => r.method === 'PUT') && sent.slice(mark).some((r) => r.path.endsWith('/findings')), 3000);
      const after = sent.slice(mark);
      const put = after.find((r) => r.method === 'PUT');
      const order = after.findIndex((r) => r.path === '/api/config' && r.method === 'GET') < after.indexOf(put);
      restored = put && put.control && order && sameJson(put.body, { findingDismissals: [KEPT] }) ? 'sent the fresh list without it' : `sent ${JSON.stringify(put && put.body)}, read first ${order}`;
    }
    out.set('sensitive dismissed fold', fold && !inGroup && !fold.open && text(fold.querySelector('summary')) === 'dismissed (1)'
      && text(line).includes('dismissed: kept by the probe') && restored === 'sent the fresh list without it'
      ? `yes ("${text(fold.querySelector('summary'))}", closed, out of its group; "${text(line.querySelector('.finding-dismissed-reason'))}"; restore ${restored})`
      : !fold ? `NO - found no dismissed fold under ${target.ruleId}; the finding drawn in its group ${inGroup}`
        : `NO - in its group ${inGroup}, open ${fold.open}, "${text(fold.querySelector('summary'))}", line "${text(line)}"; restore ${restored}`);
    stubs.findings = null;
    stubs.config = null;
    await reload();

    // Escape inside the reason form closes it and goes no further: a selected call stays selected.
    await window.navigate({ page: 'traffic' });
    await waitFor(() => doc.querySelector('#list .row'), 3000);
    const row = doc.querySelector('#list .row');
    if (row) await press(row, 300);
    const selected = ctx.pageState().selectedId;
    await window.navigate({ page: 'findings' });
    await waitFor(() => drawnSensitive(doc).articles.length === due, 5000);
    const again = article() && article().querySelector('.finding-dismiss');
    if (!again || !selected) {
      out.set('sensitive escape', `NO - ${again ? 'no call could be selected' : 'found no dismiss control'}, so this checked nothing`);
    } else {
      await press(again);
      const form = article().querySelector('form.dismiss-form');
      const reason = form.querySelector('.dismiss-reason');
      let heard = 0;
      const listen = (e) => { if (e.key === 'Escape') heard++; };
      doc.addEventListener('keydown', listen);
      reason.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }));
      doc.removeEventListener('keydown', listen);
      await pause(100);
      const back = doc.activeElement && doc.activeElement.classList.contains('finding-dismiss');
      out.set('sensitive escape', !form.isConnected && heard === 0 && ctx.pageState().selectedId === selected && !doc.getElementById('detail').hidden && back
        ? `yes (the form closed, focus back on dismiss…, the Escape reached no page handler; ${selected} still selected, its detail open)`
        : `NO - form open ${form.isConnected}, the page heard ${heard} Escape, selected ${ctx.pageState().selectedId} (was ${selected}), detail hidden ${doc.getElementById('detail').hidden}, focus on dismiss ${back}`);
    }

    // Settings: the first-party hosts box replaces `sensitive.firstPartyHosts` alone, keeping the rest
    // of `findings.sensitive` as the file holds it, and an unchanged box sends nothing.
    out.set('sensitive hosts patch', await probeHostsPatch(window, doc, sent, stubs, config));
  } finally {
    window.fetch = realFetch;
    ctx.pageState().lastCaptureTs = lastCaptureTs;
    await window.loadConfig();
    await window.navigate({ page: 'traffic' });
    await pause(200);
  }
  // Leaves Traffic as it found it: the session, live, the selection, no form open; the daemon's file untouched.
  if (selectedBefore === null || selectedBefore === undefined) doc.dispatchEvent(new window.KeyboardEvent('keydown', { key: 'Escape', bubbles: true }));
  if (ctx.pageState().sessionId !== sessionId) choose(ctx, sessionId);
  await waitFor(() => ctx.pageState().sessionId === sessionId, 5000);
  const file = (await ask('/api/config')).file;
  const after = ctx.pageState();
  const clean = !doc.getElementById('page-traffic').hidden && after.sessionId === sessionId && after.liveTail
    && (after.selectedId ?? null) === (selectedBefore ?? null) && !doc.querySelector('form.dismiss-form')
    && !(file && file.findingDismissals) && window.location.hash.startsWith('#/traffic');
  out.set('sensitive restored', clean
    ? `yes (Traffic, ${sessionId}, live, selection ${after.selectedId ?? 'none'}, no form open, the daemon's config.json has no findingDismissals)`
    : `NO - Traffic ${!doc.getElementById('page-traffic').hidden}, session ${after.sessionId}, live ${after.liveTail}, selected ${after.selectedId} (was ${selectedBefore}), `
      + `form ${Boolean(doc.querySelector('form.dismiss-form'))}, file ${JSON.stringify(file && file.findingDismissals)}, ${window.location.hash}`);
  return [...out];
}

/**
 * The Settings half: given a file whose `findings.sensitive` holds field names and one host, the box
 * shows the host; two hosts typed send `findings` with `sensitive` carrying the field names and the
 * two hosts; the box emptied drops `firstPartyHosts` and keeps the field names; unchanged, nothing.
 */
async function probeHostsPatch(window, doc, sent, stubs, config) {
  if (!config.effective || !config.effective.findings) return 'NO - the daemon reports no effective.findings, so this checked nothing';
  const status = doc.getElementById('config-status');
  const save = async () => {
    doc.getElementById('config-form').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
    await waitFor(() => !/saving/.test(status.textContent), 3000);
    await pause(100);
  };
  const earlier = window.eval('configReply');
  await window.navigate({ page: 'settings' });
  await waitFor(() => window.eval('configReply') !== earlier && doc.querySelector('#findings-config [data-sensitive="firstPartyHosts"]'), 5000);
  const fieldNames = { add: ['staffNumber'] };
  const file = { findings: { sensitive: { fieldNames, firstPartyHosts: ['old.example.invalid'] } } };
  window.eval(`configReply.file = ${JSON.stringify(file)}; renderConfig(configReply);`);
  const box = () => doc.querySelector('#findings-config [data-sensitive="firstPartyHosts"]');
  if (!box()) return 'NO - found no first-party hosts box, so this checked nothing';
  const shown = box().value;
  stubs.put = { status: 200, body: { ...config, file } };
  const mark = sent.length;
  box().value = 'api.example.invalid\n *.example.invalid \n';
  await save();
  const set = sent.slice(mark).filter((r) => r.method === 'PUT').map((r) => r.body);
  window.eval(`configReply.file = ${JSON.stringify(file)}; renderConfig(configReply);`);
  box().value = '';
  await save();
  window.eval(`configReply.file = ${JSON.stringify(file)}; renderConfig(configReply);`);
  await save();
  const unchanged = status.textContent;
  const puts = sent.slice(mark).filter((r) => r.method === 'PUT').map((r) => r.body);
  const dueSet = { findings: { sensitive: { fieldNames, firstPartyHosts: ['api.example.invalid', '*.example.invalid'] } } };
  const dueEmpty = { findings: { sensitive: { fieldNames } } };
  return shown === 'old.example.invalid' && puts.length === 2 && sameJson(set[0], dueSet) && sameJson(puts[1], dueEmpty) && unchanged === 'nothing changed'
    ? `yes (the box showed the file's host; two hosts sent ${JSON.stringify(puts[0])}; emptied, the field names kept; unchanged, "${unchanged}")`
    : `NO - shown "${shown}"; ${puts.length} PUTs (2 due): ${JSON.stringify(puts)}; unchanged said "${unchanged}"`;
}

/**
 * The probe session's export, network forbidden: its Findings page shows the masked hints and none of
 * the planted values, and with one finding dismissed in the embedded report the fold is drawn while
 * neither `dismiss…` nor `restore` is visible. `attempts` is the caller's record of network attempts;
 * none may be added. Leaves the page on Traffic with the embedded report as it was.
 */
async function probeSensitiveOffline(window, doc, archive, attempts) {
  const report = archive.findings;
  const sensitive = report ? report.findings.filter((f) => f.category === 'sensitive' && !f.dismissed) : [];
  if (!sensitive.length) return OFFLINE_LABELS.map((label) => [label, `NO - the export embeds no sensitive finding, so this checked nothing`]);
  const before = attempts.length;
  const lines = [];
  window.location.hash = '#/findings';
  await waitFor(() => !doc.getElementById('page-findings').hidden && drawnSensitive(doc).articles.length === sensitive.length, 5000);
  const masks = sensitive.flatMap((f) => f.evidence).map((e) => e.masked).filter(Boolean);
  const leak = leakVerdict(doc.getElementById('page-findings'), masks);
  lines.push(['sensitive in export masked', leak.startsWith('yes') && attempts.length === before ? leak
    : leak.startsWith('yes') ? `NO - ${attempts.length - before} network attempts` : leak]);

  const shownCss = (node) => window.getComputedStyle(node).display !== 'none';
  const target = sensitive[0];
  const embedded = report.findings;
  report.findings = embedded.map((f) => (f.id === target.id ? { ...f, dismissed: { reason: 'kept in the export', at: '2026-01-01' } } : f));
  try {
    await window.eval('findingsLoad()');
    await waitFor(() => doc.querySelector('#findings-body details.findings-dismissed'), 3000);
    const fold = doc.querySelector('#findings-body details.findings-dismissed');
    const controls = [...doc.querySelectorAll('#findings-body .finding-dismiss')];
    const restores = [...doc.querySelectorAll('#findings-body .finding-restore')];
    const visible = [...controls, ...restores].filter(shownCss);
    lines.push(['dismiss hidden in export', !controls.length || !restores.length
      ? `NO - found ${controls.length} dismiss and ${restores.length} restore controls, so this checked nothing`
      : fold && !visible.length && text(fold).includes('dismissed: kept in the export') && attempts.length === before
        ? `yes (${controls.length} dismiss… and ${restores.length} restore drawn, none visible; the fold "${text(fold.querySelector('summary'))}" drawn with its reason)`
        : `NO - ${visible.length} visible (${visible.map((n) => n.textContent).join(', ')}); fold ${fold ? text(fold) : 'none'}; ${attempts.length - before} network attempts`]);
  } finally {
    report.findings = embedded;
    await window.eval('findingsLoad()');
    window.location.hash = '#/traffic';
    await waitFor(() => !doc.getElementById('page-traffic').hidden, 3000);
  }
  return lines;
}

module.exports = { PLANTED, MASKS, LIVE_LABELS, OFFLINE_LABELS, probeSensitive, probeSensitiveOffline };
