---
id: KIN-153
title: 'Daemon: inspector findings --fail-on gates a CI build on findings'
status: To Do
assignee: []
created_date: '2026-10-23 09:43'
labels:
  - 'item:findings-ci-check'
dependencies: []
ordinal: 153000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Ordered 2026-10-23 by the product owner, design first (architect's note confirmed both questions yes; shape decided by the maintainer's proxy, 2026-10-23-findings-ci-gate-shape). Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label, copied below. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 inspector findings --data DIR --session ID --fail-on <severity> exits non-zero when any undismissed finding is at or above that severity, and zero otherwise. It prints the findings it counted, with masked evidence.
- [ ] #2 It reads the same rules and dismissals from config.json as the page and list_findings, so the three agree on the same session.
- [ ] #3 Without --fail-on, the command behaves as the findings item built it.
- [ ] #4 The gate is shown failing in this repository's CI, as part of the item: a CI step on the installed launcher (bin/inspector) runs the command against the probe fixture's payments session and must read exit 1, and against a fixture session with no finding at that severity and must read exit 0.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
