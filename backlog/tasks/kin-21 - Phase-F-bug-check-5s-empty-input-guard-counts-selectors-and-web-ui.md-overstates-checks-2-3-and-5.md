---
id: KIN-21
title: >-
  Phase F bug: check 5's empty-input guard counts selectors, and web-ui.md
  overstates checks 2, 3 and 5
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 15:22'
updated_date: '2026-10-18 15:55'
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
- [x] #1 Check 5 counts the (@media rule, earlier-or-later plain rule) pairs it actually compares, requires at least one, and prints that count on its green line; proven red with the broken property reader in (1), green restored
- [x] #2 web-ui.md and check 5's doc comment state the matching rule exactly (same selector text, property overlap by name prefix with shorthand/longhand not resolved, @media only with other at-rules not walked), or the code is extended to cover the missed cases; engineer's choice, recorded. No false claim remains
- [x] #3 Checks 2 and 3 refuse a second declaration on a column-0 line (HarnessError / exit 2, as multi-name declarations already are), proven by both examples in (3); OR web-ui.md lists it as a fourth blind spot. Engineer's choice, recorded; refusing is preferred because it costs nothing today
- [x] #4 The first 24 lines of 'node scripts/check-web-static.js' stay byte-identical; probes 164 / 21 unchanged; gradle suites green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
- [x] #4 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #5 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #6 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Engineer, commit 526dab7. CHOICES: checks 2/3 REFUSE (HarnessError, exit 2) a second declaration after ';' or '}' on a column-0 line (new declaresAgain in topLevelDeclarations); no line in web/js trips it (static run exit 0, 231 names as before). Check 5: STATE THE RULE EXACTLY, code not extended. web-ui.md and the auditMediaOverrideOrder / propertiesOverlap docs now say: same selector text (whitespace collapsed, nothing else normalised), overlap by name prefix with shorthand/longhand not resolved, custom properties only when equal, @media the only at-rule walked (nested @media included). Missed: other selectors on specificity, reordered compounds (.panes-wide#panes), non-prefix longhands (border-style/border-right, inset/top), @supports/@layer/@container. False reds: border vs border-radius, !important in the @media rule. A scan with the audit's own readers found none of those shapes in today's CSS (no other at-rule; no !important inside @media; no reordered compound; only prefix pairs are custom properties, which compare by equality). CHECK 5 COUNT: pairs = (@media selector, plain rule earlier or later) with same selector and overlapping property; fails at 0. Green: 'media overrides load last : yes (11 pair(s) compared, 15 @media selector(s) in 17 file(s))', '25 checks, 0 failed'. RED (copy in $TMPDIR/kin21/copy; '\s*:/g' -> '\s*=/g' in declaredProperties + css/35-list.css -> css/08-list.css after 05-base.css in styles.txt), exit 1: 'media overrides load last : NO - compared no @media rule with a plain rule setting the same property, so this checked nothing' / '25 checks, 1 failed'. Same copy with HEAD's audit: exit 0, 'yes (15 @media selector(s) in 17 file(s))' (the reported false green). Reader restored, rename kept: exit 1, '#panes in @media (max-width: 1199px) at css/08-list.css:26 loads before css/10-panes.css:7; ...'. Both restored: exit 0, '11 pair(s) compared'. CHECKS 2/3 RED (appended to js/50-path.js in the copy): 'const kinA = 1; const stop = 2;' -> exit 2, 'harness: js/50-path.js:542: cannot read the names declared by: const kinA = 1; const stop = 2;'; 'function kinB(){} function Map(){}' -> exit 2, 'harness: js/50-path.js:542: cannot read the names declared by: function kinB(){} function Map(){}'. With HEAD's audit both exit 0 ('232 names'). Restored: exit 0; copy diffs clean against the tree. FIRST 24 LINES: baseline from HEAD (stash) in $TMPDIR/kin21/static-before.txt; head -24 diff empty; whole output differs only on the check-5 line. PROBES (fixture via writeProbeFixture, daemon on 8231, jsdom 29.1.1): live 'checks: 164 passed, 0 failed, 1 n/a, 0 unclassified, 42 notes' exit 0; archive 'checks: 21 passed, 0 failed' exit 0. GRADLE (--rerun): :inspector-daemon:test 259/0 failures, :repo-checks:test 35/0, :inspector-ui:jvmTest 134/0, BUILD SUCCESSFUL. check-client-names.sh NOT RUN here: no terms available (CLIENT_NAME_TERMS unset).

QA at 526dab7: AC#1, #3, #4 Verified; AC#2 Failed (completeness claim in check 5's doc comment) — follow-up in KIN-22.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA at 526dab7: AC#1, #3, #4 Verified, AC#2 Failed and was filed as KIN-22; after KIN-22 (f05603b, wording pass b733aa4) AC#2 re-verified. Check 5 counts compared pairs; checks 2-3 refuse two declarations per line; static output byte-identical across 76b6c99/f05603b/b733aa4. This ticket carries a duplicated DoD block; items 3 and 6 are the no-client-names item, left unticked since the term list is not on this machine.
<!-- SECTION:FINAL_SUMMARY:END -->
