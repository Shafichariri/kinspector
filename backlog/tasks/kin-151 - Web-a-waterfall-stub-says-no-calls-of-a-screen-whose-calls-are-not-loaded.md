---
id: KIN-151
title: 'Web: a waterfall stub says no calls of a screen whose calls are not loaded'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 08:38'
updated_date: '2026-10-23 09:30'
labels:
  - 'item:web-waterfall-stub-says-unloaded'
dependencies: []
ordinal: 151000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 32 (§3 → Bugs found). Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:web-waterfall-stub-says-unloaded holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
State at stop (bob, 2026-10-23). The bug holds at head a4368ab, read from the code and not yet seen on a running page. waterfallGroups (45-visits.js:163) builds visits from visibleTransactions(). A stub's body reads 'no calls' or 'no matching calls' (:239) and its stats read '0 calls · …' (:233), even for a visit whose calls are older than the oldest loaded call. web-ui.md already says so: 'The waterfall's stubs do not take this rule yet.' Planned fix, not built: reuse the path's markUnloaded rule (50-path.js:142). That is, mark the stub when the list is short of matched and the whole-session copy's visit holds a call below the oldest loaded call (inRange under a stretch). Such a stub's stats and body read like the path's: 'calls not loaded — the list holds the newest N of M'. While wholePending, or with the copy unmarked, a stub starting below the oldest loaded call says beingRead(sessionTotal) or 'calls not loaded'. A stub whose visit truly has no calls keeps 'no calls'. Probe: a check-web-static case over the 3016-call stub, plus a live probe body in scripts/lib (render-web-ui.js is at its 2354 budget). Done: reading only. No files changed, no commits, no red proofs run, no counts measured. Not done: everything else, including the In Progress status.

Built (bob, 2026-10-23), uncommitted in this worktree, ready for QA.
Plan criteria, copied from ROADMAP: (1) confirm at head on a running page, close with evidence if it no longer holds; (2) a stub for a screen whose calls are not loaded says so, in words that do not claim none; (3) a stub for a screen with truly no calls still says no calls; (4) a probe proven red. Out of scope: loading older calls; the list's own note.
(1) Confirmed at 0a4946d on a running page. Headless Chrome, the measure fixture's big session (3016 calls, list holds the newest 1000), waterfall at 1440 and 400. Five stubs (before the first screen, Home, Product, Cart, Product) read '0 calls · 0ms · —' and 'no calls'; the whole-session copy holds 2, 1, 1, 2 and 1 calls in them.
(2) markUnloaded and a new oldestHeld moved from 50-path.js to 45-visits.js: one rule for the path and the waterfall. waterfallGroups marks a stub notLoaded. After the copy lands it uses markUnloaded. While the copy is read, or after a failed read leaves it unmarked, any stub beginning below the oldest held call is notLoaded. A notLoaded stub's stats are empty. Its body reads 'calls not loaded', or beingRead(sessionTotal) while the copy is read. Running page after the fix: big, all five stubs read 'calls not loaded' with no count. huge (160,016 calls): during the read they read "this session's 160016 calls are being read…", and after landing 'calls not loaded'.
(3) A stub the copy shows no call in still reads 'no calls', or 'no matching calls' under a filter or stretch.
(4) Static: 'wf stub clamp no filter|path:/v3/shop|path:/v3/auth|being read' in check-web-static.js, over the 3016-call stub (long-session-checks.js). Red proofs: stub never marked = 3 NO; copy treated as read during the read = 'being read' NO; copy not asked = Idle reads 'calls not loaded' where 'no calls' was due; count kept = '0 calls · 0ms · 0 B' NO. Pace neighbour: markUnloaded off = path clamp shop and no filter NO. Chrome: 'layout <session> <w> filter=<f> waterfall stubs' in measure-cases.js. On base it reads 'claims' on big filter=none; on head, 'honest'.
Counts: static 140 -> 144. Live 325 and archive 61 unchanged, so ci.yml is unchanged.
No live jsdom probe: the CI fixture has no clamped session with screens, because the 1203-call session has none.
Not done: a non-stub group past the clamp (Checkout '1000 calls') still counts only held rows. The path says 'older calls not loaded' there; the waterfall does not. This is possibly a follow-up.
<!-- SECTION:NOTES:END -->
