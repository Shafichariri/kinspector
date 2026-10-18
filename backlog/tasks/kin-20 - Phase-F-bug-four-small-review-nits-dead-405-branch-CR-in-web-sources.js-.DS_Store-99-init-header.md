---
id: KIN-20
title: >-
  Phase F bug: four small review nits (dead 405 branch, CR in web-sources.js,
  .DS_Store, 99-init header)
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
updated_date: '2026-10-18 15:05'
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Engineer, commit e065f11. N1: the 'if ("js/00-core.js" in WebPage.scripts()) 405 else 404' line and its comment deleted; the loop over WebPage.scripts()+styles() already asserts 405 on js/00-core.js, which scripts.txt lists. N2: web-sources.js splits on /\r\n|\n|\r/, where Kotlin lines() splits. Before: styles.txt converted to CRLF -> exit 2 'harness: Error: ENOENT: no such file or directory, open .../web/css/00-tokens.css' (the entry carried a \r). After: same CRLF manifest -> '25 checks, 0 failed', exit 0; LF restored. N3: WebManifestTest skips '.DS_Store' by exact name in the folder walk and the web-root listing. Before (css/.DS_Store and web/.DS_Store, --rerun): 'the_folder_holds_exactly_what_the_manifest_lists() FAILED ... web/css/.DS_Store is in the folder but web/styles.txt does not list it' and 'the_web_root_holds_only_the_shell_the_manifests_and_their_folders() FAILED'. After, same files, --rerun: exit 0. Other dotfiles still fail: css/.foo -> 'web/css/.foo is in the folder but web/styles.txt does not list it'; web/.foo -> '(e) web/ holds something other than the shell...'. NOTE for QA: Gradle's default excludes keep .DS_Store out of the test task's input fingerprint, so adding one leaves :inspector-daemon:test UP-TO-DATE - use --rerun to see the test actually read it (it is also absent from build/resources). N4: js/99-init.js header now reads 'the only file that runs anything at load, beside other files' constants', matching web-ui.md's qualified sentence.
<!-- SECTION:NOTES:END -->
