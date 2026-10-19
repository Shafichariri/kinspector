---
id: KIN-44
title: >-
  Web probes: a 1203-call CI session proves the endpoint chips and repeat marks
  read past the clamp, live and offline
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 10:06'
updated_date: '2026-10-19 11:32'
labels:
  - 'item:web-transactions-paged'
dependencies:
  - KIN-43
modified_files:
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/ProbeFixture.kt
  - inspector-daemon/src/test/kotlin/dev/inspector/daemon/ProbeFixtureTest.kt
  - scripts/lib/long-session-probes.js
  - scripts/render-web-ui.js
  - scripts/render-web-ui-archive.js
  - scripts/lib/measure-fixtures.js
  - .github/workflows/ci.yml
  - structure/budgets.txt
type: task
ordinal: 44000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Part of item:web-transactions-paged (docs/ROADMAP.md, "The web traffic list stops at 1000 calls and does not say so"), first acceptance criterion: in a session of 1001 or more calls the endpoint chips count the oldest call and a repeat group that includes it is marked, proven by a probe that fails when the read is put back to one page, live and in the exported file. Design approved by the maintainer on 2026-10-19. KIN-42 is not part of this item.

Read first: docs/decisions/web-ui.md ("The CI probe session is written by the daemon's own writer at CI time, and never committed." and "The web UI keeps an unfiltered copy of the session, and two features depend on it.") and docs/decisions/exports.md.

State today. KIN-34 already reads `state.allTransactions` through `routePages` as `transactionPages` (`inspector-daemon/src/main/resources/web/js/15-session-data.js`), so the page side is expected to pass. What is missing is a probe on a real session past the 1000-row clamp; the CI probe session has 16 calls. No web/js change is expected in this ticket, and `routePages` is not touched.

The fixture. `writeProbeFixture` in `inspector-daemon/src/test/kotlin/dev/inspector/daemon/ProbeFixture.kt` also writes a second session of exactly 1203 calls through `writeSession`, beside the probe session, replacing it on a second run as the probe session is replaced. Chosen contents:
- It starts earlier than the probe session (for example `2025-12-31T09:00:00.000Z`), so the probe session stays first in the picker, and `latest` stays pointed at the probe session. Name it with the probe session's id scheme (for example `2025-12-31T09-00-00_probe-long_ci_debug`), checked against how session ids are formed.
- Call #0 (lowest `mono`) and call #203 are identical by `duplicateKey` (`web/js/30-repeats-endpoints.js`: method, URL, status, req/res bytes), for example `GET /v3/long/twice`, with different `callId`s and at most 3000 ms apart in `mono` (the default duplicate window). No other call has that key. #203 is the oldest of the newest 1000, so it is drawn and #0 is not.
- Every other call carries a distinct query (for example `/v3/long/bulk?n=<i>`), so it forms no repeat group, and the session has at most three endpoints, so every chip is shown under the default limit of 10.
- Every host is under `example.invalid`, as in the probe session.
`ProbeFixtureTest` reads it back through `SessionRepository` and asserts each of those facts.

Other readers of the probe directory. `scripts/lib/measure-fixtures.js`'s `sessionDir` throws unless the directory holds exactly one session; it picks the probe session by id instead. `render-web-ui.js` probes that read the picker's first option, or count sessions, must still read the probe session; check each, and note that nothing in the probes deletes a session.

The live probe. In a new `scripts/lib/long-session-probes.js`, with only the wiring and report lines in `scripts/render-web-ui.js`: switch the picker to the long session through its real `change` event, let the page settle, then report
- `long chips count oldest`: the `twice` endpoint chip counts 2 (it reads 1 if the copy is the newest 1000);
- `long repeat marked`: the drawn row for call #203 carries the repeat mark (it has none if the copy is the newest 1000);
then switch back to the probe session so later probes see what they saw before.
`render-web-ui.js` is at its line budget (2748 in `structure/budgets.txt`): the probe bodies live in `scripts/lib/`, and the budget entry is raised by hand only for the wiring and report lines, with the reason appended to that entry as the earlier raises were.

