---
id: KIN-127
title: 'Shared JSON key paths: JsonPaths.walk, group and isAtOrUnder'
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 11:32'
updated_date: '2026-10-22 13:48'
labels:
  - 'item:sensitive-data-detection'
dependencies:
  - KIN-112
type: feature
ordinal: 127000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 30, docs/ROADMAP.md §6 → Sensitive-data detection. Carved out of item:session-diff's T1 on 2026-10-22 by the coordinator (option a), because session diff now ships after 1.4.0 and detection cannot wait for it; labelled for the item that needs it first, as KIN-112 was for findings. Built to the names and signatures of the session-diff design §10, which are a contract: walk(element, maxDepth = 32) { path, element -> } (returns false when anything deeper was left out), group(path) and isAtOrUnder(path, prefixes), in service/BodyShape.kt beside redactedPaths. Diff T1 shrinks by these three and still adds foldIdKeys, SessionStamp and the stats and Markdown extractions; it renames nothing built here. Daemon service/ only; no public API, no schema.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 JsonPaths.walk visits every element with its concrete path in the Redactor's syntax ($, $.key, $.items[2]), so a walked path and a redacted[] entry compare as strings; elements deeper than maxDepth (32) are not visited and walk returns false
- [x] #2 JsonPaths.group writes every array index as [*]
- [x] #3 JsonPaths.isAtOrUnder holds a path and its children ($.card.number under $.card, $.items[3].token.x under $.items[3].token) and never a sibling sharing a prefix ($.cardholder is not under $.card)
- [x] #4 BodyShapeTest shown red with [] emitted for [N], with a bare startsWith, and with the depth bound off by one
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once its PR merges. service/BodyShape.kt: walk, group, isAtOrUnder (+ MAX_DEPTH); BodyShapeTest (+4 cases). Red proofs (each run, seen failing, restored): walk emitting $path[] for every index -> 'walk visits every element with its concrete path…' (the path list differs); isAtOrUnder as a bare startsWith -> 'isAtOrUnder holds a path and its children, never a sibling…' (Expected value to be false, $.cardholder); depth check off by one (maxDepth + 1) -> 'walk stops below its depth and says so' (33 levels is not). — bob

Closed 2026-10-22 by the delivery tracker on QA's report qa/sensitive-data-detection-report.md (verdict: close yes, no blocking findings), PR #161, merge 5cce7ab. Not verified by QA: the depth bound off by one (the third red proof) is the builder's, not re-run.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA read walk, group and isAtOrUnder against session-diff section 10 (names and signatures, walk returns Boolean); red with [] for [N] in group fails BodyShapeTest and SensitiveSourceTest; red with a bare startsWith in isAtOrUnder fails both too ($.cardholder is not under $.card).
<!-- SECTION:FINAL_SUMMARY:END -->
