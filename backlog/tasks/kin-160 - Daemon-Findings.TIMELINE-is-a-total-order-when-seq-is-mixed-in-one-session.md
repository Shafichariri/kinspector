---
id: KIN-160
title: 'Daemon: Findings.TIMELINE is a total order when seq is mixed in one session'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 12:16'
updated_date: '2026-10-23 12:20'
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built by bob, 2026-10-23. Reproduced: real, not a paper bug.
- Red commit 64bef8a (FindingsTimelineOrderTest) against the old comparator: a 32-row group with mixed seq (java.util.Random seed 4, the first seed that threw) throws 'IllegalArgumentException: Comparison method violates its general contract!' sorted directly, from PaymentRules.kt:147 (R2) and from Findings.kt:201 (via SensitiveSource); the throw escapes FindingsService.report, so the page, list_findings, inspector findings and the gate all fail. The roadmap's cycle gives 3 outputs from 6 input orders; the exhaustive small case (mono x seq{none,0,1} x id{a,m,z}) finds 42 faults.
- Mixed seq in one session, how established (code reading, not run with the old binaries): seq first shipped in v1.3.0. The v1.2.0 daemon's SessionWriter re-encodes each decoded NetworkTransaction, and v1.2.0's model has no seq field, so it writes none. A 1.3.0+ device's StreamSink resends resumeSessionId from memory on reconnect, and a 1.3.0+ daemon reopens that session within SESSION_RESUME_GRACE_MS (5 min), appending rows that carry seq. So a daemon upgraded across 1.3.0 and restarted while an app streams leaves mixed seq. The current library with the current daemon alone cannot: every capture site (InspectorPlugin, the OkHttp interceptor, Recorder.mark and emit) calls nextSeq(), resume is same-process only, and the writer keeps seq. A hand edit of index.jsonl can.
- Fix e70a24d: TIMELINE = compareBy(mono, seq != null, seq, id). Total, so input order no longer matters. All-seq rows go by mono, seq, id, as before. The three sites keep calling the one comparator. In the resume session the timeline keeps the older rows (first in the file) ahead of the seq rows in a tie, which is what the key says. Known difference: two rows without seq tie by id here, by file order on the timeline (FindingRow has no file position).
- Breaks, each red, then restored: the old comparator at Findings.kt:201 alone, at PaymentRules ROW_TIMELINE alone, and at SensitiveSource alone; ?: Long.MAX_VALUE (rows without seq last); and seq dropped from the key.
<!-- SECTION:NOTES:END -->
