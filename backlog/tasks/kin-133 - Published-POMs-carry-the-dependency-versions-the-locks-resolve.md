---
id: KIN-133
title: Published POMs carry the dependency versions the locks resolve
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 16:00'
updated_date: '2026-10-22 18:06'
labels:
  - 'item:pom-version-mapping'
dependencies: []
ordinal: 133000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Fault F1 from the 1.4.0 release rehearsal (KIN-130). kotlin-stdlib is 2.3.20 in 24 POMs (root, -jvm, -iosarm64 and -iossimulatorarm64 of inspector-core, -noop, -ui, -noop-ui, -stream and -noop-stream) while every one of those modules' gradle.lockfile resolves 2.3.21, raised by Ktor 3.5.0 through conflict resolution. The POM carries the version the Kotlin plugin declares; Maven-style consumers get 2.3.20 by nearest-wins under a Ktor built against 2.3.21. Source: the publications.withType<MavenPublication>().configureEach block in the root build.gradle.kts, which has no versionMapping. inspector-model is consistent (no Ktor; 2.3.20 in both). Design by the architect; release.md's rule applies: re-read the generated POM after any change to the POM block.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Every published POM's dependency versions match the module's gradle.lockfile.
- [x] #2 A guard checks the POMs against the locks and is proven red on a POM that disagrees with its lock.
- [x] #3 The -android POMs are checked where the Android SDK exists (CI, or a machine with the SDK).
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built from designs/pom-version-mapping.md (run-2026-10-20). Branch shafic/pom-version-mapping.

Fix: root build.gradle.kts maps each publication's versions from the configuration its lock covers (fromResolutionOf), and commonMainResolvableDependenciesMetadata for the root POM. A publication no lock covers fails configuration. Guard: repo-checks PublishedPomTest (2 tests) reads all 35 POMs (7 modules x 5 publications, android included, no SDK needed) and holds them to the lock text. :repo-checks:test depends on every GenerateMavenPom task. Lockfile parsing shared in Lockfiles.kt.

No gradle.lockfile, libs.versions.toml or Kotlin plugin version changed (git diff origin/main on them is empty).

Green: ./gradlew :repo-checks:test -> 38 tests, 0 failed (also with -Pinspector=off). Hand-read: inspector-core-jvm-1.4.0-rc.pom says kotlin-stdlib 2.3.21; its .module says "requires": "2.3.21".

Red proofs, each run for real against PublishedPomTest and restored:
R1 versionMapping line deleted -> every_published_pom_states_the_versions_its_lock_resolves FAILED, 30 lines, all "<module> <pub>: org.jetbrains.kotlin:kotlin-stdlib POM 2.3.20, lock 2.3.21", 6 of them android. First: "inspector-core kotlinMultiplatform: org.jetbrains.kotlin:kotlin-stdlib POM 2.3.20, lock 2.3.21".
R2 replaced with versionMapping { allVariants { fromResolutionResult() } } -> the same 30 lines, nothing else.
R3 inspector-core/gradle.lockfile stdlib hand-edited to 2.3.22 -> 5 lines: "inspector-core android|iosArm64|iosSimulatorArm64|jvm: org.jetbrains.kotlin:kotlin-stdlib POM 2.3.20, lock 2.3.22" and "inspector-core kotlinMultiplatform: org.jetbrains.kotlin:kotlin-stdlib POM 2.3.21, lock 2.3.22".
R4 dependsOn wiring removed, ./gradlew clean :repo-checks:test -> both PublishedPomTest tests FAILED, 35 lines "no POM at inspector-core/build/publications/android/pom-default.xml" etc. Restored + clean -> green, 35 generatePomFileFor tasks ran.
R5 macosArm64() added to inspector-noop -> configuration fails: ":inspector-noop publishes 'macosArm64', which no locked configuration covers. Add its configuration to `lockedConfigurations` and regenerate the locks." (Gradle attributes it to inspector-noop/build.gradle.kts line 190, the root block's configureEach.)

Not verified here: ./gradlew build and build -Pinspector=off (no Android SDK on this machine; CI build and release-swap are the full run and the AC #3 evidence with the SDK). PublishedPomTest on Linux: no CI job runs :repo-checks:test on Linux (repo job runs no Gradle; build and release-swap are macOS); Docker is not running here.

Closed 2026-10-22 by the delivery tracker on QA's report qa/pom-version-mapping-report.md (verdict: close yes, no blocking findings), PR #172, merge 9481339. Not verified by QA: PublishedPomTest on a Linux host (CI runs it only on macOS), and the builder's red proofs R2, R4 and R5 were not re-run.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA: :repo-checks:test --rerun 38 tests, 0 failed, PublishedPomTest's 2 included; kotlin-stdlib in inspector-core, inspector-core-jvm and inspector-stream-jvm is 2.3.20 at base and 2.3.21 (the lock's) at head, and the .module requires 2.3.21; red R1 (versionMapping line deleted) lists 30 mismatches, red R3 (lock hand-edited to 2.3.22) lists 5; the -android POMs are read by the test and PR #172's build and release-swap jobs (macOS, with the SDK) passed.
<!-- SECTION:FINAL_SUMMARY:END -->
