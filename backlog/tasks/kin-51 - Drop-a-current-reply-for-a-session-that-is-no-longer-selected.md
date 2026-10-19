---
id: KIN-51
title: Drop a /current reply for a session that is no longer selected
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 13:58'
updated_date: '2026-10-19 14:28'
labels:
  - 'item:web-current-session-guard'
dependencies: []
type: bug
ordinal: 51000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
`refreshCurrent` (inspector-daemon web/js/70-timeline.js, ~line 366) awaits GET /api/sessions/{id}/current and writes state.current without checking that the session asked about is still selected. On a failed read it keeps the previous session's values.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 A `/current` reply for a session that is no longer selected is dropped. The strip shows the selected session's values or none, never another session's.
- [x] #2 A failed `/current` read after a switch leaves the strip empty for the new session. It does not keep the previous session's values.
- [x] #3 A `render-web-ui.js` probe holds a `/current` reply, switches session, then releases it, and asserts what the strip shows. It fails with the guard removed, and with the `catch` fallback put back.
- [x] #4 No other state writer changes.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob):
- Fix: inspector-daemon/src/main/resources/web/js/70-timeline.js refreshCurrent only. Capture state.sessionId before the await, as syncAllSignals/syncAllTransactions do (KIN-29), and return without writing or rendering if the selected session changed. A failed read writes [] rather than keeping state.current. No other writer touched (loadSignals, afterSessionChange, picker handler unchanged).
- Probe: scripts/lib/current-guard-probes.js, probeCurrentAfterSwitch(ctx), returning [label, verdict] pairs like long-session-probes.js. Two lines: 'now late reply dropped' (hold the selected session's /current reply from a direct refreshCurrent() call, switch session through the picker, let the switch settle, release the reply with the old session's real values, assert the strip lists exactly the new session's own /current) and 'now failed read empty' (switch with the new session's load-time /current held so state.current still holds the old values, make a refreshCurrent() for the new session fail, assert the strip is empty; then release and settle). Switches back to the starting session. Wired into render-web-ui.js with one call and one report loop (+2 lines; budget raised in structure/budgets.txt as KIN-44 did); UI_EXPECT_CHECKS in ci.yml +2.
- Red proofs: (1) guard line removed -> 'now late reply dropped: NO'; (2) catch(() => state.current) put back -> 'now failed read empty: NO'. Both run against a daemon serving the probe fixture, then restored.

Built (bob).

Fix, 70-timeline.js refreshCurrent only: takes state.sessionId before the await, as syncAllSignals/syncAllTransactions take theirs (KIN-29), and returns without writing or rendering when the selected session has changed. A failed read now gives [] instead of keeping state.current.

Failure choice: empty on every failure, not only when the session changed. state.current does not record which session it belongs to, so refreshCurrent cannot tell 'the previous values are this session's' from 'they are the session just left'. Tagging it with a session would mean changing loadSignals and afterSessionChange too, which criterion 4 rules out. It also matches loadSignals, whose own /current read already falls back to []. The cost: a single transient failure on a session that did not change blanks the strip until the next signal or load. For a strip that claims to show what the app holds now, showing nothing is safer than showing stale values with nothing to mark them.

Guard is session-only, not a load counter. signalLoad is not reused: a same-session reply arriving out of order is still that session's own values, and that is not in this ticket's scope. A→B→A while a reply for A is held: the reply lands while A is selected and is written. That is A's own values, which criterion 1 allows.

Probe: scripts/lib/current-guard-probes.js (probeCurrentAfterSwitch). Wired in scripts/render-web-ui.js with 3 lines (require, call, report loop), budget 2752 -> 2755 in structure/budgets.txt. choose() is now exported from lib/long-session-probes.js. ci.yml UI_EXPECT_CHECKS 185 -> 187. Lines: 'now late reply dropped' and 'now failed read empty'. Both call the page's refreshCurrent directly and stub only the /current route they hold.

Probe command (daemon on the probe fixture, port 8231):
INSPECTOR_UI_ORIGIN=http://127.0.0.1:8231 INSPECTOR_UI_EXPECT_CHECKS=187 node scripts/render-web-ui.js > ui.html
-> now late reply dropped: yes (2026-01-01T09-00-00_probe_ci_debug's late reply dropped; the strip lists 0 of 2025-12-31T09-00-00_probe-long_ci_debug's own)
-> now failed read empty: yes (a failed read for 2025-12-31T09-00-00_probe-long_ci_debug leaves the strip empty, not 2026-01-01T09-00-00_probe_ci_debug's 10)
-> checks: 187 passed, 0 failed, 1 n/a, 0 unclassified, 42 notes; exit 0

Red 1, guard removed: sed -i '' '/  if (sessionId !== state.sessionId) return;/d' inspector-daemon/src/main/resources/web/js/70-timeline.js, then the same probe command:
-> now late reply dropped: NO - after 2026-01-01T09-00-00_probe_ci_debug's late reply the strip lists state CartViewModel, cache catalog, cache cart, state CheckoutViewModel, screen Checkout, screen Product, screen Cart, tap add_to_cart, analytics home_viewed, screen Home, where 2025-12-31T09-00-00_probe-long_ci_debug's none were due
-> checks: 186 passed, 1 failed ...; failed: now late reply dropped; expected 187 passing checks, got 186; exit 1

Red 2, fallback put back: sed -i '' 's|/current`).catch(() => \[\]);$|/current`).catch(() => state.current);|' on the same file, then the same probe command:
-> now failed read empty: NO - after a failed read for 2025-12-31T09-00-00_probe-long_ci_debug the strip lists state CartViewModel, cache catalog, ... screen Home
-> checks: 186 passed, 1 failed ...; failed: now failed read empty; expected 187 passing checks, got 186; exit 1
Both mutations were restored from a copy, and the green run above is on the restored file.

Other checks: node scripts/check-web-static.js -> 79 checks, 0 failed. render-web-ui-archive.js (CI's two exports, EXPECT 37) -> 37 passed, 0 failed. ./gradlew :inspector-daemon:test :repo-checks:test -> BUILD SUCCESSFUL, daemon 268 tests and repo-checks 35 tests, 0 failures. measure-web-ui.js --base (snapshot of a426883) -> moved against base: nothing. The same 2 FAILs on base and head: first rows huge and connected huge, about 26.9 s against the 3000 ms limit. They were already failing before this change.

No decision file edited. web-ui.md lists the overtaken-load guards by name (callLoad, signalLoad), and this guard could be added there. That is a docs call for whoever owns the decision file.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
AC1 Verified: live probe 'now late reply dropped: yes'; two-session archive, strip lists 10 of the selected session's own; Chrome render at base shows the old session's values after the late reply, at head the new session's. AC2 Verified: 'now failed read empty: yes'; Chrome render: strip hidden, items []. AC3 Verified: guard deleted -> 186 passed 1 failed (now late reply dropped); .catch(() => state.current) put back -> now failed read empty fails; restored -> 187 passed. AC4 Verified: web/ diff is one hunk in refreshCurrent; loadSignals (15-session-data.js) and afterSessionChange (90-sessions.js) unchanged. check-web-static 79/0; live probe 187/0; archive probe 37/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0; commit identity and client-name scans pass. Accepted trade-off: a failed /current read now always empties the strip, also on an unchanged session, until the next signal or load (product-owner may revisit).
<!-- SECTION:FINAL_SUMMARY:END -->
