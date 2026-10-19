---
id: KIN-39
title: >-
  Signals bug: the unfiltered-then-filter route has no guard, and web-ui.md
  overclaims
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 22:45'
updated_date: '2026-10-19 06:33'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-36
type: bug
ordinal: 39000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the third verify panel (head a86dde2), blocking; fails KIN-24 AC6. Reverting 15-session-data.js:294 to 'if (!filter)' leaves static, live and archive all green, yet load-unfiltered-then-type-a-filter makes the path read 'nothing recorded' over visits with calls. checkPastTheClamp tests only filter-first. web-ui.md:161-162 says the copy costs 'nothing at all when no filter is set', false since KIN-34. web-ui.md:191 says the copies 'go stale in three ways, and each is closed'; the paragraph lists five and one is open (KIN-40).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 An unfiltered-then-filter check, red with 'if (!filter)', green now
- [x] #2 web-ui.md:161-162 and :191 are true, with no completeness claim
- [x] #3 web-ui.md states the '0 of N match' limit until KIN-40 lands, or drops the statement if KIN-40 fixes it
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by Bob (engineer), commit 182fadc (after KIN-40's d321929, so AC3's statement is dropped rather than added: KIN-40 fixed the '0 of N match' limit, and web-ui.md now describes the fix, not the limit). No web file changed.
NEW CHECK: 'unfiltered then filter' in check-web-static.js's session state section (scripts/lib/session-state-checks.js checkUnfilteredThenFilter): the real 15/25/45/50 files over the 3016-call clamp stub; loadTransactions with no filter, then 'path:/nothing', then the path's counts. Green: '"0 of 2500 match / 0 of 516 match" after an unfiltered load held 3016 of 3016 in the whole copy'. The stub and the path-count reader are now shared with checkPastTheClamp (clampPage, pathCounts); its four lines print as before. Static 64 -> 65 checks, 0 failed.
RED PROOF: 15-session-data.js line 291 set to 'if (!filter) {' -> 'unfiltered then filter: NO - "0 of 484 match / 0 of 516 match", where "0 of 2500 match / 0 of 516 match" was due; the unfiltered load left 1000 of 3016 in the whole copy', 65 checks, 1 failed, and no other line moved (the gap the panel found). Restored -> 65/0.
DOCS (docs/decisions/web-ui.md): the 'costs nothing at all when no filter is set' sentence (was :161-162) now says it costs nothing only while the list's page holds the whole session, and that past the clamp the copy is the same paged read, naming the check. The 'go stale in three ways, and each is closed' sentence (was :191) now reads 'have gone stale in the ways below, and all but the last are closed; this is what has been found, not every way there is', and the paragraph ends with the open one: a live row arriving between pages of the whole-session read is in neither the read nor the copy (owned by item:web-transactions-paged, per ROADMAP dbabdbe). Re-verified against this tree with the panel's scratch harness c3/midread.js: 'marked=true all=2500 daemon=2501 list has LIVE=true whole copy has LIVE=false'.
RUNS: static 65/0, live probe 182/0, archive 26/0, :inspector-daemon:test --rerun 267/0, :repo-checks:test --rerun 35/0. Chrome measurements not rerun for this commit: no web file changed, and the served jar's web is byte-identical to the tree (checked 50-path.js out of the jar).

QA (fourth verify panel doug-4, charles-4, simon-4, head 8082e02): every AC of this ticket Verified; ACs ticked, ticket left In Progress because the item closes together. Panel reports filed as received; the per-AC command and decisive line are in the panel reports, not copied here.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
All ACs Verified by QA at 8082e02 (fourth panel); per-AC commands and decisive lines are in that panel's report and the ticket's QA note, with the engineer's red/green proofs in the implementation notes. Item-wide regression run at 8f64058 by a fresh QA (KIN-41 verification): static checks 71 passed 0 failed, live probe 182, archive probe 26, :inspector-daemon:test 267/0, :repo-checks:test 35/0. DoD 3 (no client names) left unticked: the scan terms were not available to the tracker, same as every ticket in this item.
<!-- SECTION:FINAL_SUMMARY:END -->
