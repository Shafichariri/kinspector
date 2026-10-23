---
id: KIN-149
title: >-
  Guard: a file in an uncompiled src/<name> of a published module fails
  :repo-checks
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-23 07:59'
updated_date: '2026-10-23 08:26'
labels:
  - 'item:guard-ios-main-compiled'
dependencies: []
ordinal: 149000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 39. Placed 2026-10-23 by the product owner (PR #187). Acceptance criteria are the roadmap item's own, from docs/ROADMAP.md under the heading carrying this ticket's item label. QA verifies on main after merge. Out of scope: turning the default hierarchy back on.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 A :repo-checks test fails when a published module has a src/<name> directory that is not one of its source sets, naming the directory and where iOS-only code goes
- [ ] #2 Proven red with a file in inspector-core/src/iosMain
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
bob: PR #191. SourceSetDirsTest in :repo-checks, fed by a provider in repo-checks/build.gradle.kts (-Dinspector.sourceSets: each published module's dir and Kotlin source set names). Fails on a src/<name> git lists that is not a source set, naming it and saying iOS-only code goes in iosArm64Main and iosSimulatorArm64Main. Red: a .kt in inspector-core/src/iosMain and a README in inspector-noop/src/iosMain each exit 1; an empty dir passes. Check: ./gradlew :repo-checks:test --tests '*SourceSetDirsTest*'. Ready for QA.
<!-- SECTION:NOTES:END -->
