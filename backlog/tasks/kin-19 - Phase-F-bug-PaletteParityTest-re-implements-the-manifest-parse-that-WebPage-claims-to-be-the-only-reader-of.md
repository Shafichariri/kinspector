---
id: KIN-19
title: >-
  Phase F bug: PaletteParityTest re-implements the manifest parse that WebPage
  claims to be the only reader of
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-18 14:52'
updated_date: '2026-10-18 15:05'
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

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Engineer, commit c4f34bb. PaletteParityTest reads every *.css in web/css/ via -Dinspector.webCss (WebCssArgument in inspector-ui/build.gradle.kts, the folder declared @InputDirectory, RELATIVE); the manifest parse copy and the styles.txt input are gone. WebPage.entries is now the only Kotlin manifest parser (WebManifestTest calls it), so WebPage's KDoc is true unchanged. shared-rules.md palette paragraph updated. Proofs, no --rerun: (1) --m-get in 00-tokens.css dark block set to '1 2 3' -> ':inspector-ui:jvmTest FAILED', 'the dark palette matches the web tokens[jvm] FAILED', 'java.lang.AssertionError: dark --m-get differs between the web and the overlay'; restored. (2) '.kin19 { --m-get: 154 161 172; }' appended to 35-list.css -> 'no checked token is declared outside the tokens file[jvm] FAILED', 'declared outside css/00-tokens.css, where this test cannot see it win the cascade: --m-get in css/35-list.css'; restored -> BUILD SUCCESSFUL, tests=3 failures=0 (FROM-CACHE: same inputs as the green run before the breaks).
<!-- SECTION:NOTES:END -->
