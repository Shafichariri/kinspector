---
id: KIN-19
title: >-
  Phase F bug: PaletteParityTest re-implements the manifest parse that WebPage
  claims to be the only reader of
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-13
type: bug
ordinal: 19000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (S4). PaletteParityTest.kt:34 parses the manifest itself while WebPage.kt:15 says WebPage is the only Kotlin reader of it, so the KDoc is false and there are two parsers.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 PaletteParityTest reads css/00-tokens.css and the files in the declared css/ input directory directly (WebManifestTest already guarantees folder == manifest), with no manifest parse copy remaining
- [ ] #2 WebPage's KDoc is true as written
- [ ] #3 The two existing red proofs (drift in tokens; --m-get in 35-list.css) re-run red with no --rerun, and the test is green restored
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
