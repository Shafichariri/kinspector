---
id: KIN-126
title: The release page and ACCESS.md name the same place to get the library
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 10:51'
updated_date: '2026-10-22 13:48'
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
- [x] #1 The release page's library section names Maven Central first, with mavenCentral() and no token, and GitHub Packages as the alternative that needs one, in the same order and words as docs/ACCESS.md.
- [x] #2 It does not claim the version is on Central when the page is created: the central job only stages the bundle and a person publishes it (docs/decisions/release.md).
- [x] #3 No other sentence in the boilerplate contradicts docs/ACCESS.md, and the change lists each one checked.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Chose wording over reordering: the release page now says the version reaches Maven Central once the maintainer publishes the staged bundle. No job, order or trigger in release.yml changed; only the text of the notes heredoc. Sentences checked against docs/ACCESS.md: the both-halves opener (now says the library is published to GitHub Packages and staged on Central, rather than 'went to GitHub Packages', which the daemon job cannot know when it creates the page); the INTEGRATION.md section 14 pointer; the daemon section (JDK 21 only, no account, gh release download, binds 127.0.0.1); the library section (Central first with mavenCentral() and no token, GitHub Packages second with a classic read:packages token, pointing at ACCESS.md's 'The older path').

Closed 2026-10-22 by the delivery tracker on QA's report qa/public-claims-and-release-page-report.md (verdict: close yes, no blocking findings), PR #159, merge 82887c1. Nothing listed as not verified for this ticket.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA rendered the Publish the release step's notes at head and base with VERSION=1.4.0: Maven Central first with mavenCentral() and no token, GitHub Packages as the older path, pointing at ACCESS.md; the page says 1.4.0 reaches Central once the maintainer publishes it; both workflow files parsed, every key identical but that step's run text.
<!-- SECTION:FINAL_SUMMARY:END -->
