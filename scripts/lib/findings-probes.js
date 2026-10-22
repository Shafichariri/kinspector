/* Probes of the Findings page and the Settings page's findings fieldset, live for `render-web-ui.js`
 * and offline for `render-web-ui-archive.js`.
 *
 * The page computes nothing: every rule, count, message, masked hint and coverage line is the
 * daemon's. So the live probe compares what the page drew with what `GET …/findings` answers the
 * probe itself, and with the payments session's counts as `ProbeFixture.kt` builds them (10 findings
 * in 18 calls on 12 rows: 3 high, 6 medium, 1 info), and drives the parts that are the page's own: the
 * rail link, a call link opening `#/txn/{id}` with Back returning, `?id=` marking one finding, and a
 * live call asking again at most once. The fixture's idempotency keys hold `PLANTED`, which no page
 * may print. The probe session has no findings under the default rules.
 *
 * `probeFindingsPage` leaves the page on Traffic, on the session it found, live, and checks that it
 * did (`findings restored`), so the probe after it does not pass vacuously. Each returns
 * `[label, verdict]` pairs, one per line the caller prints.
 */

const { choose, waitFor } = require('./long-session-probes');

/** The fixture's payments session, as `ProbeFixture.kt` names it. */
const PAYMENTS_SESSION_ID = '2025-12-31T08-00-00_probe-payments_ci_debug';
/** Its counts, as `ProbeFixture.kt`'s `paymentsSessionCalls` lays them out and `PaymentRulesTest` asserts. */
const PAYMENTS = { findings: 10, calls: 18, high: 3, medium: 6, info: 1, rows: 12,
  coverage: '13 mutation calls looked at; 6 request bodies compared, 2 unknown (2 truncated).' };
/** What the page must never print: the fixture's planted keys, a body field and an error message. */
const SECRETS = ['PLANTED', 'SECRET', 'k-PLANTED-7a1f', 'acct-1', '"qty"', 'after 10000ms'];

const FINDINGS_LABELS = [
  'findings rail', 'findings none', 'findings payments', 'findings coverage', 'findings txn link',
  'findings anchor', 'findings no value', 'findings live asks', 'findings restored',
];
const SETTINGS_LABELS = ['findings settings drawn', 'findings settings patch', 'findings settings saved', 'findings settings refused'];

const pause = (ms) => new Promise((r) => setTimeout(r, ms));
const pageShown = (doc, page) => !doc.getElementById(`page-${page}`).hidden;
const text = (node) => (node ? node.textContent.replace(/\s+/g, ' ').trim() : '');
const plural = (n, one, many) => `${n} ${n === 1 ? one : many}`;

/** The count line the page is due to draw for [report], built from the reply the probe read itself. */
function countDue(report) {
  const calls = `${plural(report.total, 'call', 'calls')} in this session`;
  return report.findingCount === 0 ? `No findings in ${calls}.`
    : `${plural(report.findingCount, 'finding', 'findings')} in ${calls} · ${report.high} high · ${report.medium} medium · ${report.info} info`;
}

/** The page as drawn: the count line, each rule's section, each finding's article and call links. */
function drawn(doc) {
  const rules = [...doc.querySelectorAll('#findings-body section.findings-rule')];
  const articles = [...doc.querySelectorAll('#findings-body article.finding')];
  return {
    count: text(doc.getElementById('findings-count')),
    rules,
    rule: (id) => rules.find((s) => s.dataset.rule === id) || null,
    articles,
    links: [...doc.querySelectorAll('#findings-body a.finding-txn')],
    coverage: [...doc.querySelectorAll('#findings-body .findings-coverage')].map(text),
  };
}

