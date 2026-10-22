---
id: KIN-102
title: >-
  Polish A1: icon controls — --target-min, .btn.btn-icon, SVG for the four icon
  controls
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 07:27'
updated_date: '2026-10-22 08:05'
labels:
  - 'item:web-ui-polish'
dependencies: []
ordinal: 102000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 27, PR A (built screens). Design: the architect's note for item:web-ui-polish (run of 2026-10-20) §9; questions answered by the 2026-10-22 decision records (size M; only PR B waits; g becomes a prefix; defects folded into A3 and A5). The engineer copies the note's criteria into the plan.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The note's §9 A1 criteria hold, each guard shown red
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Note §9 A1 criteria:
1. At 1440, 1000 and 400, live and in an export, dark and light: #detail-full, #detail-hide (column mode), #drawer-close (drawer mode), #more-button and the Sessions row ✕ are each at least 24x24 px, their SVG (or the ✕ text) at least 14 px, and hit at the centre (polish <w> icon targets).
2. Titles and aria-labels are unchanged word for word.
3. The list pane's top at 1440 is 145 px ±2, and room and shell read as at base (--base).
4. icon target css and icon controls svg are in, each shown red.
5. The web-ui.md icon paragraph of §8 is in.
Files: web/css/00-tokens.css, 40-detail.css, 35-list.css, 10-panes.css, web/index.html, web/js/40-copy-export.js, web/js/90-sessions.js, scripts/lib/polish-checks.js, scripts/lib/polish-cases.js, scripts/check-web-static.js, scripts/measure-web-ui.js, docs/decisions/web-ui.md.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (bob). --target-min 24px (00-tokens); .btn.btn-icon in 40-detail.css after .btn-sm (min-width/min-height from the token, padding 0 4px, line-height 1, 16px); centring under :where(.btn.btn-icon) at zero specificity so .drawer-close/.detail-hide/[hidden] still decide display (a two-class display would have shown #drawer-close in column mode); .btn-icon[hidden] hides. Removed .detail-tools .btn (35-list) and .more-button (10-panes); the drawer's narrow .drawer-close is inline-flex. SVGs for #detail-full, #detail-hide, #drawer-close (index.html, titles/aria unchanged), ⋮ drawn as three circles; Sessions row ✕ keeps text with btn-icon. Deviation: the note's 'min-width: auto' for the row ✕ would let it fall under 24 wide; it keeps min-width 24 and grows to fit 'sure?'.
Static (check-web-static 120 -> 122): icon target css red: 'NO - .btn.btn-icon does not take min-height from var(--target-min)'; with .more-button { font-size: 13px ... } put back: 'NO - sized outside .btn.btn-icon: .more-button { font-size: 13px } (css/10-panes.css); ...'. icon controls svg red with &#10530; back in #detail-full: 'NO - #detail-full holds 0 svg, not one; #detail-full holds text "&#10530;"'. Both green after restore.
Chrome polish <w> icon targets <served|export> <dark|light> at 1440/1000/400: 12 readings, all 'at least 24x24, hit' (controls 26x24, svg 16x16, row ✕ 24x24). Red with the min sizes removed: 'head: 12 readings, 12 failed' (#more-button 26x18, row ✕ 22.2x18). Empty scan: 'NO - found no icon control, so this checked nothing'. --base origin/main: only the polish readings moved (small -> at least 24x24, hit); room 1440 bands list top 145px on both; room 1000 close disjoint served/export disjoint (✕ 26x24).
<!-- SECTION:NOTES:END -->
