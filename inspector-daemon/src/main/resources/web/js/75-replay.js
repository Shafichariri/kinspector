/* replay: the editor, sending and the result */
'use strict';

// --- replay: editing before sending ----------------------------------------------------------

/*
 * `REPLAY.md` step 3. The daemon has accepted edits since replay shipped — `ReplayRequest` takes
 * `method`, `url`, `headers` and `body`, and `Replayer` applies them *before* asking the app to
 * sign, which `ReplayTest.an edited path is what gets signed` pins. Nothing exposed any of it.
 *
 * Three of the daemon's own refusal messages say "Edit the body to supply it", which until now
 * named a control that did not exist. That is the case this is most useful for: a request whose
 * body was truncated, streamed or outside the capture allowlist cannot be replayed as captured,
 * and supplying the body by hand is the only way to run it at all.
 */

// @mirror-begin hopByHop
/** Hop-by-hop headers, mirroring `HOP_BY_HOP_HEADERS` in `Replay.kt`. Keep them in step. */
const HOP_BY_HOP = new Set([
  'host', 'content-length', 'connection', 'keep-alive', 'transfer-encoding',
  'te', 'trailer', 'upgrade', 'proxy-authorization', 'proxy-authenticate', 'accept-encoding',
]);

/**
 * The header set the editor opens with.
 *
 * The same filtering the daemon applies when no headers are sent, applied here instead — because
 * the moment the UI sends an explicit map, `Replayer` uses it *verbatim* and its own filtering
 * never runs. Seeding from the raw capture would therefore quietly reintroduce `Content-Length`
 * and `Host` from a request whose body the user is about to change.
 */
function replayableHeaderLines(txn) {
  return Object.entries(txn.reqHeaders || {})
    .filter(([name]) => !HOP_BY_HOP.has(name.toLowerCase()))
    .map(([name, values]) => `${name}: ${values.join(', ')}`)
    .join('\n');
}
// @mirror-end hopByHop

/**
 * Parses the header box back into a map.
 *
 * A line that is not `Name: value` is reported rather than skipped. Silently dropping one would
 * send a request missing a header the user believes they set, and the failure would look like a
 * server problem.
 */
function parseHeaderLines(text) {
  const headers = {};
  const bad = [];
  for (const raw of text.split('\n')) {
    const line = raw.trim();
    if (!line) continue;
    const at = line.indexOf(':');
    if (at <= 0) { bad.push(line); continue; }
    headers[line.slice(0, at).trim()] = line.slice(at + 1).trim();
  }
  return { headers, bad };
}

/** Why the captured body is not in the box, when it is not. */
function missingBodyNote(txn, body) {
  if (body !== null) return null;
  if (!txn.reqBytes) return null;
  const size = fmtBytes(txn.reqBytes);
  if (txn.reqBodyTruncated) {
    return `The captured body was cut at the capture cap (${size} total), so replaying it as ` +
      'captured is refused — supply it here in full to run this request.';
  }
  if (txn.reqBodyOmitted === 'streaming') {
    return `${size} was streamed and never buffered, by design. There is nothing captured to ` +
      'replay; supply the body here.';
  }
  if (txn.reqBodyOmitted === 'contentType') {
    return `${size} was not captured — its content type is outside the capture allowlist. ` +
      'Supply the body here, or enable captureAllBodies and re-capture.';
  }
  return `${size} was recorded on device but no body reached the archive.`;
}

function toggleReplayEditor(txn, pane, reqBody) {
  const existing = pane.querySelector('.replay-editor');
  if (existing) { existing.remove(); return; }

  const form = el('div', 'replay-editor');
  const note = missingBodyNote(txn, reqBody);

  const field = (label, node) => {
    const wrap = el('div', 'replay-field');
    wrap.appendChild(el('label', 'replay-label', label));
    wrap.appendChild(node);
    return wrap;
  };

  const method = el('input', 'replay-input replay-method');
  method.value = txn.method;
  method.spellcheck = false;

  const url = el('input', 'replay-input');
  url.value = urlOf(txn);
  url.spellcheck = false;

  const headers = el('textarea', 'replay-input replay-textarea');
  headers.value = replayableHeaderLines(txn);
  headers.spellcheck = false;
  headers.rows = 6;

  const body = el('textarea', 'replay-input replay-textarea');
  // As captured, so sending without touching anything sends what was sent.
  body.value = reqBody || '';
  body.spellcheck = false;
  body.rows = 6;

  const resign = el('input');
  resign.type = 'checkbox';
  resign.checked = true;
  const resignLabel = el('label', 'replay-resign');
  resignLabel.appendChild(resign);
  resignLabel.appendChild(el('span', null, ' regenerate per-request headers (re-sign)'));
  resignLabel.title =
    'The app signs what is actually sent, after these edits are applied. Turn this off only to ' +
    'reproduce the captured headers exactly.';

  form.appendChild(field('Method', method));
  form.appendChild(field('URL', url));
  form.appendChild(field('Headers — one Name: value per line', headers));
  if (note) form.appendChild(el('div', 'replay-note', note));
  form.appendChild(field('Body', body));
  form.appendChild(resignLabel);

  const problem = el('div', 'replay-error');
  problem.hidden = true;
  form.appendChild(problem);

  const send = el('button', 'btn', 'send');
  const cancel = el('button', 'btn btn-quiet', 'cancel');
  cancel.onclick = () => form.remove();
  const actions = el('div', 'replay-actions');
  actions.appendChild(send);
  actions.appendChild(cancel);
  form.appendChild(actions);

  send.onclick = () => {
    const parsed = parseHeaderLines(headers.value);
    if (parsed.bad.length) {
      problem.hidden = false;
      problem.textContent =
        `not Name: value — ${parsed.bad.slice(0, 3).join(' | ')}${parsed.bad.length > 3 ? ' …' : ''}`;
      return;
    }
    problem.hidden = true;
    runReplay(txn, pane, send, {
      method: method.value.trim() || txn.method,
      url: url.value.trim(),
      headers: parsed.headers,
      // Omitted only when there is nothing to send and nothing was sent. Present — even empty —
      // it counts as an override, which is what lifts the daemon's refusal on a body it could
      // not capture, and is also how somebody deliberately sends an empty one.
      body: body.value === '' && !txn.reqBytes ? null : body.value,
      resign: resign.checked,
    });
  };

  // Above the tabs, where the request it describes is, rather than below the response.
  pane.insertBefore(form, pane.querySelector('.dtabs'));
}