/** The live page: see the file's header. `ctx` is `render-web-ui.js`'s shell context. */
async function probeFindingsPage(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const out = new Map(FINDINGS_LABELS.map((label) => [label, 'n/a (not reached)']));
  const picker = doc.getElementById('session-picker');
  const sessionId = picker.value;
  const daemon = async (id) => (await fetch(`${ORIGIN}/api/sessions/${encodeURIComponent(id)}/findings?limit=5000`)).json();
  const press = async (node, ms = 150) => {
    node.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
    await pause(ms);
  };
  const rail = (page) => doc.querySelector(`#rail .rail-item[data-page="${page}"]`);
  // Every findings request the page sends; the rest pass through untouched.
  const asked = [];
  const realFetch = window.fetch;
  window.fetch = (path, init) => {
    if (new URL(path, ORIGIN).pathname.endsWith('/findings')) asked.push(path);
    return realFetch(path, init);
  };
  const lastCaptureTs = ctx.pageState().lastCaptureTs;
  try {
    if (!sessionId) return [...out.keys()].map((label) => [label, 'n/a (no session)']);
    const order = [...doc.querySelectorAll('#rail .rail-item')].map((a) => a.dataset.page);
    const link = rail('findings');
    if (!link) {
      out.set('findings rail', 'NO - found no rail link for findings, so this checked nothing');
      return [...out];
    }
    await press(link);
    const opened = await waitFor(() => pageShown(doc, 'findings') && window.location.hash === '#/findings', 3000);
    out.set('findings rail', opened && order.indexOf('findings') === order.indexOf('stats') + 1 && link.getAttribute('href') === '#/findings'
      ? `yes (after Stats, ${link.getAttribute('href')}; opened ${window.location.hash})`
      : `NO - rail order ${order.join(', ')}, href ${link.getAttribute('href')}, opened ${opened} at ${window.location.hash}`);

    // The session shown has no findings under the default rules, and the page says so rule by rule.
    const own = await daemon(sessionId);
    const ownDue = countDue(own);
    await waitFor(() => drawn(doc).count === ownDue, 5000);
    let page = drawn(doc);
    if (own.findingCount > 0) {
      out.set('findings none', `n/a (${sessionId} has ${own.findingCount} findings)`);
    } else {
      // Rules that ship off (sensitive email and phone) say so instead; every rule that ran reads checked.
      const checked = page.rules.filter((s) => text(s).includes('— checked, none found'));
      const ran = own.rules.filter((rule) => rule.enabled);
      out.set('findings none', page.count === ownDue && ran.length > 0 && checked.length === ran.length && page.articles.length === 0
        ? `yes ("${page.count}"; ${checked.length} rules read "checked, none found", as the daemon ran ${ran.length} of ${own.rules.length})`
        : !page.rules.length ? `NO - found no rule section, so this checked nothing; "${page.count}"`
          : `NO - "${page.count}" where "${ownDue}" was due; ${checked.length} of ${ran.length} rules that ran read checked; ${page.articles.length} findings drawn`);
    }

    const hasPayments = [...picker.options].some((o) => o.value === PAYMENTS_SESSION_ID);
    if (!hasPayments) {
      for (const label of FINDINGS_LABELS.slice(2, 8)) out.set(label, `n/a (no session ${PAYMENTS_SESSION_ID})`);
    } else {
      const report = await daemon(PAYMENTS_SESSION_ID);
      choose(ctx, PAYMENTS_SESSION_ID);
      const due = countDue(report);
      await waitFor(() => drawn(doc).count === due && drawn(doc).articles.length === report.findings.length, 8000);
      page = drawn(doc);
      const fixture = report.findingCount === PAYMENTS.findings && report.total === PAYMENTS.calls && report.high === PAYMENTS.high
        && report.medium === PAYMENTS.medium && report.info === PAYMENTS.info;
      const rowsDrawn = new Set(page.links.map((a) => a.dataset.txn));
      const wrongRules = report.rules.filter((rule) => {
        const section = page.rule(rule.id);
        return !section || section.querySelectorAll('article.finding').length !== rule.count;
      }).map((rule) => rule.id);
      const title = text(doc.querySelector('#findings-body .findings-category-title'));
      out.set('findings payments', fixture && page.count === due && title === 'Payment safety' && !wrongRules.length
        && page.articles.length === PAYMENTS.findings && rowsDrawn.size === PAYMENTS.rows
        ? `yes ("${page.count}" under ${title}; ${page.articles.length} findings, each rule's as the daemon counts; links to ${rowsDrawn.size} rows)`
        : `NO - "${page.count}" where "${due}" was due (the fixture's ${PAYMENTS.findings}/${PAYMENTS.calls}/${PAYMENTS.high}/${PAYMENTS.medium}/${PAYMENTS.info} held: ${fixture}); `
          + `title "${title}"; ${page.articles.length} findings; rules drawn otherwise: ${wrongRules.join(', ') || 'none'}; ${rowsDrawn.size} rows linked, ${PAYMENTS.rows} due`);

      // One line per family, as the daemon wrote it and in its order; the payment one is the fixture's.
      const line = report.coverage && report.coverage.payment && report.coverage.payment.line;
      const lines = Object.values(report.coverage || {}).map((cover) => cover.line);
      out.set('findings coverage', lines.length > 1 && sameJson(page.coverage, lines) && line === PAYMENTS.coverage
        ? `yes (${lines.length} lines as given, payment's "${line}")`
        : `NO - drew ${JSON.stringify(page.coverage)}; the daemon says ${JSON.stringify(lines)}, the fixture's payment line "${PAYMENTS.coverage}"`);

      // A call link opens the call's own page, pushed, so Back returns to the page as it was.
      const first = report.findings[0];
      const linkOf = (id) => doc.querySelector(`#findings-body article.finding[data-finding="${first.id}"] a.finding-txn[data-txn="${id}"]`);
      const txnLink = linkOf(first.txnIds[0]);
      if (!txnLink) {
        out.set('findings txn link', `NO - found no link to ${first.txnIds[0]} in finding ${first.id}, so this checked nothing`);
      } else {
        const dueId = first.txnIds[0];
        const href = txnLink.getAttribute('href');
        await press(txnLink, 300);
        const path = () => text(doc.querySelector('#detail .detail-head .path'));
        const callShown = await waitFor(() => doc.getElementById('panes').classList.contains('txn-full') && path().length > 0, 5000);
        const at = window.location.hash;
        window.history.back();
        const back = await waitFor(() => pageShown(doc, 'findings') && window.location.hash === '#/findings', 3000);
        out.set('findings txn link', callShown && at === `#/txn/${dueId}` && back
          ? `yes (${href} opened ${at}, ${path()}; Back returned to ${window.location.hash})`
          : `NO - opened ${at} where #/txn/${dueId} was due, the call's page drawn ${callShown}; Back to Findings ${back} (${window.location.hash})`);
      }

      // Read once the page is drawn again with the session's rows held, so the call links' labels are
      // in the text too. It holds a masked tail, so the scan has something in it, and none of the plants.
      await waitFor(() => drawn(doc).articles.length === report.findings.length && drawn(doc).links.every((l) => l.querySelector('.method')), 5000);
      const pageText = doc.getElementById('page-findings').textContent;
      const leaked = SECRETS.filter((s) => pageText.includes(s));
      const masked = report.findings.flatMap((f) => f.evidence).map((e) => e.masked).filter(Boolean);
      const shownMasks = masked.filter((m) => pageText.includes(m));
      out.set('findings no value', !masked.length ? 'NO - found no masked hint in the reply, so this checked nothing'
        : !leaked.length && shownMasks.length === masked.length
          ? `yes (${pageText.length} characters drawn, ${shownMasks.length} masked hints such as "${masked[0]}", none of ${SECRETS.length} planted values)`
          : `NO - the page shows ${leaked.join(', ') || 'no plant'}; ${shownMasks.length} of ${masked.length} masked hints drawn`);

      // `?id=` marks that finding alone and scrolls it into view once; an id not in the report says so.
      await waitFor(() => drawn(doc).articles.length === report.findings.length, 5000);
      const target = report.findings[2];
      const proto = window.Element.prototype;
      const hasOwn = Object.prototype.hasOwnProperty.call(proto, 'scrollIntoView');
      const saved = proto.scrollIntoView;
      const scrolled = [];
      proto.scrollIntoView = function scrollIntoView() { scrolled.push(this.dataset && this.dataset.finding); };
      try {
        await window.navigate({ page: 'findings', id: target.id });
        await waitFor(() => doc.querySelectorAll('#findings-body .finding-target').length > 0, 3000);
        const marked = [...doc.querySelectorAll('#findings-body .finding-target')].map((a) => a.dataset.finding);
        await window.navigate({ page: 'findings', id: 'no-such-finding' });
        await pause(200);
        const missing = text(doc.querySelector('#findings-body .findings-missing'));
        const unmarked = doc.querySelectorAll('#findings-body .finding-target').length;
        out.set('findings anchor', marked.join() === target.id && scrolled.join() === target.id
          && missing === 'that finding is not in this session\'s findings now' && unmarked === 0
          ? `yes (?id=${target.id} marked and scrolled to that finding alone; an unknown id: "${missing}")`
          : `NO - ?id=${target.id} marked ${marked.join(', ') || 'nothing'}, scrolled ${scrolled.join(', ') || 'nothing'}; an unknown id: "${missing}", ${unmarked} marked`);
      } finally {
        if (hasOwn) proto.scrollIntoView = saved;
        else delete proto.scrollIntoView;
      }
      await window.navigate({ page: 'findings' });
      await pause(200);

      // Live: a call through the page's own socket asks again once within 1.5 s; paused, never.
      const socket = ctx.lastSocket();
      const template = ctx.pageState().transactions[0];
      if (socket && template) {
        let n = 0;
        const send = () => socket.onmessage({ data: JSON.stringify({
          type: 'txn', sessionId: PAYMENTS_SESSION_ID, txn: { ...template, id: `findingsprobe-c${++n}`, mono: template.mono + 100000 + n, seq: undefined },
        }) });
        const liveButton = doc.getElementById('live-btn');
        let before = asked.length;
        send();
        await pause(1500);
        const whileLive = asked.length - before;
        await press(liveButton, 300);
        before = asked.length;
        send();
        await pause(1500);
        const whilePaused = asked.length - before;
        await press(liveButton, 300);   // resuming reads the session again, without the probe's calls
        out.set('findings live asks', whileLive === 1 && whilePaused === 0
          ? 'yes (a live call asked for the findings once within 1.5 s; paused, a call asked nothing)'
          : `NO - ${whileLive} findings requests after a live call (1 due), ${whilePaused} while paused (0 due)`);
      } else {
        out.set('findings live asks', 'NO - no socket or no call to copy');
      }
      await press(rail('traffic'), 300);
      choose(ctx, sessionId);
      await waitFor(() => ctx.pageState().sessionId === sessionId && ctx.pageState().signalSessionId === sessionId, 8000);
    }
    if (!pageShown(doc, 'traffic')) await press(rail('traffic'), 300);
    await ctx.settle();
  } finally {
    window.fetch = realFetch;
    ctx.pageState().lastCaptureTs = lastCaptureTs;
  }
  const after = ctx.pageState();
  const clean = pageShown(doc, 'traffic') && after.sessionId === sessionId && after.liveTail && window.location.hash.startsWith('#/traffic');
  out.set('findings restored', clean
    ? `yes (Traffic, ${sessionId}, live, ${window.location.hash})`
    : `NO - Traffic ${pageShown(doc, 'traffic')}, session ${after.sessionId}, live ${after.liveTail}, ${window.location.hash}`);
  return [...out];
}

