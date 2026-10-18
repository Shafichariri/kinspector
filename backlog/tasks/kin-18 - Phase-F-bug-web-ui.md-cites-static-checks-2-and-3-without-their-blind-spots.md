---
id: KIN-18
title: 'Phase F bug: web-ui.md cites static checks 2 and 3 without their blind spots'
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
updated_date: '2026-10-18 15:05'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-14
type: bug
ordinal: 18000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (S3). The decision cites checks 2 and 3 as guaranteeing one declaration per name, but column-0 detection misses indented declarations, '/* x */ function stop(){}' and the second name in a multi-line 'let a = f(1,\n 2), ok = 3'. Engineer chooses fix or disclosure; the choice is recorded in the ticket notes.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Either the scan handles indented declarations, a leading block comment and the second name in a multi-line declaration, each proven red then green; or docs/decisions/web-ui.md states the limit in one sentence where it cites the checks
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Engineer, commit d8a4765. CHOICE: disclosure, not a fix. Seeing an indented top-level declaration needs brace depth across strings, template literals and regex literals - a JavaScript parser inside a script that must run with nothing installed. docs/decisions/web-ui.md now says, in one sentence where it cites checks 1-3: 'checks 2 and 3 read declarations at column 0, so a top-level one written indented, after a block comment on the same line, or as the second name of a let whose first line ends inside brackets is not seen'. The topLevelDeclarations doc in web-audits.js, which claimed a declaration is never silently left out by its shape, now lists the same three. Evidence that each blind spot is real (appended to js/50-path.js, restored after each): '  function stop(){}' -> 'no browser global names : yes (231 names ...)', exit 0; '/* x */ function stop(){}' -> same, exit 0; 'let kin18a = String(1,\n  2), ok = 3;' plus 'function ok(){}' -> 'one declaration per name  : yes (234 names in 20 file(s))', exit 0 (ok declared twice, unseen). No guard was added, so there is nothing to prove red.
<!-- SECTION:NOTES:END -->
