---
id: KIN-13
title: >-
  Phase F F4: split the web stylesheet into seventeen contiguous files and add
  static check 4
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-18 13:30'
updated_date: '2026-10-18 15:55'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-10
  - KIN-11
modified_files:
  - inspector-daemon/src/main/resources/web/css
  - inspector-daemon/src/main/resources/web/styles.txt
  - scripts/check-web-static.js
  - scripts/lib/web-audits.js
  - inspector-ui/src/jvmTest/kotlin/dev/inspector/ui/PaletteParityTest.kt
  - structure/budgets.txt
type: task
ordinal: 13000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Phase F, commit F4 (design note §1 "CSS cut", §3 static check 4 and PaletteParityTest, §5 F4). Approved by the maintainer on 2026-10-18 as written. Read first: `docs/decisions/web-ui.md`, `docs/decisions/shared-rules.md` (the palette paragraph). shared-rules.md, on test inputs: "without the input declaration, adding a bad case file left `jvmTest` up to date and green — measured" — F1 gave PaletteParityTest its declared input; this commit relies on it.

`css/00-tokens.css` is cut into **contiguous slices in original order only**, so the cascade equals today's. Rule: an `@media` block stays in the same file as every rule it overrides, after them. The cut satisfies every pair UX named: 750–758 with the 771 block, the reduced-motion blocks at 814, 1135 and 1924, `.pv-visit` 453 with the 504 block, and `--stop-bg` 1039/1040. Line numbers are base `style.css`:

| File | Lines |
|---|---|
| `00-tokens.css` | 1–81 |
| `05-base.css` | 82–169 |
| `10-panes.css` | 170–254 |
| `15-waterfall.css` | 255–345 |
| `20-path.css` | 346–581 |
| `25-axis.css` | 582–627 |
| `30-now-chips.css` | 628–747 |
| `35-list.css` | 748–929 |
| `40-detail.css` | 930–1159 |
| `45-settings.css` | 1160–1246 |
| `50-replay.css` | 1247–1358 |
| `55-duplicates.css` | 1359–1399 |
| `60-timeline.css` | 1400–1529 |
| `65-tabs.css` | 1530–1635 |
| `70-browser.css` | 1636–1796 |
| `75-sessions.css` | 1797–1828 |
| `80-json-tree.css` | 1829–1926 |

Concatenation equality does not prove each sheet is the same (QA objection 6): a CSS file ending inside an unclosed `{` or comment is closed automatically as its own sheet but swallows the next file when concatenated, and the static audits read the concatenation. Static check 4 holds that. The `css/00-tokens.css` entry in `structure/budgets.txt` is removed (the file is now 81 lines). Independent of F3; the static count of 24 assumes F2's three checks.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 `web/css` holds the seventeen contiguous slices of the description's table in original order, listed in that order in `styles.txt`; every `@media` block is in the same file as every rule it overrides, after them (750–758 with the 771 block; the reduced-motion blocks at 814, 1135 and 1924; `.pv-visit` 453 with the 504 block; `--stop-bg` 1039/1040)
- [x] #2 Proof: concatenating the files without their headers equals the F1 file byte for byte
- [x] #3 Static check 4: every CSS file closes its own braces and comments, outside strings; proven red with an unclosed `{` and with an unclosed comment, then restored
- [x] #4 Static count 24, the first 20 lines byte-identical to base `f574bda`
- [x] #5 PaletteParityTest takes the tokens from `00-tokens.css` and fails if any token it checks is declared in another file `styles.txt` lists
- [x] #6 PaletteParityTest's "outside tokens" assertion proven red with no `--rerun` by a drifted token in `00-tokens.css` and by `--m-get` declared in `35-list.css`, then green when restored
- [x] #7 The `css/00-tokens.css` budget entry is removed and there are no new budget entries
- [x] #8 Probes 164 and 21, compared per label (label plus verdict) against base
- [x] #9 `./gradlew :inspector-daemon:test :inspector-ui:jvmTest :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built in 4eace25 (local, not pushed). Evidence in $TMPDIR/kin13/.

Cut: the 17 slices of the table, from F1's file (cmp-identical to 8b20dcc css/00-tokens.css and to base f574bda style.css). Every boundary falls on a blank line or a section comment between rules. Script: kin13/split.js.

