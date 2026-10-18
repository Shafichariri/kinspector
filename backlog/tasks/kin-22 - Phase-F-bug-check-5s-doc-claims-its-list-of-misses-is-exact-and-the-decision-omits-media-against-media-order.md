---
id: KIN-22
title: >-
  Phase F bug: check 5's doc claims its list of misses is exact, and the
  decision omits @media-against-@media order
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 15:35'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-21
type: bug
ordinal: 22000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by QA verifying KIN-21 at 526dab7 (AC#2 'No false claim remains' Failed).

Reproduction (QA probe: $TMPDIR/qa3/probe5.js; each shape passed to auditMediaOverrideOrder returns 'yes (1 pair(s) compared...)'):
1. The doc comment in scripts/lib/web-audits.js says 'what it does not resolve is exactly this. Missed: ...' but six unlisted shapes are also missed: an earlier plain !important; combinator spacing (#p > .a vs #p>.a); vendor aliases (-webkit-user-select vs user-select); logical vs physical (margin-left vs margin-inline-start); 'all: unset'; CSS nesting.
2. An @media block loading before another @media block that overrides it is never compared. Live today: in css/35-list.css the prefers-reduced-motion '#detail-pane { transition: none }' must load after the 1199px block's '#detail-pane { transition: ... }'; moving it above stays '25 checks, 0 failed'.
3. docs/decisions/web-ui.md says a second declaration after ';' or '}' on a column-0 line is refused, but 'const a = 1; /* x */ const stop = 2;' exits 0.
4. 'one prefixing the other' should say the code requires a '-' at the boundary.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The check 5 doc comment and web-ui.md make no completeness claim about misses ('among them', not 'exactly this'), and both state that @media-against-@media order is not checked, naming the reduced-motion/1199px pair in 35-list.css as the live case held by review and by keeping both blocks in one file.
- [ ] #2 web-ui.md's checks 2/3 sentence is true as written for a comment between the two declarations (either the scan refuses it, proven exit 2, or the sentence excludes it).
- [ ] #3 The prefix wording matches the code (a '-' is required at the boundary).
- [ ] #4 No code behaviour change beyond, optionally, the checks 2/3 refusal; first 24 lines of 'node scripts/check-web-static.js' byte-identical; static still 25 checks, 0 failed.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
