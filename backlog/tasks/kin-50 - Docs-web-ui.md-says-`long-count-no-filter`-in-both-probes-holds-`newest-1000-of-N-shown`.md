---
id: KIN-50
title: >-
  Docs: web-ui.md says `long count no filter` in both probes holds `newest 1000
  of N shown`
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-19 11:27'
updated_date: '2026-10-19 11:32'
labels:
  - 'item:web-transactions-paged'
dependencies:
  - KIN-48
type: bug
ordinal: 50000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by the round-2 verify panel at 7a56668; doug, charles-2 and simon agree. In docs/decisions/web-ui.md "keeps an unfiltered copy" paragraph, "`long count no filter` in both probes hold it" is false, because the archive probe line now expects "1203 of 1203 calls" (KIN-48). It fails KIN-45 AC#6. Two comment fixes ride along and do not block.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 In docs/decisions/web-ui.md, everything from "The list draws the newest 1000 calls by the page's own limit" through "`long count no filter` in both probes hold it." is replaced by: "The list draws the newest 1000 calls by the page's own limit (`LIST_CEILING`), not by the route's clamp happening to match what it asked, and with no filter and no brushed stretch the header then says `newest 1000 of 4210 shown`, because `1000 of 4210 calls` is the shape a filter produces and sent readers looking for one (`no-filter count` in `check-web-static.js`, `long count no filter` in the live probe). An export asks for every embedded call instead, and says `4210 of 4210 calls`: it has no filter to reach past a ceiling, and at 1000 its oldest calls could not be opened at all, while a brushed stretch over them said they did not exist (`long count no filter`, `long stretch oldest` and `long offset 1000 sliced` in the archive probe)." Wrapped to the file's line width, no ragged short line.
- [x] #2 In scripts/render-web-ui-archive.js, the three comment lines above `const oldest` become: "// The pager's second question, the only one with a middle offset: the lines above all pass / // against a route that slices to `limit` rather than to `offset + limit`, and this one does not. / // A route that ignores `offset` already fails `transactions offset past end`."
- [x] #3 In inspector-daemon/src/main/resources/web/css/05-base.css, the #counts comment becomes: "/* The count must not wrap. The bar's 44px clips a wrapped count at the top and the bottom, and a / wrapped "newest 1000 of 1203 shown" read as "1000 of 1203", the shape only a filter produces. */"
- [x] #4 Every check named in the new web-ui.md text exists in the file it names and passes. DecisionsTest passes, :repo-checks:test passes, and check-web-static.js stays 79/0.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
Commit 68315b4. Verified by doug (QA) at 68315b4, panel close: yes (architect and UX yes): static 79/0; :inspector-daemon:test 268/0; :repo-checks:test 35/0 (DecisionsTest 5/0); live probe 185/0; archive probe 37/0. Not verified: CI (branch not pushed). Text of ACs 1-3 verified by script; every check named in the new web-ui.md text exists and passes; static 79/0, DecisionsTest 5/0.
<!-- SECTION:FINAL_SUMMARY:END -->
