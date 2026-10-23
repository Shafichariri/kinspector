---
id: KIN-145
title: >-
  Polish B1: the planned pages conform — keys, page document, targets, empty
  states
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 05:16'
updated_date: '2026-10-23 08:04'
labels:
  - 'item:web-ui-polish'
dependencies:
  - KIN-102
  - KIN-103
  - KIN-104
  - KIN-105
  - KIN-106
  - KIN-107
  - KIN-139
  - KIN-140
  - KIN-116
  - KIN-117
  - KIN-118
  - KIN-119
  - KIN-124
ordinal: 145000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR B. Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9 B1/B2; questions answered by the 2026-10-22 decision records. Deps A1–A6 (KIN-102..107), session diff T5/T6 (KIN-139/140), findings T5–T8 (KIN-116..119), detection T5 (KIN-124).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 g d opens #/diff and g f opens #/findings live (page keys diff, page keys findings, each red with its letter removed); in an export g f opens Findings and g d does nothing
- [ ] #2 #page-findings carries page-doc
- [ ] #3 At 1440 and 400 every visible .btn on Diff and Findings, dismiss and restore included, is at least 24px tall, and every .settings-note/.page-prose is at most 72ch at 1440
- [ ] #4 Each why-state on Diff and Findings makes none of the three forbidden claims of §3 P10 (one probe line per page)
- [ ] #5 shell <w> page diff fits and findings fits stay green
- [ ] #6 The Pace red is repeated and the counts are raised
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, 2026-10-23, branch shafic/web-ui-polish-b on b2fe9b4):
- AC1 keys. Already covered before this ticket: static `page keys` (g d / g f live, with each row removed, g f and g d in an export); live `page keys live` iterated PAGES, so it pressed g d and g f inside one line; archive `keys in export` pressed g f and checked the legend, but never pressed g d. Change: `page keys live` keeps Traffic/Stats/Sessions/Processes/Settings; new live lines `page keys diff` and `page keys findings` (polish-probes.js); `keys in export` also presses g d (no count change).
- AC2: #page-findings already carries page-doc (Findings T5, 22fadcb). Guard: new static `page documents` — every PAGES page but Traffic is a page-doc section in index.html.
- AC3: new Chrome reading `polish <w> planned controls` at 1440 and 400, served: Diff with a route's detail open (calls in A/B, diff-open) and a filter that matches nothing (clear filter); Findings with the dismiss form open and a dismissed fold open (dismiss…, dismiss, cancel, restore); every drawn .btn (and .diff-open) >=24px, every .settings-note/.page-prose <=72ch at 1440. PAGE_READ's note selector takes .page-prose too.
- AC4: live `diff why states` and `findings why states`: each why-state driven by state/stubbed replies, judged against P10's three claims (waiting only with no session; a filter that emptied the page offers clear filter; no 'yet' of an ended session).
- AC5: shell <w> page diff/findings fits re-read with --base.
- AC6: Pace red on `chip marks active` (and the immediate next line, `findings rail`); ci.yml UI_EXPECT_CHECKS raised.
- Follow-up: Stats .stats-scroll flex shrink check in Chrome (session-diff PR B hand-over).
No render-web-ui.js line (at budget): the new live lines ride probePageKeys, already wired.

Proofs (bob, 2026-10-23). Counts at 1b7a45b, re-measured on a clean base worktree with CI's web job replayed on 8107: static 139, live 321, archive 61. Head: static 140 (+page documents), live 325 (+page keys diff, page keys findings, diff why states, findings why states), archive 61 (keys in export extended, no new line). ci.yml UI_EXPECT_CHECKS 321 -> 325; ARCHIVE_EXPECT_CHECKS stays 61.
Red then green (each restored, then green):
- page documents: page-doc taken off #page-findings -> 'NO - #page-findings is "page", not a page-doc'.
- page keys diff: ['d','diff'] out of PAGE_KEYS -> 'NO - g d left traffic shown at #/traffic, focus on BODY'.
- page keys findings: ['f','findings'] out -> 'NO - g f left traffic shown at #/traffic, focus on BODY'.
- diff why states / findings why states: 'yet' put in each page's no-calls sentence -> 'NO - both ended, no calls: "Neither session holds a call yet." ("yet" of an ended session)' and 'NO - ended, no calls: "No calls in this session yet, so nothing to check." ("yet" of an ended session)'.
- diff why states: clear filter not drawn -> 'NO - filter: "No routes match the filter `path:/polish-probe-matches-nothing` on either side." (a filter emptied it and no clear filter is offered)'.
- diff why states: 'waiting for an app to connect' put in the no-calls sentence -> 'NO - both ended, no calls: … ("waiting" with a session); B recording, no calls: … ("waiting" with a session)'.
- keys in export: Diff made live:false -> 'NO - g d moved to diff (#/diff); the legend lists traffic, stats, findings, diff, not traffic, stats, findings'.
- Pace: chip marks active (second chip clicked) -> 'chip marks active  : false', the new lines green beside it; findings rail, the next guard after the new lines (stats+1 -> +2) -> 'findings rail : NO - rail order traffic, stats, findings, …'.
- Chrome polish 400 tables whole: red on 1b7a45b and on head before the fix: 'FAIL polish 400 tables whole: cut (stats-scroll 630px for 968px, diff-scroll 1100px for 1100px)'.
- Chrome polish 1440 planned controls: red before the fix: 'short: diff route /v3/shop/cart/items 14.5px, …' (.diff-open, the route button, which is not .btn).

Round end (bob): Chrome --only polish,shell,room,layout --base 1b7a45b: head 243 readings 0 failed, base 243 readings 2 failed; moved against base: 'polish 400 tables whole: cut -> whole' and 'polish 1440 planned controls: short -> at least 24px', both meant to. shell <w> page diff fits and page findings fits green at 1440/1000/600/400. Further reds: dismiss… and restore forced to 16px -> 'FAIL polish 1440 planned controls: short (… findings dismiss… 16px, … findings restore 16px …)' at 1440 and 400; the prose cap removed -> 'FAIL polish 1440 planned prose: wider than 72ch (23 notes, widest 1232px …)'. CI web replay on 8107: static 140/0 failed, live 325 passed, archive 61 passed. :repo-checks:test --rerun 38 tests, 0 failed. Fixes: .stats-scroll flex: none (85-stats.css), .diff-open min-height --target-min (88-diff.css; P10 names diff-open, the ticket names .btn). Sheet at head: scratchpad/sheet-pr-b (209 files with index.html), handed to KIN-146.
<!-- SECTION:NOTES:END -->