/** Two JSON values equal whatever their keys' order. */
function sameJson(a, b) {
  const canon = (v) => (Array.isArray(v) ? v.map(canon)
    : v && typeof v === 'object' ? Object.keys(v).sort().reduce((o, k) => ({ ...o, [k]: canon(v[k]) }), {}) : v);
  return JSON.stringify(canon(a)) === JSON.stringify(canon(b));
}

/**
 * The Settings page's Findings fieldset, with every `PUT /api/config` stubbed here (the page's own
 * guard is not what keeps the daemon's file untouched). The page is given a file whose `findings`
 * holds a sub-object the fieldset does not draw, a rule override and a payment field; the inputs then
 * put that rule back to its defaults, change another rule's severity, turn a third off and add a
 * payment path. The one body sent must hold exactly those, the other sub-object as the file had it.
 * Leaves the page on Traffic with the config read again from the daemon.
 */
async function probeFindingsSettings(ctx) {
  const { window, doc, ORIGIN } = ctx;
  const out = new Map(SETTINGS_LABELS.map((label) => [label, 'n/a (not reached)']));
  const config = await (await fetch(`${ORIGIN}/api/config`)).json();
  const effective = config.effective && config.effective.findings;
  if (!effective) return SETTINGS_LABELS.map((label) => [label, 'NO - the daemon reports no effective.findings, so this checked nothing']);
  const sent = [];
  let answer = { status: 200, body: config };
  const realFetch = window.fetch;
  window.fetch = (path, init) => {
    if (init && init.method === 'PUT' && new URL(path, ORIGIN).pathname === '/api/config') {
      sent.push(JSON.parse(init.body));
      const { status, body } = answer;
      return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }));
    }
    return realFetch(path, init);
  };
  const box = doc.getElementById('findings-config');
  const status = doc.getElementById('config-status');
  const save = async () => {
    doc.getElementById('config-form').dispatchEvent(new window.Event('submit', { bubbles: true, cancelable: true }));
    await waitFor(() => !/saving/.test(status.textContent), 3000);
    await pause(100);
  };
  try {
    // Opening Settings reads the config; a save made before that read lands would be drawn over by it.
    const earlier = window.eval('configReply');
    await window.navigate({ page: 'settings' });
    await waitFor(() => window.eval('configReply') !== earlier && !box.hidden && box.querySelectorAll('[data-rule-enabled]').length > 0, 5000);
    const rows = [...box.querySelectorAll('tbody tr')];
    const whole = rows.filter((tr) => tr.querySelector('input[type="checkbox"][data-rule-enabled]') && tr.querySelector('select[data-rule-severity]'));
    const defaults = whole.filter((tr) => {
      const rule = effective.rules.find((r) => r.id === tr.dataset.rule);
      const option = [...tr.querySelectorAll('option')].find((o) => o.value === (rule && rule.defaultSeverity));
      return option && option.textContent.endsWith('(default)');
    });
    const payment = ['idempotencyHeaders', 'paymentPaths', 'ignorePaths', 'duplicateWindowMs', 'resubmitWindowMs']
      .filter((key) => box.querySelector(`[data-payment="${key}"]`));
    out.set('findings settings drawn', whole.length === effective.rules.length && rows.length === whole.length && defaults.length === whole.length && payment.length === 5
      ? `yes (${whole.length} rules, each with an on box and a severity select naming its default; ${payment.length} payment settings)`
      : !rows.length ? 'NO - found no rule row, so this checked nothing'
        : `NO - ${whole.length} of ${rows.length} rows whole, ${effective.rules.length} rules in effective.findings; ${defaults.length} name the default; payment fields: ${payment.join(', ')}`);

    const [dup, keyed, retried] = ['payment.duplicate-mutation', 'payment.retry-without-key', 'payment.retry-with-key'];
    const file = { findings: { sensitive: { probe: ['kept as written'] }, rules: { [keyed]: { severity: 'info' } }, payment: { resubmitWindowMs: 60000 } } };
    window.eval(`configReply.file = ${JSON.stringify(file)}; renderConfig(configReply);`);
    const pick = (sel) => box.querySelector(sel);
    const inputs = [`[data-rule-severity="${dup}"]`, `[data-rule-severity="${keyed}"]`, `[data-rule-enabled="${retried}"]`, '[data-payment="paymentPaths"]'];
    const lacking = inputs.filter((sel) => !pick(sel));
    if (lacking.length) {
      for (const label of SETTINGS_LABELS.slice(1)) out.set(label, `NO - the fieldset has no ${lacking.join(', ')}, so no save was tried`);
      return [...out];
    }
    pick(`[data-rule-severity="${dup}"]`).value = 'medium';
    pick(`[data-rule-severity="${keyed}"]`).value = 'high';
    pick(`[data-rule-enabled="${retried}"]`).checked = false;
    const paths = pick('[data-payment="paymentPaths"]');
    paths.value = `${paths.value}\nwallet\n`;
    answer = { status: 200, body: config };
    await save();
    const due = { findings: {
      sensitive: file.findings.sensitive,
      rules: { [dup]: { severity: 'medium' }, [retried]: { enabled: false } },
      payment: { resubmitWindowMs: 60000, paymentPaths: [...effective.payment.paymentPaths, 'wallet'] },
    } };
    out.set('findings settings patch', sent.length === 1 && sameJson(sent[0], due)
      ? `yes (one PUT: ${JSON.stringify(sent[0]).slice(0, 150)}…)`
      : `NO - ${sent.length} PUTs; sent ${JSON.stringify(sent[0])}; due ${JSON.stringify(due)}`);
    out.set('findings settings saved', status.textContent === 'saved — findings use it now' && !status.classList.contains('error')
      ? `yes ("${status.textContent}")`
      : `NO - "${status.textContent}"${status.classList.contains('error') ? ', drawn as an error' : ''}`);

    const refusal = 'findings.payment.paymentPaths entry \'path:x\': write the pattern without path:';
    answer = { status: 400, body: { error: refusal } };
    const again = box.querySelector('[data-payment="paymentPaths"]');
    again.value = `${again.value}\npath:x`;
    await save();
    out.set('findings settings refused', sent.length === 2 && status.textContent === refusal && status.classList.contains('error')
      ? `yes ("${status.textContent}", as the daemon's 400 said it)`
      : `NO - ${sent.length} PUTs; status "${status.textContent}", error ${status.classList.contains('error')}`);
  } finally {
    window.fetch = realFetch;
    await window.loadConfig();
    await window.navigate({ page: 'traffic' });
    await pause(200);
  }
  return [...out];
}

