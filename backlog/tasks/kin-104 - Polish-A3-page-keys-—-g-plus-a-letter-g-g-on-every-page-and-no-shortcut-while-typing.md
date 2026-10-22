---
id: KIN-104
title: >-
  Polish A3: page keys — g plus a letter, g g, ? on every page, and no shortcut
  while typing
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 09:41'
labels:
  - 'item:web-ui-polish'
dependencies: []
ordinal: 104000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR A (built screens). Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9; questions answered by the 2026-10-22 decision records (size M; only PR B waits; g becomes a prefix; defects folded into A3 and A5). The engineer copies the note's criteria into the plan.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The note's §9 A3 criteria hold, each guard shown red; g g is top of list (decision 2026-10-22), with the legend and an UNRELEASED line
- [ ] #2 The replay textarea no longer fires list shortcuts (folded defect)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Note §9 A3 criteria:
1. page keys and typing skips keys (static), and page keys live, keys legend, keys skip textarea and rail key titles (live), pass and were each shown red as §6 lists.
2. keys in export passes in the archive probe.
3. g d and g f do nothing and are not in the legend at this base, and do work over stub PAGES rows.
4. In Chrome at 400 and 1440, the legend opened by ? on Settings is inside the window (polish <w> legend inside).
5. txn route still reads 'j moves nothing'.
6. The Pace-rule red of chip marks active is pasted into the ticket.
7. UI_EXPECT_CHECKS and ARCHIVE_EXPECT_CHECKS are raised in the same commit, and the render-web-ui.js budget is raised by hand with the reason of §5 (only if the wiring cannot live in scripts/lib; preferred: keep 2354).
8. The g paragraph of §8 is in; the UNRELEASED line saying top of list is now g g (decision 2026-10-22 Q3).
Folded defect (Q5b): the replay textarea no longer fires list shortcuts.
Files: web/js/08-routes.js, 20-navigation.js, 99-init.js, web/index.html, scripts/lib/polish-checks.js, polish-probes.js, polish-cases.js, scripts/render-web-ui.js, render-web-ui-archive.js, .github/workflows/ci.yml, structure/budgets.txt, docs/decisions/web-ui.md, docs/UNRELEASED.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (bob), uncommitted on shafic/web-ui-polish-a3 over d41f617. 08-routes.js: PAGE_KEYS, PAGE_KEY_MS 1500, pageKeyAvailable (in PAGES, not live in an export), readPageKey(pending, key, now) -> { pending, page, top, consumed } (consumed added so the handler knows to leave a key to the list; Shift/Control/Alt/Meta/CapsLock neither start nor spend a prefix), typingTarget (INPUT, SELECT, TEXTAREA, isContentEditable); rail titles 'Stats (g s)', aria-label bare. 99-init.js: typingTarget first (Escape blurs), Escape closes an open legend first, then (no Cmd/Ctrl/Alt held) ? opens the legend and the prefix machine runs on every page and #/txn; g g = move(-1e9) only on Traffic outside #/txn; case 'g' removed. state.pageKeyAt in 00-core.js. #keys-pop moved after #server-banner, g g/G line, ? line, ul#keys-pages from renderKeyLegend() (20-navigation.js); opened by ? it sits under #topbar at the right, from the more menu under the button. .keys + .keys divider in 30-now-chips.css.
Counts: static 124 -> 126; live 264 -> 268 (UI_EXPECT_CHECKS raised); archive 48 -> 49 (ARCHIVE_EXPECT_CHECKS raised). render-web-ui.js stays 2354 (wiring folded into the statsLines line and the require line; budget untouched). Probe bodies in scripts/lib/polish-probes.js.
Red, static: g g not top -> 'page keys: NO - g g gave {"page":null,"top":false}, not {"page":null,"top":true}'; PAGES membership removed -> 'page keys: NO - g d with no diff row gave {"page":"diff",...}; g f with no findings row gave {"page":"findings",...}'; TEXTAREA removed -> 'typing skips keys: NO - textarea is not a typing target'.
Red, live (267 passed, 1 failed each): navigate replaced by location.hash -> 'page keys live: NO - g s left stats shown, focus on BODY; g a ... focus on BODY; ...'; g g routed to nothing -> 'page keys live: NO - g g selected a0000010, the first row is a0000001'; legend left inside #page-traffic -> 'keys legend: NO - the legend is inside #page-traffic, hidden on Settings'; TEXTAREA removed -> 'keys skip textarea: NO - after f, o, g s in the body: focus on stats-title, order flipped true, page stats'; titles left as labels -> 'rail key titles: NO - traffic: title "Traffic", aria-label "Traffic"; ...'.
Pace rule (probeEndpointChip clicking the second chip while asserting the first), polish lines present: 'chip marks active  : false (the clicked chip, and only it)' with page keys live / keys legend / keys skip textarea / rail key titles all yes in the same run; 259 passed, 4 failed (chip marks active and three later lines the broken click's restore disturbed). Restored: 268 passed.
Red, archive: export check removed from pageKeyAvailable (export and tree copy alike) -> 'keys in export: NO - g a moved to traffic (#/traffic); g p moved to traffic (#/traffic); the legend lists traffic, stats, sessions, processes, settings, not traffic, stats' (48 passed, 1 failed). Next archive line re-proved: 'stats offline drawn: NO - 13 rows of 13, ...' with the probe asking for one more row, keys in export yes in that run.
Chrome: polish 1440/400 legend inside 'inside, on top (1237,48 to 1430,295 in 1440x900; 5 page keys; hit true; Escape shut it true)'; red with the legend placed 900px lower: 'FAIL polish 1440 legend inside: outside or covered (1237,944 to 1430,1191 ...)'. --only polish,shell,room,layout against d41f617: base 206 readings 2 failed, head 206 readings 0 failed; moved against base: only the two legend inside readings.
txn route still: 'yes (... j moves nothing; ...)'. g d and g f: nothing and not in the legend now; diff and findings over stub PAGES rows in 'page keys' (live and exported).
Not done: no 'polish restored' line (the §6.2 table lists it without a ticket; the key probes leave Traffic, legend shut, no prefix and no selection themselves). docs/UNRELEASED.md line added at the coordinator's instruction (RUN-RULES 7 otherwise reserves the file).
<!-- SECTION:NOTES:END -->
