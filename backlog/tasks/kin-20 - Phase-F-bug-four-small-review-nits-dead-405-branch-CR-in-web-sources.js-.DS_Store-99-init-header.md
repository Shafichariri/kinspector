---
id: KIN-20
title: >-
  Phase F bug: four small review nits (dead 405 branch, CR in web-sources.js,
  .DS_Store, 99-init header)
status: To Do
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
labels:
  - 'item:structure-phase-f'
dependencies:
  - KIN-10
type: bug
ordinal: 20000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the verify panel (N1-N4). N1: RestApiTest.kt ~228-233 has a dead 'if ("js/00-core.js" in WebPage.scripts()) 405 else 404' branch. N2: web-sources.js splits manifests on '\n' but Kotlin lines() also strips '\r', so the comment at :7 is untrue for CRLF manifests. N3: WebManifestTest lists dotfiles, so a Finder .DS_Store turns the build red on a Mac. N4: js/99-init.js:1 header claims 'the only code that runs at load' while 05-network.js builds source and 00-core.js builds state/prefs at load.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 N1: the dead branch is deleted and the test still asserts the intended status
- [ ] #2 N2: web-sources.js strips '\r' as well as '\n' when splitting manifests, so the comment at :7 is true
- [ ] #3 N3: WebManifestTest ignores .DS_Store by name only; other dotfiles still fail it
- [ ] #4 N4: the js/99-init.js header is reworded to match web-ui.md's qualified sentence
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
