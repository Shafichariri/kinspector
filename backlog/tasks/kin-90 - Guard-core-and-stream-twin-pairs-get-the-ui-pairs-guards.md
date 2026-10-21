---
id: KIN-90
title: 'Guard: core and stream twin pairs get the ui pair''s guards'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-21 18:15'
updated_date: '2026-10-21 22:28'
labels:
  - 'item:guard-twin-pairs-alike'
dependencies: []
ordinal: 90000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Batch 1 of the order decided 2026-10-21. Acceptance criteria are the roadmap item's own, in docs/ROADMAP.md under the heading carrying this ticket's item label; the engineer copies them into its plan in this ticket's notes before building. Built without QA before merge; QA verifies on main after merge.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every acceptance criterion of the roadmap item labelled item:guard-twin-pairs-alike in docs/ROADMAP.md holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob, branch shafic/guard-twin-pairs-alike from b297b00).

Acceptance criteria (copied from docs/ROADMAP.md, item:guard-twin-pairs-alike):
1. The core and stream pairs fail on a public declaration outside their contract, the way the ui pair does. For stream, the contract is the one with Ktor-typed members filtered out.
2. Each module of the core and stream pairs declares its golden file as a jvmTest input, with inputs.files. An edit to the golden file alone re-runs the test and fails it, with the build cache on.
3. structure/twins.txt names each pair's golden file, and :repo-checks fails a pair whose file is missing or unnamed.
4. Each guard is proven red: a stray public class in each module, a golden-only edit, a pair line without its file.

Files:
- api-parity/core/src/dev/inspector/ApiParityTest.kt, api-parity/stream/src/dev/inspector/stream/StreamApiParityTest.kt: add every_public_declaration_is_in_the_contract using ApiSurface.publicHolders, as UiApiParityTest does. Possibly a shared helper in api-parity/shared ApiSurface.kt so three tests do not carry three copies of the failure text.
- inspector-core, inspector-noop, inspector-stream, inspector-noop-stream build.gradle.kts: tasks.named<Test>("jvmTest") { inputs.files(golden).withPathSensitivity(RELATIVE) }.
- structure/twins.txt: pair lines gain a fourth field, the golden file. repo-checks NoopTwinTest: a pair without a golden, or one naming a file that does not exist, fails.
- Docs: docs/decisions/twins-and-api.md (the 'not yet applied to core and stream' sentence), api-parity/AGENTS.md, repo-checks guide if needed.

Stop rule: measure publicHolders on all four modules BEFORE writing the contract comparison. If core or stream has a public declaration today outside its contract, stop and report it to the maintainer; no internal-ising, no golden edits. No golden file changes in this item.

Red proofs planned: R1 stray public class in each of the 4 modules; R2 golden-only edit of each golden, run without --rerun, build cache on; R3 drop the golden name from a pair line in twins.txt; R4 name a missing file.

Built (bob). Measured before writing the comparison: publicHolders equals the contract list in all four modules (core and noop: the 9 CONTRACT_CLASSES; stream and noop-stream: the 4 STREAM_CONTRACT_CLASSES; the real stream's Ktor-typed defaultStreamClient sits on the contract facade StreamSinkKt). No STOP condition. No golden file changed.

Red proofs, each restored and re-run green (JAVA_HOME per RUN-RULES):
- R1 stray public declaration, one per module: class StrayProbe in inspector-core and inspector-stream, top-level fun strayProbe() in inspector-noop and inspector-noop-stream. Each module: every_public_declaration_is_in_the_contract FAILED with '+ dev.inspector.StrayProbe [class]' / '+ dev.inspector.StrayProbeKt [strayProbe]' (stream: dev.inspector.stream...), while public_api_matches_the_golden_surface stayed green in all four.
- R1b Ktor-typed stray in the real stream module (fun strayClient(): HttpClient in a new file): '+ dev.inspector.stream.StrayProbeKt [strayClient]'.
- R2 golden-only edit (a line appended to api/inspector-public-api.txt and api/inspector-stream-public-api.txt), --build-cache, no --rerun, after a green FROM-CACHE baseline: all four jvmTest FAILED, 'Missing from this module (1)'. Reverse: inputs.files removed from inspector-noop and inspector-stream, same edit: those two jvmTest UP-TO-DATE and green, the other two FAILED.
- R3 twins.txt stream pair line without its file: NoopTwinTest.every_pair_names_a_golden_file_that_exists FAILED, 'These pairs name no golden file ... :inspector-stream :inspector-noop-stream'. (Also seen by accident: main's own twins.txt fails all three pairs.)
- R4 ui pair naming api/inspector-ui-api.txt: 'These pairs name a golden file that does not exist'.

Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA (Doug), PR #125 merge 623c571, one round, no blocking findings. '--rerun' on the four jvmTest tasks: 2 parity tests green in :inspector-core, :inspector-noop, :inspector-stream, :inspector-noop-stream. Red: class StrayProbe in core -> completeness FAIL '+ dev.inspector.StrayProbe [class]' with the golden test green; top-level fun strayProbe() in :inspector-noop-stream -> FAIL '+ dev.inspector.stream.StrayProbeKt [strayProbe]'. Golden-only edit of both golden files, --build-cache --continue, no --rerun: all four jvmTest FAILED (PUBLIC API differs). twins.txt: misspelt -> 'These pairs name a golden file that does not exist'; core line without file -> 'These pairs name no golden file in structure/twins.txt'. 'git diff b1bbcac 623c571 -- api/' empty. check-release-clean.sh: self-test found the canary, plain run found nothing (JVM jars only locally). Not run locally: Android SDK/xcrun, rests on CI (build, release-swap passed). JVM-only guard; native surface is item:guard-twin-native-surface. The hand-over's claim that root AGENTS.md says the ui pair has no golden file was false at 623c571.
<!-- SECTION:FINAL_SUMMARY:END -->
