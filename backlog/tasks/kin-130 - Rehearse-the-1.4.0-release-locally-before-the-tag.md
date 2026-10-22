---
id: KIN-130
title: Rehearse the 1.4.0 release locally before the tag
status: To Do
assignee: []
created_date: '2026-10-22 13:28'
labels:
  - 'item:release-rehearsal'
dependencies: []
ordinal: 130000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
ROADMAP §6 → Release 1.4.0 → 'Rehearse the release locally before the tag' (order row 13e, lane S). Run it once findings and detection are merged, and before 13c's last commit. This is the first release since the gradle.lockfiles; release.md records three POM faults that no build failed on. Out of scope: signing with the real key, any upload to Central or GitHub Packages, and workflow changes unless a fault needs one.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Publish to a local repository at the release version (e.g. -Pinspector.version=1.4.0-rc) and read a generated POM from each of the seven published modules: name, coordinates and dependency versions match the locks, and no dependency is missing or added.
- [ ] #2 The daemon zip is built and passes the same smoke test release.yml runs: unzip, serve, /api/sessions and /, and MCP serverInfo.version.
- [ ] #3 On the rehearsal's artifacts, check-release-clean.sh --self-test finds the canary and the real check finds nothing.
- [ ] #4 The delivery tracker records the results in docs/STATUS.md with the commit they ran on; a fault found becomes an item or a fix before the tag.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
