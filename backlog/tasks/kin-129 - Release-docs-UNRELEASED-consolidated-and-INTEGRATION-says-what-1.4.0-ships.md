---
id: KIN-129
title: 'Release docs: UNRELEASED consolidated and INTEGRATION says what 1.4.0 ships'
status: In Progress
assignee: []
created_date: '2026-10-22 13:28'
labels:
  - 'item:release-1-4-docs'
dependencies: []
ordinal: 129000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
ROADMAP §6 → Release 1.4.0 → 'The 1.4.0 release notes and INTEGRATION.md say what 1.4.0 ships' (order row 13c, lane S). Docs only. No public API, no noop twin, no golden file.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 UNRELEASED's overlapping owed lines are one line each (6/8/18, 5/9/19, 13/23); each sentence a later item made false is dropped, and the change lists it with the item that overtook it.
- [ ] #2 Lines for findings, sensitive-data detection and the merged parts of web UI polish are in, and each is in the notes only once QA has verified it.
- [ ] #3 Every claim in the consolidated notes matches STATUS → Verified at the cut, and nothing is claimed for an item the cut left out.
- [ ] #4 INTEGRATION gains GET /api/sessions/{id}/findings, MCP list_findings, inspector findings, and the findings and findingDismissals keys with their live read, with a version bump and a §14 entry that says what to do.
- [ ] #5 v60 and v61 point to v57 for the 1.3.0 web UI.
- [ ] #6 It lands last before the tag; a later merge that changes what 1.4.0 ships reopens it.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Pass 1 (PR #163) did AC 1, 4 and 5 in full, and AC 2 and 3 for what is on main; each line is marked verified or QA owed. The final pass owes: findings T7/T8 (KIN-118/119) words in the findings line plus any §6e or version-entry change; detection T5 (KIN-124): 'or Settings', dismissing from the page, and §6e 'in the file only'; the QA-owed lines re-checked against STATUS (KIN-115, 116, 117, 120-123, 127, 104, 128); the release version line and the 1.4.0 coordinates entry pointing back to v59-v64 and later; any further consumer-visible entry takes the next number after v64; UNRELEASED emptied to its header at the cut.
<!-- SECTION:NOTES:END -->