/**
 * The Findings rail link in an export: shown, because the page reads only the embedded report, while
 * the pages that need the daemon stay hidden (`pages hidden in export` holds those).
 */
function probeFindingsRailOffline(window, doc) {
  const link = doc.querySelector('#rail .rail-item[data-page="findings"]');
  const shown = Boolean(link) && window.getComputedStyle(link).display !== 'none';
  return [['findings rail in export', !link ? 'NO - found no rail link for findings, so this checked nothing'
    : shown && link.getAttribute('href') === '#/findings' ? 'yes (Findings shown in the rail, #/findings)' : `NO - shown ${shown}, href ${link.getAttribute('href')}`]];
}

/**
 * The payments session's export, network forbidden: first Traffic's badges and a call's section
 * (`findings-traffic-probes.js`), then the page draws the embedded report, a call link opens the call
 * from the embedded rows and Back returns, and the router refuses a category, which needs the daemon,
 * while cutting a limit as the daemon cuts and keeping `findingCount`. `attempts` is the caller's
 * record of network attempts; none may be added. Leaves the page on Traffic.
 */
async function probeFindingsExport(window, doc, archive, attempts) {
  const labels = ['findings offline drawn', 'findings txn in export', 'findings category refused'];
  const report = archive.findings;
  const { probeFindingsTrafficOffline } = require('./findings-traffic-probes');
  const lines = await probeFindingsTrafficOffline(window, doc, archive, attempts);
  if (!report) return [...lines, ...labels.map((label) => [label, 'NO - the export embeds no findings'])];
  const before = attempts.length;
  window.location.hash = '#/findings';
  const due = countDue(report);
  await waitFor(() => !doc.getElementById('page-findings').hidden && drawn(doc).count === due
    && drawn(doc).articles.length === report.findings.length, 5000);
  const page = drawn(doc);
  const footer = text(doc.getElementById('findings-note'));
  lines.push(['findings offline drawn', report.findings.length > 0 && page.articles.length === report.findings.length && page.count === due
    && footer === `Computed when this file was exported. Rule version ${report.ruleVersion}.` && attempts.length === before
    ? `yes ("${page.count}", ${page.articles.length} findings as embedded; "${footer}"; no network attempt)`
    : !report.findings.length ? 'NO - the export embeds no finding, so this checked nothing'
      : `NO - "${page.count}" where "${due}" was due; ${page.articles.length} of ${report.findings.length} drawn; footer "${footer}"; ${attempts.length - before} network attempts`]);

  const id = report.findings[0] && report.findings[0].txnIds[0];
  const link = page.links.find((a) => a.dataset.txn === id);
  if (!link) {
    lines.push(['findings txn in export', 'NO - found no call link, so this checked nothing']);
  } else {
    link.dispatchEvent(new window.MouseEvent('click', { bubbles: true, cancelable: true, button: 0 }));
    const path = () => text(doc.querySelector('#detail .detail-head .path'));
    const opened = await waitFor(() => doc.getElementById('panes').classList.contains('txn-full') && path().length > 0, 3000);
    const at = window.location.hash;
    window.history.back();
    const back = await waitFor(() => !doc.getElementById('page-findings').hidden, 3000);
    lines.push(['findings txn in export', opened && at.startsWith(`#/txn/${id}`) && back && attempts.length === before
      ? `yes (${at} drew ${path()} from the export; Back returned to Findings)`
      : `NO - at ${at}, the call drawn ${opened}, Back to Findings ${back}; ${attempts.length - before} network attempts`]);
  }

  const source = window.eval('source');
  const base = `/api/sessions/${encodeURIComponent(report.sessionId)}/findings`;
  const refused = await source.fetch(`${base}?category=payment`);
  const refusal = (await refused.json()).error;
  const cut = await (await source.fetch(`${base}?limit=1`)).json();
  lines.push(['findings category refused', refused.status === 400 && /needs the daemon/.test(refusal)
    && cut.findings.length === 1 && cut.findingCount === report.findingCount
    ? `yes (category: ${refused.status} "${refusal}"; limit=1 cut to 1 of findingCount ${cut.findingCount})`
    : `NO - category: ${refused.status} "${refusal}"; limit=1 gave ${cut.findings && cut.findings.length}, findingCount ${cut.findingCount}`]);
  window.location.hash = '#/traffic';
  await waitFor(() => !doc.getElementById('page-traffic').hidden, 3000);
  return lines;
}

/**
 * The live probes, the page, the Settings fieldset, then Traffic (`findings-traffic-probes.js`, which
 * reads this file's fixture counts, so it is required here once this file has loaded), for
 * `render-web-ui.js`'s one call.
 */
async function probeFindings(ctx) {
  const { probeFindingsTraffic } = require('./findings-traffic-probes');
  return [...await probeFindingsPage(ctx), ...await probeFindingsSettings(ctx), ...await probeFindingsTraffic(ctx)];
}

module.exports = {
  PAYMENTS_SESSION_ID, PAYMENTS, SECRETS, FINDINGS_LABELS, SETTINGS_LABELS, pause, pageShown, text,
  probeFindings, probeFindingsPage, probeFindingsSettings, probeFindingsRailOffline, probeFindingsExport,
};
