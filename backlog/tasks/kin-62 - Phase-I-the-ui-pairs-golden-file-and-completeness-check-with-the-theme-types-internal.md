---
id: KIN-62
title: >-
  Phase I: the ui pair's golden file and completeness check, with the theme
  types internal
status: In Progress
assignee: []
created_date: '2026-10-21 04:16'
updated_date: '2026-10-21 04:16'
labels:
  - 'item:structure-phase-i'
dependencies: []
ordinal: 62000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 9. Design: the architect's note for item:structure-phase-i (run of 2026-10-20). T2 (INTEGRATION.md v59) folded in, one PR.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 api/inspector-ui-public-api.txt is asserted for equality by UiApiParityTest in both :inspector-ui and :inspector-noop-ui, holds exactly the two InspectorOverlayKt lines, and was read by a person
- [ ] #2 InspectorOverlayKt is recorded as the JVM facade in both; renaming either InspectorOverlay.kt fails that module's test (R9, R10)
- [ ] #3 JVM descriptors are compared; Compose's parameters stay in the jvm line and the fun line is the declaration, by the parameter-count rule with no name list (R6, R7, R8)
- [ ] #4 Adding a public declaration to one module fails that module, in the contract facade (R1, R2) and in any other file (R3, R4)
- [ ] #5 InspectorTheme, InspectorColors and LocalInspectorColors are internal; making any public again fails :inspector-ui:jvmTest (R5); a contract class holding nothing fails (R13)
- [ ] #6 api/inspector-public-api.txt and api/inspector-stream-public-api.txt are byte-identical after regeneration (R12)
- [ ] #7 -Pinspector=off still builds (CI release-swap green); check-release-clean.sh --self-test finds the canary and the plain run finds nothing
- [ ] #8 INTEGRATION.md v59: version line with the commit date, v58 heading loses (this document), v59 entry says the three types are internal and to copy the colours (folded from T2)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
