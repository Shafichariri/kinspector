---
id: KIN-147
title: 'Docs housekeeping (2026-10-23): the live-config sentence and 256 MiB'
status: To Do
assignee: []
created_date: '2026-10-23 07:59'
labels:
  - 'item:docs-housekeeping-2026-10-23'
dependencies: []
ordinal: 147000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 34. Placed 2026-10-23 by the product owner (PR #187). Acceptance criteria are the roadmap item's own, from docs/ROADMAP.md under the heading carrying this ticket's item label. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 docs/decisions/daemon-and-mcp.md reads 'Only findings and findingDismissals apply live; every other key waits for the next start' or words that cannot be read the other way
- [ ] #2 docs/DAEMON.md says the diff budget is 256 MiB, not 256 MB
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
