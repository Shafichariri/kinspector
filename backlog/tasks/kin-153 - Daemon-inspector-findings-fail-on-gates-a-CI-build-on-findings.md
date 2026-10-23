---
id: KIN-153
title: 'Daemon: inspector findings --fail-on gates a CI build on findings'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 09:43'
updated_date: '2026-10-23 09:49'
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan and build (bob, 2026-10-23), from designs/findings-ci-check.md and decision 2026-10-23-findings-ci-gate-shape.
- service/FindingsGate.kt: parse/judge/lines, pure. Counts report.high/medium/info at or above the threshold (taken before the limit, dismissed left out); never report.findings. CannotJudge on configError and on total == 0.
- Main.kt: --fail-on. stdout unchanged (the report). stderr: header, one line per listed finding at or above, 'and N more', each coverage line; evidence = location, path, masked hint only. Exit 1 found, 0 none, 2 cannot judge (bad --fail-on incl. no value, no such session, bad --category, empty session, configError). Without --fail-on: unchanged, errors exit 1.
- Tests: FindingsGateTest (pure), FindingsCliTest (child JVM exits; dismissal honoured; no-flag unchanged), FindingsLeakTest (gate stderr surface, --fail-on info), FindingsGateStreamTest (real StreamSink -> daemon: double submit exits 1, single 0, sink stopped before delivery exits 2 'holds no calls').
- CI: web job step 'Findings gate exits 1 on a high finding and 0 on none' on bin/inspector: payments session exit 1, base session 2025-12-30T09-00-00_probe_ci_debug exit 0 (no finding at any severity).
- Docs: DAEMON.md §7 --fail-on paragraph and 'Findings in CI' recipe (wait for rows, assert sink.dropped == 0 before stop()); decision paragraph in daemon-and-mcp.md; Key files row in inspector-daemon/AGENTS.md. No INTEGRATION.md change.
- Not done on purpose: no catch around the report read. No reachable throw was found (an unreadable index, body dir and broken meta all read as data), so a catch could not be shown red. A throw exits 1 (still a red build); item 37a owns the only suspected throw.
<!-- SECTION:NOTES:END -->