Proof (kin13/prove.js, output kin13/proof.txt): each file's first line is a one-line header comment; the 17 bodies concatenated in styles.txt order = 61736 bytes, F1 file 61736 bytes, 'IDENTICAL: byte for byte'.

@media pairs: 750-758 with 771 (35-list); reduced-motion 814 (35-list, overrides 771's transition), 1135 (40-detail, .copy-field 1112), 1924 (80-json-tree, .jt-act 1876); .pv-visit 453 with 504 (20-path); --stop-bg 1039/1040 (40-detail).
DISCREPANCY for the reviewer: the 771 block also overrides #panes (172) and #panes.panes-wide (184) grid-template-columns, which are in 10-panes.css, not 35-list.css. So 'every @media block is in the same file as every rule it overrides' is false for that one block under the approved table. The cascade is still identical (contiguous, original order; concatenation proof). No contiguous cut under the 600-line budget can satisfy it: 10-panes would have to run 170-929. Implemented the table as approved; F5's decision wording ('an @media block stays in the file of the rules it overrides') needs to account for this.

Static check 4 (auditCssClosesPerFile, scripts/lib/web-audits.js), printed in --- scripts ---: 'css files close : yes (475 top-level blocks in 17 file(s))'; F1's single file gives the same 475. 24 checks, 0 failed. First 20 lines diff-identical to kin10/static-base.txt; whole output = F3's plus that line (kin13/static-before.txt vs static-after.txt).
Red: unclosed { appended to 25-axis.css -> 'NO - css/25-axis.css ends with 1 unclosed {', exit 1. Unclosed comment -> 'NO - css/25-axis.css ends inside the comment opened on line 48', exit 1. Restored output cmp-identical.

PaletteParityTest: tokens from 00-tokens.css; new test 'no checked token is declared outside the tokens file'. Red, plain ./gradlew :inspector-ui:jvmTest, no --rerun: (1) --m-get 154 161 172 -> 1 2 3 in 00-tokens.css: 'dark --m-get differs between the web and the overlay', 134 tests, 1 failed. (2) ':root { --m-get: 154 161 172; }' appended to 35-list.css: 'declared outside css/00-tokens.css, where this test cannot see it win the cascade: --m-get in css/35-list.css', 1 failed. Each restored -> green. Logs kin13/palette-red1.log, palette-red2.log.

Budget: css/00-tokens.css line removed; no new entries; largest CSS file 40-detail.css 231 lines.

Probes against kin10/probe on 8231: live 'checks: 164 passed, 0 failed, 1 n/a, 0 unclassified, 42 notes'; archive 'checks: 21 passed, 0 failed', 'every file inlined: yes - 20 script(s) and 17 stylesheet(s)'. Per label vs base (kin10/labels.py): 209/209 and 25/25 lines, identical sequence True. Stylesheet audit lines identical to F3's report.

Gradle: :inspector-model:jvmTest :inspector-daemon:test :repo-checks:test :inspector-ui:jvmTest BUILD SUCCESSFUL (159 / 259 / 35 / 134 tests, 0 failed).
Not run: Chrome CDP diff (QA's harness), iOS/Android targets.

Conductor, 2026-10-18: criterion #1 ('every @media block in the same file as every rule it overrides') cannot hold for the 1199px block at original line 771, which also overrides #panes / #panes.panes-wide (lines 172, 184, in 10-panes.css); no contiguous cut under the 600-line budget fixes it. The rule that guarantees the cascade, and the one UX's objection asked for, is: styles.txt order is the cascade, the files are contiguous slices of the original in original order, and an @media block never loads before a rule it overrides. That holds here (771 loads after 172/184). F5 writes the decision paragraph in those terms; QA judges criterion #1 against this restatement and reports the exception explicitly.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA panel 1 at 90618e2: all 9 ACs Verified. CSS split into seventeen contiguous files; QA re-ran the concatenation proof (61736 bytes identical to base). AC#1 judged against the conductor's restatement (the 1199px @media block overrides #panes across files and loads after them). Static check 4 added. Chrome: 0/16 configs differ served and export at e065f11. DoD no-client-names left unticked: the term list is kept out of the repository, so it was not runnable here; release-steward scans what it can.
<!-- SECTION:FINAL_SUMMARY:END -->
