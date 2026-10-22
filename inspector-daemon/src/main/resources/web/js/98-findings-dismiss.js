/* findings-dismiss: setting a finding aside with a reason, the dismissed fold, and the no-hosts hint */
'use strict';

/*
 * A dismissal is one entry in `config.json`'s top-level `findingDismissals`, matched by the daemon on
 * the finding's rule, route, location and path exactly, and read on the next findings read
 * (`daemon-and-mcp.md`). The page writes it through `PUT /api/config`, which replaces a top-level key
 * whole, so it reads the config afresh immediately before writing and sends that key alone: a list
 * read minutes ago would drop a dismissal made since, and resending `findings` would undo a rule edit
 * made on Settings meanwhile. Two tabs dismissing in the same moment can still lose one; closing that
 * needs a control route of its own, which is not worth it. Nothing is drawn as dismissed until the
 * daemon's next findings reply says so: the page computes nothing, so it never guesses that a save
 * matched. Dismiss and restore send something, so an export hides both (`body.archive`); the fold is
 * drawn there, because an export that left dismissed findings out would read as nothing found.
 */

/**
 * The open form, if any, and which rules' folds the reader opened. A live refresh draws the page
 * again every second at most; while a form is open the page is not redrawn (`findingsDismissHolds`),
 * so a reason being typed is never thrown away, and an opened fold is opened again when it is.
 */
const dismissState = { form: null, openFolds: new Set() };

/** Whether a redraw would destroy a form somebody is filling in. */
const findingsDismissHolds = () => Boolean(dismissState.form && dismissState.form.isConnected && findingsShown());

/** Forgets the open form: entering the page draws it afresh. */
function findingsDismissReset() {
  dismissState.form = null;
}

/** The four parts a dismissal matches on, from the finding itself: its grouped path, never one call's. */
const findingsDismissKey = (finding) => ({
  ruleId: finding.ruleId, route: finding.route, location: finding.location ?? null, path: finding.path ?? null,
});

const sameDismissKey = (entry, key) => entry.ruleId === key.ruleId && entry.route === key.route
  && (entry.location ?? null) === key.location && (entry.path ?? null) === key.path;

/**
 * The file's `findingDismissals` as it stands now. A file the daemon cannot read, or a key that is not
 * a list, is refused rather than written over: a save must never replace settings it could not read.
 */
async function findingsDismissFresh() {
  const config = await api('/api/config');
  if (config.fileError) throw new Error(`${config.fileError} — fix config.json by hand; nothing was saved.`);
  const held = config.file ? config.file.findingDismissals : undefined;
  if (held === undefined || held === null) return [];
  if (!Array.isArray(held)) throw new Error('config.json\'s findingDismissals is not a list; fix it by hand first. Nothing was saved.');
  return held;
}

/** Sends [list] as the whole `findingDismissals` key, and nothing else; throws the daemon's words on a refusal. */
async function findingsDismissWrite(list) {
  const res = await controlFetch('/api/config', { method: 'PUT', json: { findingDismissals: list } });
  if (res.ok) return;
  let message = '';
  try { message = (await res.json()).error || ''; } catch { /* the status says it */ }
  throw new Error(message || `the daemon answered ${res.status}`);
}

/**
 * `dismiss…` at the end of [head], the finding's header line, opening the reason form under it. A
 * finding with no route cannot be keyed, so it has no control. Hidden in an export by `body.archive`.
 */
function findingsDismissControl(head, finding) {
  if (!finding.route) return;
  const open = el('button', 'btn btn-sm btn-quiet finding-dismiss', 'dismiss…');
  open.type = 'button';
  open.title = 'Set aside every finding with this rule, route, location and path, in every session, with a reason saved to config.json';
  open.addEventListener('click', () => findingsDismissOpen(head, finding));
  head.appendChild(open);
}

/**
 * What a dismissal of [finding] reaches, said in the form before anything is saved: the daemon matches
 * on rule, route, location and path in every session, so it hides every finding with that key, now and
 * later, not only the one on screen (the product owner's ruling, PR #167).
 */
function findingsDismissReach(finding) {
  const rule = (findingsState.report && findingsState.report.rules.find((r) => r.id === finding.ruleId)) || null;
  const where = [finding.location, finding.path].filter(Boolean).join(' ');
  return `Dismissing hides every ${rule ? `"${rule.title}"` : finding.ruleId} finding on ${finding.route}`
    + `${where ? ` at ${where}` : ''}, in every session, not only this one, until it is restored.`;
}

