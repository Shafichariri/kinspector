---
id: KIN-29
title: 'Signals bug: a slow older load can land under a newer filter'
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 20:21'
labels:
  - 'item:signals-query-one-rule'
dependencies:
  - KIN-25
type: bug
ordinal: 29000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (Charles) on KIN-24, head 5f6b610, with a vm harness (race.js under the scratchpad's h/). Should-fix. loadTransactions has no guard against an older call finishing last, and syncAllSignals re-reads state.filter after awaiting the pager. A slow unfiltered load landing after a tag:cache load leaves state.signals: c1,s1 under tag:cache.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The filter is read once per load and passed down to syncAllSignals
- [ ] #2 A generation counter discards a load superseded by a newer one
- [ ] #3 The harness scenario is red without the counter and green with it, as a static or probe check
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
