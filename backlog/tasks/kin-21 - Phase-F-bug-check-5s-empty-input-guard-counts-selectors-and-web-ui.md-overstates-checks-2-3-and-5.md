---
id: KIN-21
title: >-
  Phase F bug: check 5's empty-input guard counts selectors, and web-ui.md
  overstates checks 2, 3 and 5
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 15:22'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-16
  - KIN-18
type: bug
ordinal: 21000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the second verify panel (qa2 and arch2, agreed) at e065f11; the maintainer chose on 2026-10-18 to fix it before phase F closes.

Reproductions:
1. In a copy, change `\s*:/g` to `\s*=/g` in `declaredProperties` (scripts/lib/web-audits.js ~447) and rename css/35-list.css to css/08-list.css with styles.txt reordered: check 5 still prints `yes (15 @media selector(s) in 17 file(s))` and `25 checks, 0 failed`. Its empty-input guard counts @media selectors, not compared pairs.
2. docs/decisions/web-ui.md (~38-45) says check 5 matches 'an overlapping property'; the code compares selector text exactly, matches property names by prefix, and skips every at-rule except @media. Missed real overrides: border-style/border-width vs @media border-right; inset vs top/right/bottom; .panes-wide#panes vs #panes.panes-wide; a rule inside @supports. False reds: !important in the @media rule; border vs border-radius. None of these shapes is in today's CSS.
3. web-ui.md (~22-25) lists three blind spots of checks 2 and 3 as if complete; a fourth exists: a second declaration on a column-0 line after ';' or '}' (`const kinA = 1; const stop = 2;` and `function kinB(){} function Map(){}` both pass).
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Check 5 counts the (@media rule, earlier-or-later plain rule) pairs it actually compares, requires at least one, and prints that count on its green line; proven red with the broken property reader in (1), green restored
- [ ] #2 web-ui.md and check 5's doc comment state the matching rule exactly (same selector text, property overlap by name prefix with shorthand/longhand not resolved, @media only with other at-rules not walked), or the code is extended to cover the missed cases; engineer's choice, recorded. No false claim remains
- [ ] #3 Checks 2 and 3 refuse a second declaration on a column-0 line (HarnessError / exit 2, as multi-name declarations already are), proven by both examples in (3); OR web-ui.md lists it as a fourth blind spot. Engineer's choice, recorded; refusing is preferred because it costs nothing today
- [ ] #4 The first 24 lines of 'node scripts/check-web-static.js' stay byte-identical; probes 164 / 21 unchanged; gradle suites green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
- [ ] #4 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #5 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #6 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