/** The reason form, inserted after [head]: one at a time, so opening another closes the first. */
function findingsDismissOpen(head, finding) {
  if (dismissState.form) findingsDismissClose(false);
  const form = el('form', 'dismiss-form');
  form.dataset.finding = finding.id;
  form.appendChild(el('p', 'dismiss-scope', findingsDismissReach(finding)));
  const reason = el('input', 'dismiss-reason');
  Object.assign(reason, { type: 'text', placeholder: 'why this is expected', maxLength: 200, autocomplete: 'off' });
  reason.setAttribute('aria-label', 'Why this finding is expected');
  const send = el('button', 'btn btn-sm dismiss-send', 'dismiss');
  send.type = 'submit';
  send.disabled = true;
  const cancel = el('button', 'btn btn-sm btn-quiet dismiss-cancel', 'cancel');
  cancel.type = 'button';
  const actions = el('span', 'dismiss-actions');
  actions.append(send, cancel);
  // The reach above, then the reason and the buttons on a row of their own, so the reach is read first.
  const row = el('div', 'dismiss-row');
  row.append(reason, actions);
  form.appendChild(row);
  reason.addEventListener('input', () => { send.disabled = !reason.value.trim(); });
  cancel.addEventListener('click', () => findingsDismissClose(true));
  // Escape closes the form and goes no further, as the replay menu's does (`web-ui.md`): on Traffic the
  // page's own Escape clears the selection, and no page handler should act on a key meant for the form.
  form.addEventListener('keydown', (e) => {
    if (e.key !== 'Escape') return;
    e.preventDefault();
    e.stopPropagation();
    findingsDismissClose(true);
  });
  form.addEventListener('submit', (e) => {
    e.preventDefault();
    findingsDismissSend(finding, form, reason, send);
  });
  head.after(form);
  dismissState.form = form;
  reason.focus();
}

/** Closes the open form; the page is drawn again if a redraw was held for it. */
function findingsDismissClose(redraw) {
  const form = dismissState.form;
  dismissState.form = null;
  if (!form) return;
  form.remove();
  if (redraw && findingsShown()) findingsRender();
  // The redraw built the finding's control again, so focus goes to the one drawn now, by its finding.
  const article = [...document.querySelectorAll('#findings-body article.finding')].find((a) => a.dataset.finding === form.dataset.finding);
  const open = article && article.querySelector('.finding-dismiss');
  if (open) open.focus();
}

/** The last line of [box], the form or a fold's line, saying why nothing changed; the form stays open. */
function findingsDismissSay(box, text) {
  for (const line of [...box.children]) if (line.classList.contains('dismiss-error')) line.remove();
  if (text) box.appendChild(el('p', 'dismiss-error', text));
}

/** With a reason only: the fresh list plus this finding's entry, then the findings read again. */
async function findingsDismissSend(finding, form, reason, send) {
  const why = reason.value.trim();
  if (!why) return;
  send.disabled = true;
  findingsDismissSay(form, '');
  try {
    const list = await findingsDismissFresh();
    await findingsDismissWrite([...list, { ...findingsDismissKey(finding), reason: why, at: new Date().toISOString().slice(0, 10) }]);
  } catch (e) {
    findingsDismissSay(form, `Not dismissed: ${e.message}`);
    send.disabled = !reason.value.trim();
    return;
  }
  findingsDismissClose(false);
  await findingsLoad();
}

/** `restore`: every entry with this finding's key leaves the fresh list, then the findings are read again. */
async function findingsRestore(finding, button) {
  button.disabled = true;
  const line = button.closest('li');
  findingsDismissSay(line, '');
  try {
    const list = await findingsDismissFresh();
    const key = findingsDismissKey(finding);
    const kept = list.filter((entry) => !(entry && sameDismissKey(entry, key)));
    if (kept.length !== list.length) await findingsDismissWrite(kept);
  } catch (e) {
    findingsDismissSay(line, `Not restored: ${e.message}`);
    button.disabled = false;
    return;
  }
  await findingsLoad();
}

/**
 * The rule's dismissed findings, folded at the end of its section and closed until opened: each
 * finding's message, where it is, the reason, and `restore`, which an export hides.
 */
function findingsDismissedFold(ruleId, findings) {
  const fold = el('details', 'findings-dismissed');
  fold.dataset.rule = ruleId;
  fold.open = dismissState.openFolds.has(ruleId);
  fold.addEventListener('toggle', () => {
    if (fold.open) dismissState.openFolds.add(ruleId);
    else dismissState.openFolds.delete(ruleId);
  });
  fold.appendChild(el('summary', 'findings-dismissed-head', `dismissed (${findings.length})`));
  const list = el('ul', 'findings-dismissed-list');
  for (const finding of findings) {
    const li = el('li', 'finding-dismissed');
    li.dataset.finding = finding.id;
    const head = el('div', 'finding-dismissed-head');
    head.appendChild(el('span', 'finding-dismissed-title', finding.message));
    const restore = el('button', 'btn btn-sm btn-quiet finding-restore', 'restore');
    restore.type = 'button';
    restore.title = 'Take this dismissal out of config.json; the finding comes back on the next read';
    restore.addEventListener('click', () => findingsRestore(finding, restore));
    head.appendChild(restore);
    li.appendChild(head);
    const where = [finding.route, finding.location, finding.path].filter(Boolean).join(' ');
    if (where) li.appendChild(el('p', 'mono finding-dismissed-where', where));
    li.appendChild(el('p', 'finding-dismissed-reason', `dismissed: ${finding.dismissed.reason}`));
    list.appendChild(li);
  }
  fold.appendChild(list);
  return fold;
}

/**
 * Under the sensitive coverage line, with no first-party hosts set: a severity is raised for where data
 * was sent only once they are, and the hosts are set on Settings. Live only, since an export has no
 * Settings page; its coverage line already says the first half.
 */
function findingsHostsHint(section, report, category) {
  const cover = report.coverage && report.coverage[category];
  if (ARCHIVE || category !== 'sensitive' || !cover || cover.firstPartyHosts !== 0) return;
  const hint = el('p', 'settings-note muted findings-hosts-hint');
  hint.append('No first-party hosts set, so nothing is raised for where it was sent — set them in ', findingsSettingsLink('Settings'), '.');
  section.appendChild(hint);
}
