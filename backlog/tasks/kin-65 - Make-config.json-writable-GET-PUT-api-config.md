---
id: KIN-65
title: 'Make config.json writable: GET/PUT /api/config'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 04:39'
updated_date: '2026-10-21 13:34'
labels:
  - 'item:web-foundation-shell'
dependencies: []
ordinal: 65000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-shell (1a, order row 10, run of 2026-10-20).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Merge of known keys; unknown file keys kept; unknown request key 400 by name; invalid values 400
- [x] #2 Malformed file 409 and left untouched; atomic rename
- [x] #3 Flags reported and winning; nothing applied before restart
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (uncommitted). archive/ConfigFile (read/merge, lock, temp+ATOMIC_MOVE, symlink kept), DaemonConfig.flags, http/ConfigRoutes. Values are written with a hand indenter because kotlinx prettyPrint re-encodes numbers (2.50 -> 2.5), which broke 'kept as it was'. Red: in-place write -> file-key assertion fails; unknown-key preservation dropped -> fails. Note: PUT /api/config under a transparent gated node of its own stays 405 (nothing below /api/config), so that break is not red there; a gated path node of its own (route{handle}) is red in the new WrongMethodStatusTest case (403 for POST/DELETE/PATCH) and in ReadRoutesOpenTest.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (PR A, e9f7131, qa/web-foundation-shell-prA-report.md), live plus ConfigFileTest/ConfigRoutesTest. AC1: PUT {maxSessions:7} creates the file; a file with findings.rule 2.50 is kept byte for byte after PUT {maxTotalMb:100}; {bogus:1} 400 naming the key; {port:70000} 400; [1] 400; text/plain 415. AC2: with '{ broken' on disk PUT gives 409 and the shasum is unchanged; ConfigFileTest 'a save writes a temporary file beside the old one and renames it over' passes, no config.json.tmp-* left. AC3: after PUT maxSessions 7, effective.maxSessions stays 100 while file says 7; a restart with --port 8109 against a file holding port 8555 reports port 8109 and flags ['port']. R2 (config routes un-gated) fails coverage, ConfigRoutesTest and WrongMethodStatusTest.
<!-- SECTION:FINAL_SUMMARY:END -->
