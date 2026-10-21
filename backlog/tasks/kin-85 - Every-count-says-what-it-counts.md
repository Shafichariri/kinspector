---
id: KIN-85
title: Every count says what it counts
status: Done
assignee: []
created_date: '2026-10-21 13:45'
updated_date: '2026-10-21 15:15'
labels:
  - 'item:web-foundation-list-room'
dependencies:
  - KIN-81
ordinal: 85000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Design: the architect's note for item:web-foundation-list-room (run of 2026-10-20), Tickets. Built without design review by the maintainer's choice; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 (f3) counts agree live and exported on the capped session, red with an unlabelled scope count and with the tab counting rows
- [x] #2 (f2) the count on one line at 1000 for every wording
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in d9484cf. Traffic tab counts state.sessionTotal (js/20-navigation.js); scope bar 'N of M calls under this prefix', 'N/M under prefix' below 520px (js/25-traffic-list.js, css/35-list.css); overview hint 'N calls in this session' and in a stretch 'N calls in this stretch · <from> – <to>', counted from the whole session (js/55-axis.js); glance 'calls in session' ('calls in stretch' under a brushed stretch) (js/65-browser-detail.js). Review finding: the hint was cut at 1440/1000 because .axis had flex-basis auto (its svg's width); it now has a zero basis and gives way (css/25-axis.css). Checks: 'counts agree' live and 'counts agree in export' on the 1203-call session, label table includes row 3's header and list-note match wordings (red: bare scope count -> unlabelled, tab counting rows -> 1000 for the session); row 5's 'live glance plain/filter' read 'calls in session'; 'live header filter' now asserts the tab's session count, with the reason in a comment (red: tab back to rows); measure-web-ui 'room <w> hint whole' at 1440 and 1000 (red: flex-basis auto) and 'topbar <w> count' now also at 1000 and for 'N of M calls' and the filter-plus-stretch wording. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA round 1 at 4fe71dc, Verified. AC1: on the 1203-call session at every width: tab 'traffic 1203', header 'newest 1000 of 1203 shown', scope '1203 of 1203 calls under this prefix', overview and glance 1203; export header '1203 of 1203 calls'. Red via m3 (unlabelled scope count): 'counts agree: NO - unlabelled: scope 1203 of 1203'. The red with the tab counting rows is the engineer's, not repeated by QA. AC2: the header count stays on one line at 1000 for every wording. DoD2: m3 red then restored. DoD3: name scan no match.
<!-- SECTION:FINAL_SUMMARY:END -->