The archive probe. CI also exports the long session (`curl -sf .../api/sessions/<long id>/export.html`) and passes it to `scripts/render-web-ui-archive.js` as `INSPECTOR_UI_ARCHIVE_LONG`. When set, the script opens it in a second JSDOM with the network forbidden exactly as the first, and reports `long chips count oldest`, `long repeat marked` and the same `no network attempts` and `no page errors` lines for it. Unset, those lines report n/a.

Check counts. `UI_EXPECT_CHECKS` and `ARCHIVE_EXPECT_CHECKS` in `.github/workflows/ci.yml` change in the same commit as the checks, by the number of passing lines added.

No public API, no schema, no change to the security boundary; the daemon change is in its test classpath only.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 writeProbeFixture (ProbeFixture.kt) also writes a 1203-call session, older than the probe session, with latest still on the probe session; ProbeFixtureTest asserts through SessionRepository: 1203 calls, call #0 and call #203 share one duplicateKey within 3000 ms with different callIds, no other call has that key, no other repeat group, at most three endpoints, every host under example.invalid
- [x] #2 render-web-ui.js, with the bodies in scripts/lib/long-session-probes.js, reports 'long chips count oldest' (the twice chip counts 2) and 'long repeat marked' (the row for call #203 carries the repeat mark) yes against the CI daemon, and switches back to the probe session afterwards
- [x] #3 render-web-ui-archive.js, given INSPECTOR_UI_ARCHIVE_LONG (the long session's export.html, written by CI), reports the same two lines yes, with no network attempts and no page errors for that file
- [ ] #4 Red proof, live and archive: with transactionPages in web/js/15-session-data.js put back to reading one page, both 'long chips count oldest' and 'long repeat marked' report NO in both probes; restored, yes
- [x] #5 Every existing live and archive probe line reads what it read before; scripts/lib/measure-fixtures.js picks the probe session by id, and measure-web-ui.js still builds its probe, big and huge sessions
- [x] #6 UI_EXPECT_CHECKS and ARCHIVE_EXPECT_CHECKS in .github/workflows/ci.yml change in the same commit as the checks; render-web-ui.js's budget in structure/budgets.txt is raised only for the wiring and report lines, with the reason appended; :repo-checks:test passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (a300e4b). Live red proof (transactionPages reading one page): both long lines NO, 182 passed 2 failed; restored 184. ARCHIVE red proof could not go red at a300e4b: loadTransactions still asked limit=2000, which the export honours since KIN-43, so the offline list held all 1203 rows and syncAllTransactions copied it without calling transactionPages. Re-run on KIN-45 (limit 1000): archive 'long chips count oldest' NO (twice chip counts 1) and 'long repeat marked' NO, 33 passed 2 failed; restored 35. Existing live lines diffed against the committed script on a one-session copy of the fixture: only notes 'session options' and 'session rows' moved (1 -> 2). Old measure-fixtures sessionDir throws on the two-session dir; new one builds probe/big/huge. Ready for QA.

2026-10-19, KIN-48 (maintainer decision: an export draws every embedded call): the archive half of AC#4's red proof is retired. Offline the copy is the list, so the pager (transactionPages reading one page) is never called and cannot be put back to one page. The live half stays.

Built in cd198c4 (KIN-48): the export now asks for every embedded call, so offline transactionPages is never reached past the list; the archive half of AC#4 stays retired as above. Live half unchanged. The export's middle-offset slicing is now guarded by the archive line 'long offset 1000 sliced' instead.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Commit a300e4b. Verified by doug (QA) at 68315b4, panel close: yes (architect and UX yes): static 79/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0 (DecisionsTest 5/0); live probe 185/0; archive probe 37/0. Not verified: CI (branch not pushed). Live probe: 'long chips count oldest' and 'long repeat marked' yes; live red proof (transactionPages reading one page) both NO, restored yes. AC#4 left unticked: only its live half is met; the archive half was retired on 2026-10-19 by KIN-48 (offline the copy is the list, so the pager is never called). AC#6 ticked for the local check counts and budgets; CI itself not run.
<!-- SECTION:FINAL_SUMMARY:END -->
