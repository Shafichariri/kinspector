---
id: KIN-160
title: 'Daemon: Findings.TIMELINE is a total order when seq is mixed in one session'
status: To Do
assignee: []
created_date: '2026-10-23 12:16'
labels:
  - 'item:findings-timeline-total'
dependencies: []
ordinal: 160000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Roadmap row 37a, lane D, size S. The findings comparator in service/Findings.kt is 'mono, then seq when both rows carry it, then id', which is intransitive when some rows in a group carry seq and some do not: (seq 0, id z) < (seq 1, id a) < (no seq, id m) < (seq 0, id z). Findings.kt, PaymentRules.kt and SensitiveSource.kt sort with it. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label. Out of scope: which calls a rule flags; what seq means or when it is assigned. QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The first commit is a red test that sorts a group of 32 or more rows with mixed seq through the comparator and shows it throw, or shows two input orders giving two outputs
- [ ] #2 The item's record says whether any archive the current library and daemon write can hold mixed seq in one session, and how that was established
- [ ] #3 After the fix the same test is green and the order no longer depends on input order
- [ ] #4 Rows that all carry seq are ordered exactly as today
- [ ] #5 A finding's evidence lists calls in the order item:timeline-seq-ties draws them on the timeline
<!-- AC:END -->
