---
id: KIN-17
title: >-
  Phase F bug: static check 3 denylist misses browser built-ins the page calls
  (Map, atob, encodeURIComponent)
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
updated_date: '2026-10-18 15:55'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-11
type: bug
ordinal: 17000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (architect S2, confirmed by QA). 'function Map(){}', 'function atob(){}' and 'const encodeURIComponent = 1' all pass static check 3, yet the page calls Map 21x, encodeURIComponent 17x and atob 1x; redeclaring one in a classic script shadows the built-in for every later file. The comment at scripts/lib/web-audits.js:298 over-claims the denylist.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Check 3 also rejects any name in Object.getOwnPropertyNames(globalThis) as seen by Node at check time, in addition to the named list
- [x] #2 Each of 'function Map(){}', 'function atob(){}' and 'const encodeURIComponent = 1' is proven red, then removed and green
- [x] #3 The comment at web-audits.js:298 is made accurate
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Engineer, commit 160dc2c. auditWindowNames now refuses WINDOW_DENYLIST plus Object.getOwnPropertyNames(globalThis) read at check time (130 names under Node v23.3.0 when run from check-web-static.js). Green line: 'no browser global names   : yes (231 names, none of 46 browser globals or 130 JavaScript globals)'. Each break appended at column 0 to js/50-path.js, run, file restored from a copy: 'function Map(){}' -> 'NO - a browser global declared: Map at js/50-path.js:543 (a JavaScript global)', exit 1; 'function atob(){}' -> 'NO - a browser global declared: atob at js/50-path.js:543 (a JavaScript global)', exit 1; 'const encodeURIComponent = 1' -> 'NO - a browser global declared: encodeURIComponent at js/50-path.js:543 (a JavaScript global)', exit 1. Restored: '25 checks, 0 failed', exit 0. The comment over WINDOW_DENYLIST no longer over-claims; it says built-ins come from globalThis and names the DOM-only members neither source covers (requestAnimationFrame, matchMedia, getComputedStyle - confirmed absent from Node's globalThis). web-ui.md's citation of check 3 updated. Line 27 of the static output changed text (count suffix); it is outside the first 24.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA panel 2 at e065f11: all 3 ACs Verified. Static check 3 also refuses every JavaScript global the page calls (Map, atob, encodeURIComponent). DoD no-client-names left unticked: the term list is kept out of the repository, so it was not runnable here; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
