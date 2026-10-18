---
id: KIN-16
title: >-
  Phase F bug: nothing checks the cross-file 1199px @media override order,
  though web-ui.md says static check 4 pins it
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
updated_date: '2026-10-18 15:22'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-13
type: bug
ordinal: 16000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (architect S1, confirmed by QA). docs/decisions/web-ui.md says the cross-file 1199px @media block's load order is 'Pinned by static check 4 ... and by the concatenation proof'. Neither checks order, and the proof is uncommitted. Repro (QA): rename css/35-list.css to css/08-list.css and reorder styles.txt. Static check stays 24/0, :inspector-daemon:test, :inspector-ui:jvmTest, live probe 164 and archive probe 21 all stay green, while headless Chrome shows style and PNG changes in all 12 configs under 1200px.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A zero-dependency static check in scripts/lib/web-audits.js, printed in the '--- scripts ---' section, fails when, for a selector inside an @media block, a plain (non-@media) rule with the same selector loads later in styles.txt order
- [ ] #2 Proven red by renaming css/35-list.css to css/08-list.css with styles.txt reordered, and green again once restored
- [ ] #3 The first 24 lines of check-web-static.js output stay byte-identical
- [ ] #4 docs/decisions/web-ui.md 'Pinned by' names the new check and no longer cites the uncommitted concatenation proof
- [ ] #5 A comment above #panes in css/10-panes.css points at the override in css/35-list.css
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Engineer, commit e046990. New static check 5 'media overrides load last' (auditMediaOverrideOrder in scripts/lib/web-audits.js), printed last in '--- scripts ---'. Fails when a selector inside @media has a plain rule with the SAME selector and an OVERLAPPING property (equal, or shorthand/longhand by name prefix) loading later in styles.txt order. Property overlap is required, deliberately narrower than AC#1's wording: selector-only matching fails today's stylesheet (':root in @media (prefers-color-scheme: light) at css/00-tokens.css:53 loads before css/40-detail.css:111' - that plain :root sets --stop-bg, which the light block does not, so there is no override). Green: 'media overrides load last : yes (15 @media selector(s) in 17 file(s))', '25 checks, 0 failed'. RED (mv css/35-list.css css/08-list.css, styles.txt lists it after 05-base.css), exit 1: 'media overrides load last : NO - an @media rule is overridden by a plain rule loaded after it: #panes in @media (max-width: 1199px) at css/08-list.css:26 loads before css/10-panes.css:4; #panes.panes-wide in @media (max-width: 1199px) at css/08-list.css:26 loads before css/10-panes.css:16; #panes.panes-wide #detail-pane in @media (max-width: 1199px) at css/08-list.css:46 loads before css/10-panes.css:17' / '25 checks, 1 failed'. Restored: '25 checks, 0 failed'. First 24 lines byte-identical to a5a8661 (diff of head -24 empty; baseline in $TMPDIR/kin16/static-before.txt). web-ui.md 'Pinned by' now names check 5 and no longer cites the concatenation proof. Comment added above #panes in css/10-panes.css. Limits stated in the audit's doc: different selectors of equal specificity are not compared, nor shorthands whose longhands do not share the prefix (inset/top).

Maintainer, 2026-10-18 (via the conductor): AC#1 is accepted as narrowed — a later plain rule counts only when it has the same selector AND sets an overlapping property. Selector-only matching fails today's correct CSS (:root light tokens in 00-tokens.css:53 vs a plain :root in 40-detail.css:111 setting a different token). Both verify-panel members recommended accepting.
<!-- SECTION:NOTES:END -->
