---
id: KIN-88
title: 'Guard: QA''s live-traffic Chrome driver, committed'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 18:15'
updated_date: '2026-10-21 18:52'
labels:
  - 'item:guard-live-traffic-driver'
dependencies: []
ordinal: 88000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Batch 1 of the order decided 2026-10-21. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. Built without QA before merge; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:guard-live-traffic-driver in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, branch shafic/guard-live-traffic-driver).

Acceptance criteria (roadmap item:guard-live-traffic-driver):
1. The /ingest client and the CDP page helpers live in scripts/lib/; Chrome via lib/measure-cdp.js (window.__sockets, exceptions); fetch recorder + delay hook beside it; no second launcher.
2. Wired into scripts/measure-web-ui.js as a case --only can select; burst bound, filter-edit race and session-switch race are judged (exit 1), not logged.
3. Each judged reading proven red by breaking its rule (re-read per live row; let an old filter's / old session's re-read land), green when restored.
4. Nothing beyond 127.0.0.1; every streamed host ends in .invalid.
5. QA briefs name it among the scripts/lib harnesses (roadmap-cycle SKILL.md Promote-a-harness rule, plus scripts/AGENTS.md key files).

Files: new scripts/lib/ingest-client.js (app-side /ingest client, refuses non-loopback origins and non-.invalid hosts); new scripts/lib/live-traffic-cases.js (fetch recorder + delay hook installed per page, snapshot helpers, the case: setup two sessions, burst, filter-edit race, session-switch race, plus judged loopback-only and no-exceptions rows); scripts/lib/measure-cdp.js gains page.onNewDocument(source) so the recorder installs through the one launcher; scripts/measure-web-ui.js gains --only traffic with its own empty-data daemon per tree, run alone after the parallel batch; scripts/AGENTS.md and .claude/skills/roadmap-cycle/SKILL.md name it; docs/decisions/web-ui.md re-read paragraph names the new readings. measure-cases.js untouched. No web/ change, no public API.

Built (bob). node scripts/measure-web-ui.js --only traffic: 9 readings, 0 failed, exit 0. Red proofs, each in the served web/ (served ahead of the jar, no rebuild), restored after:
- oweReread calls rereadLive() on every row: FAIL traffic burst reads: over bound (150 list reads, at most 6), exit 1.
- stands() without its filter clause: FAIL traffic filter edit race, old lands first only, exit 1.
- stands() without its session clause: FAIL traffic session switch race, old lands first only, exit 1.
- rereadLive's call write unguarded (if (true), ticket and stands() both gone): all four race readings FAIL, exit 1.
- stands() filter clause alone removed, single-order draft: still green, because the ticket (readSent/readWritten) also drops an old re-read landing after the edit's load. Hence each race runs in both landing orders.
- 99-init.js fetching http://localhost:9 and throwing: FAIL traffic reaches (beyond 127.0.0.1) and FAIL traffic exceptions. The first draft stayed green on reaches (it read origins from __asked, which the races clear); fixed with an uncleared __origins log, then red as above.
- ingest-client without checkHost and host api.qa.test: FAIL traffic hosts: not .invalid.
- ingest-client refuses http://localhost:8099 and https://example.invalid:443 as origins, and host api.example.com on call(); run against a scratch daemon.
Restored: 9 readings, 0 failed. check-web-static.js 107 checks 0 failed; :repo-checks:test pass.
<!-- SECTION:NOTES:END -->
