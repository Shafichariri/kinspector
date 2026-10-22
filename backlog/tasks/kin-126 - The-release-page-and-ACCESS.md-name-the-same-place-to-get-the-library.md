---
id: KIN-126
title: The release page and ACCESS.md name the same place to get the library
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 10:51'
updated_date: '2026-10-22 10:54'
labels:
  - 'item:release-notes-boilerplate'
dependencies: []
type: docs
ordinal: 126000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
ROADMAP §6 → Release 1.4.0 → 'The release page and ACCESS.md name the same place to get the library' (order row 13d, lane S). Surface: the notes .github/workflows/release.yml writes on the release page. No public API, no workflow behaviour change.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 The release page's library section names Maven Central first, with mavenCentral() and no token, and GitHub Packages as the alternative that needs one, in the same order and words as docs/ACCESS.md.
- [ ] #2 It does not claim the version is on Central when the page is created: the central job only stages the bundle and a person publishes it (docs/decisions/release.md).
- [ ] #3 No other sentence in the boilerplate contradicts docs/ACCESS.md, and the change lists each one checked.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Chose wording over reordering: the release page now says the version reaches Maven Central once the maintainer publishes the staged bundle. No job, order or trigger in release.yml changed; only the text of the notes heredoc. Sentences checked against docs/ACCESS.md: the both-halves opener (now says the library is published to GitHub Packages and staged on Central, rather than 'went to GitHub Packages', which the daemon job cannot know when it creates the page); the INTEGRATION.md section 14 pointer; the daemon section (JDK 21 only, no account, gh release download, binds 127.0.0.1); the library section (Central first with mavenCentral() and no token, GitHub Packages second with a classic read:packages token, pointing at ACCESS.md's 'The older path').
<!-- SECTION:NOTES:END -->