// --- replay --------------------------------------------------------------------------------

/**
 * Re-sends a captured request and renders what came back.
 *
 * Re-signing is on by default because the requests worth replaying are usually signed, and a
 * verbatim replay of a signed request fails at the server in a way that reads as a backend fault
 * rather than as the tool having replayed single-use headers.
 */
async function runReplay(txn, pane, button, edits = null) {
  const previous = button.textContent;
  button.disabled = true;
  button.textContent = 'replaying…';

  let panel = pane.querySelector('.replay-result');
  if (!panel) {
    /*
     * Anchored on `.dtabs`, which is a direct child of the pane.
     *
     * This used to anchor on `pane.querySelector('.section-title')`, and `querySelector`
     * searches the whole subtree: on a row with no redaction banner and no attempt chain — an
     * ordinary row, which is most of them — the first `.section-title` is the one inside a
     * `.dtab-panel`, a *grandchild*. `insertBefore` then throws NotFoundError and the replay
     * never ran. It worked on exactly the rows that happen to have a direct-child section title
     * above the tabs, which is why it survived: those are the interesting rows you reach for
     * when testing replay by hand.
     *
     * Nothing caught it because this script never pressed the button. It does now.
     */
    const anchor = pane.querySelector('.dtabs');
    panel = el('div', 'replay-result');
    // `insertBefore(node, null)` appends, so a pane without tabs still works.
    pane.insertBefore(el('div', 'section-title', 'Replay'), anchor);
    pane.insertBefore(panel, anchor);
  }
  panel.textContent = '';

  try {
    const res = await source.fetch('/api/replay', {
      method: 'POST',
      headers: { 'X-Inspector-Control': '1', 'Content-Type': 'application/json' },
      body: JSON.stringify({
        txnId: txn.id,
        session: state.sessionId || 'latest',
        resign: true,
        // Spread last so an editor's `resign: false` wins. With no edits this is exactly the
        // request the plain replay button has always sent.
        ...(edits || {}),
      }),
    });
    const result = await res.json();
    renderReplayResult(panel, result, edits !== null);
  } catch (e) {
    panel.appendChild(el('div', 'replay-error', `could not reach the daemon: ${e.message}`));
  } finally {
    button.disabled = false;
    button.textContent = previous;
  }
}

function renderReplayResult(panel, result, edited = false) {
  if (!result.ok) {
    panel.appendChild(el('div', 'replay-error', result.error || 'replay failed'));
    if (result.diagnosis) panel.appendChild(el('div', 'replay-diagnosis', result.diagnosis));
    return;
  }

  const head = el('div', 'replay-head');
  head.appendChild(el('span', `status s${statusClass(result.status)}`, result.status));
  head.appendChild(el('span', 'muted', fmtMs(result.ms)));
  // Which request this result belongs to. A 200 under a row whose capture was a 500 is a
  // different fact depending on whether anything was changed, and the panel sits directly
  // above the captured request's own tabs.
  if (edited) head.appendChild(el('span', 'replay-edited', 'edited'));
  panel.appendChild(head);

  // Showing which headers the app regenerated is the difference between trusting the result and
  // wondering whether it went out with the captured values.
  if (result.resignedHeaders && result.resignedHeaders.length) {
    panel.appendChild(el(
      'div',
      'replay-resigned muted',
      `regenerated by the app: ${result.resignedHeaders.join(', ')}`,
    ));
  } else {
    panel.appendChild(el('div', 'replay-resigned muted', 'sent as captured — nothing regenerated'));
  }

  // A diagnosis can accompany a successful send: a 401 is a completed request.
  if (result.diagnosis) panel.appendChild(el('div', 'replay-diagnosis', result.diagnosis));

  if (result.body) {
    const body = el('pre', 'body', result.body);
    if (result.bodyTruncated) body.appendChild(el('div', 'muted', '… truncated'));
    panel.appendChild(body);
  }
}
