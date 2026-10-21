---
id: KIN-62
title: >-
  Phase I: the ui pair's golden file and completeness check, with the theme
  types internal
status: Done
assignee: []
created_date: '2026-10-21 04:16'
updated_date: '2026-10-21 10:50'
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
- [x] #1 api/inspector-ui-public-api.txt is asserted for equality by UiApiParityTest in both :inspector-ui and :inspector-noop-ui, holds exactly the two InspectorOverlayKt lines, and was read by a person
- [x] #2 InspectorOverlayKt is recorded as the JVM facade in both; renaming either InspectorOverlay.kt fails that module's test (R9, R10)
- [x] #3 JVM descriptors are compared; Compose's parameters stay in the jvm line and the fun line is the declaration, by the parameter-count rule with no name list (R6, R7, R8)
- [x] #4 Adding a public declaration to one module fails that module, in the contract facade (R1, R2) and in any other file (R3, R4)
- [x] #5 InspectorTheme, InspectorColors and LocalInspectorColors are internal; making any public again fails :inspector-ui:jvmTest (R5); a contract class holding nothing fails (R13)
- [x] #6 api/inspector-public-api.txt and api/inspector-stream-public-api.txt are byte-identical after regeneration (R12)
- [x] #7 -Pinspector=off still builds (CI release-swap green); check-release-clean.sh --self-test finds the canary and the plain run finds nothing
- [x] #8 INTEGRATION.md v59: version line with the commit date, v58 heading loses (this document), v59 entry says the three types are internal and to copy the colours (folded from T2)
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #106, merge 1479c30, base 4dc21d2. AC1: UiApiParityTest.xml in both modules' build/test-results/jvmTest, tests=2 failures=0 each; ui 136 (134+2), noop-ui 2; golden file is the header plus exactly two lines; R11: golden-only edit without --rerun fails both modules 'Missing from this module (1)'. AC2: R9/R10 (rename either InspectorOverlay.kt) fail both tests, 'Contract class dev.inspector.ui.InspectorOverlayKt is not on this module's classpath'. AC3: javap -public -s gives (ZLkotlin/jvm/functions/Function2;Landroidx/compose/runtime/Composer;II)V in both; rule is parameterCount > Kotlin parameters, no names; R6 and R7 fail on the right line; R8 not repeated by QA (builder's record). AC4: R1/R2 public fun fails its own module only; R3/R4 caught by the completeness test. AC5/AC6: three theme types internal; R5 public again fails; R13 empty contract class fails; after real regeneration core and stream golden files byte-identical (core 73, noop 3, stream 3, noop-stream 1 pass). AC7: CI release-swap green; locally -Pinspector=off :sample:desktop:build exit 0, check-release-clean self-test 'SELF-TEST PASSED: canary detected in 1 core artifact(s)', plain run 'PASSED: no capture code found in 1 artifact(s)' (JVM artifacts only; klib half is CI's). AC8: INTEGRATION.md v59 claims checked against tags v0.1.0..v1.3.0 and the code; no tag or publish yet. Look unchanged: 12/12 screenshots byte-identical. repo-checks green; root AGENTS.md 14,843 of 15,000 bytes. NOT run locally (no Android SDK, no working xcrun): full ./gradlew build, all Android targets, iOS links; they rest on CI (build, release-swap, repo, web green on the PR; main push run on 1479c30 still in progress when QA wrote). TRAP, non-blocking: the R12 proof command quoted in the design note and hand-over, ':inspector-core:jvmTest :inspector-stream:jvmTest -Dinspector.api.regenerate=true --rerun', applies --rerun to stream only; core's golden file was not rewritten. Re-run as ':inspector-core:jvmTest --rerun :inspector-stream:jvmTest --rerun -Dinspector.api.regenerate=true' both were rewritten and the diff stayed empty; the result stands. The command is not quoted in the repository. Known limit (design Risk 1): the guard is JVM-only, a public declaration only in iosMain/androidMain is unguarded. .claude/agents/architect.md line 31 still says noop-ui has no golden file.
<!-- SECTION:FINAL_SUMMARY:END -->
