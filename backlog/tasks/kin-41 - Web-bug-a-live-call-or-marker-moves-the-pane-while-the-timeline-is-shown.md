---
id: KIN-41
title: 'Web bug: a live call or marker moves the pane while the timeline is shown'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-19 05:04'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-40
type: bug
ordinal: 41000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the fourth verify panel (head 8082e02), blocking. Regression from KIN-33, which removed the timeline's own scroller. Measured on a 3016-call session with the timeline shown, live on and the pane in the middle: one call through ws.onmessage moves scrollTop 14927->29853 (oldest-first) or 14984->0 (newest-first); a marker does the same. At e1711a3 it stayed at 14884. Cause: renderList's follow (web/js/25-traffic-list.js:348-352) scrolls the shared #list-pane whatever view is shown. follow-checks.js calls renderTimeline() directly, so it never sees this; web-ui.md's KIN-38 follow paragraph is therefore false in practice. Harnesses: $TMPDIR/doug4/tools/livemove.js and scratchpad simon4/tools/. Architect's fix direction: set state.timelineFresh in setSortOrder, loadTransactions and setTimeRange so those still follow.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 With the timeline (or path or waterfall) shown and scrolled back, a live call or marker leaves scrollTop unchanged in both orders
- [ ] #2 With the pane at the end it follows, a new row is still followed
- [ ] #3 The order toggle, a filter load and a time-range brush still follow on the timeline, as at 8082e02 (state.timelineFresh set in setSortOrder, loadTransactions and setTimeRange)
- [ ] #4 A new check drives the real socket txn and marker path while the timeline is scrolled back; red at 8082e02, green after
- [ ] #5 web-ui.md's follow paragraph is true and names the check
- [ ] #6 Chrome measurement at 1440 and 400 recorded
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
- [ ] #4 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #5 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #6 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
