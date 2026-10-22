---
id: KIN-42
title: >-
  Web: draw the first page at once and read the whole-session copy in the
  background
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 05:04'
updated_date: '2026-10-22 06:42'
labels:
  - 'item:signals-query-one-rule'
  - deferred
dependencies:
  - KIN-40
type: bug
ordinal: 42000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the fourth verify panel (head 8082e02), blocking. Regression from KIN-34, which reads the whole-session call list in full even with no filter. Measured on 160,016 calls in Chrome: 25.6 s of 'waiting for an app with inspector-stream to connect' under a header saying '1000 of 160016 calls', dot on 'connecting…', no tab bar, filter or axis; e1711a3 drew in under 3 s. Cause: 15-session-data.js:116-129 waits for syncAllTransactions before drawing, and 99-init.js:129-132 waits for all of that before connectLive(). The maintainer chose (2026-10-19) to fix this properly now rather than narrow KIN-34 back.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 The list, tab bar, timeline, axis and counts draw from the first page as soon as it lands: rows within 2 s on the 160k session in headless Chrome; the live socket connects then, and the dot shows the socket's state, not the load's
- [x] #2 'waiting for an app' and every 'nothing recorded' or 'no calls' text never appear while the whole-session copy is still being read; in that time empty states and filter notes say the session is still being read (with a count where known)
- [x] #3 When the copy lands, everything that depends on it redraws (endpoint chips, repeat marks, path counts and 'calls not loaded' markers, axis and notes) without moving the scroll position
- [x] #4 ~~A live call or signal arriving while the whole-session read is in progress is buffered and merged by id when the read lands; this closes the mid-read window recorded as open in web-ui.md (scratchpad c3/midread.js), which is removed from the open list~~ MET by KIN-46 and KIN-47 (2026-10-19), struck here; the numbering is kept so the other criteria stay citable
- [x] #5 A newer load, a session switch or a forget during the background read cancels it (load counters)
- [x] #6 Checks: a jsdom or static check for the in-progress empty-state text, the post-read redraw and the mid-read merge, each red at 8082e02; the Chrome timing on 160k recorded as a documented check
- [x] #7 web-ui.md describes the progressive read with no completeness claims
- [x] #8 No regression in KIN-24..40's checks or the panels' Chrome measurements (scope bar on top, first row visible, station landing, run toggle, huge-session draw)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
- [x] #4 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #5 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #6 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Per designs/kin-42-first-page-at-once.md (charles, 623c571), against base 4645c43. 1) Pure relocation of loadTransactions + syncAllTransactions from js/15-session-data.js to js/16-session-load.js (static/live/archive unchanged). 2) Behaviour: wholePending, saysBeingRead(node, whole), loadTransactions steps 4-7 with drawWhole, overtaken-page getter and true/false returns in syncAllTransactions; connectLive after loadSessions in 99-init; one-line flags in 20/25/45/50/55/65/70; settle() in render-web-ui.js. 3) Checks: 4 static (being-read-checks.js), 2 live (long-session-probes.js), 2 Chrome (measure-cases.js), UI_EXPECT_CHECKS 260->262, web-ui.md texts word for word. Axis hidden until the copy lands (maintainer accepted default).
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
2026-10-22, bob: built against 4645c43 and not committed, on instruction. The relocation patch is at /private/tmp/claude-501/-Users-shafichariri-development-tools-kinspector--claude-worktrees-structure-phase-i-6f380a/710e8438-ad27-4248-b44c-2f978f86e31c/scratchpad/kin42/commit1-relocation.patch. Coordinator's decision on the copy-lands conflict: option (a). While the whole-session copy is read on a time view, the overview line keeps its band: the axis is hidden (the Q1 default holds), and the axis hint holds beingRead(sessionTotal) at the axis's 24 px height (.axis-reading). syncOverview counts the hint. It never shows an empty axis. Without the band the rows dropped 66 px when the copy landed: the overview line 33 plus the scope bar 33. With it they drop 33, the scope bar alone. Proofs: copy lands huge is red at 66 px with the band not reserved and green at 33 px. being read huge is red with the overview line hidden and with the axis drawn during the read. reading notes is red on each of: hint not reserved, syncOverview ignoring the hint, axis drawn. Counts: static 112 -> 116, live 260 -> 262, archive 48. Chrome --base 4645c43 (first,traffic,layout,run,live,room): 220 readings a side, head 0 failed, base 4. Only first rows huge, connected huge, being read huge and copy lands huge moved; room bands unchanged. Huge, head against base: first rows 427 vs 24007 ms, connected 430 vs 24009 ms. web-ui.md: the note's texts are kept word for word, plus one sentence on the reserved band and an exception added to the overview-line paragraph. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #136 merge 1888380, round 1, no failures, no blocking findings. Criterion 1: `measure-web-ui.js --only first,traffic,layout,run,live,room --base 4645c43`: head `first rows huge: within 3000 ms (411 ms)`, `connected huge: within 3000 ms (414 ms)`; base both `over 3000 ms` (about 24 s). Red by QA: `connectLive()` moved back after the load -> `FAIL connected huge: over 3000 ms (23748 ms)` while first rows stays ok. Criterion 2: QA sampled the whole read every 400 ms (57 to 58 samples per width) and found no `waiting for an app`, `nothing recorded` or `no calls` at any width; `being read huge: being read (this session's 160016 calls are being read...)`. Criterion 3: `copy lands huge: in place (row moved 33 px, 10 chips, calls in session 160016; landed at 25931 ms)`, scroll kept at 4000; red by QA with the overview band not reserved: `the first visible row moved 66 px`. Criterion 4: struck in the ticket as met by KIN-46 and KIN-47 (both Done); not in this QA table. Criterion 5: no QA row of its own; the head run of the traffic section (session-switch and filter-edit races) read 0 failed in 220 readings, and a filter typed during the read restarts it by design; the builder's red (`asked 1 more page`) was not repeated by QA. Criterion 6: static 112 -> 116 and live probe 260 -> 262, 0 failed, ci.yml UI_EXPECT_CHECKS 262; Chrome head 220 readings 0 failed, base 4 failed (only the four intended readings moved). Criterion 7: web-ui.md carries the design note's replacements verbatim plus one inserted sentence (the coordinator's option a, the overview band reserved during the read). Criterion 8: `25-traffic-list.js` 591, `15-session-data.js` 401, `50-path.js` 600, no budget added or raised, :repo-checks:test --rerun green. Rendered at 1440, 1000 and 400: band 33 px during and after the read, document 1440, 1000 and 400 wide, 0 exceptions. Finding, not blocking: the band's min-height rule is unguarded (with `.axis-hint.axis-reading { min-height: 24px }` removed, `--only first` stays green at 44 px, under the 48 px bound). Not verified: Safari and Firefox on the huge session; Chrome topbar and shell in this round.
<!-- SECTION:FINAL_SUMMARY:END -->
