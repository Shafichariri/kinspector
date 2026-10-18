---
id: KIN-13
title: >-
  Phase F F4: split the web stylesheet into seventeen contiguous files and add
  static check 4
status: To Do
assignee: []
created_date: '2026-10-18 13:30'
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
- [ ] #1 `web/css` holds the seventeen contiguous slices of the description's table in original order, listed in that order in `styles.txt`; every `@media` block is in the same file as every rule it overrides, after them (750–758 with the 771 block; the reduced-motion blocks at 814, 1135 and 1924; `.pv-visit` 453 with the 504 block; `--stop-bg` 1039/1040)
- [ ] #2 Proof: concatenating the files without their headers equals the F1 file byte for byte
- [ ] #3 Static check 4: every CSS file closes its own braces and comments, outside strings; proven red with an unclosed `{` and with an unclosed comment, then restored
- [ ] #4 Static count 24, the first 20 lines byte-identical to base `f574bda`
- [ ] #5 PaletteParityTest takes the tokens from `00-tokens.css` and fails if any token it checks is declared in another file `styles.txt` lists
- [ ] #6 PaletteParityTest's "outside tokens" assertion proven red with no `--rerun` by a drifted token in `00-tokens.css` and by `--m-get` declared in `35-list.css`, then green when restored
- [ ] #7 The `css/00-tokens.css` budget entry is removed and there are no new budget entries
- [ ] #8 Probes 164 and 21, compared per label (label plus verdict) against base
- [ ] #9 `./gradlew :inspector-daemon:test :inspector-ui:jvmTest :repo-checks:test` passes
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
