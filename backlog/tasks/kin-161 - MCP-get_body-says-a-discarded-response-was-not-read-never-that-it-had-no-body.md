---
id: KIN-161
title: >-
  MCP: get_body says a discarded response was not read, never that it had no
  body
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-30 13:35'
updated_date: '2026-10-30 13:57'
labels:
  - 'item:mcp-discarded-body-text'
dependencies: []
modified_files:
  - >-
    inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/TransactionTools.kt
  - >-
    inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/TransactionToolsTest.kt
type: bug
ordinal: 161000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
**Bug item:** `item:mcp-discarded-body-text`. Found during the `item:producer-react-native` design and reproduced live against a HEAD daemon.

**The defect.** `TransactionTools.explainMissingBody` (`inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/TransactionTools.kt:141-156`) checks `bytes == 0L -> "This $side had no body."` **before** it reads the omission. So MCP `get_body` on a row with `resBodyOmitted: "discarded"` and `resBytes: 0` answers:

> This res had no body.

That is the exact misreport `BodyOmission.DISCARDED` exists to prevent. `Transaction.kt:182-191`: "Keeping this distinct from 'the body was empty' matters, because the hop may well have carried one, and calling it empty would be exactly the kind of guess this field exists to replace."

**Other surfaces already get it right:**
- the web detail puts the recorded reason first (`web/js/35-detail.js:239-244`, "A recorded reason outranks the byte count");
- the Markdown export does too (`service/ExportMarkdown.kt:393-395`).

`docs/decisions/capture.md`: "**Never guess why a body is absent — read `reqBodyOmitted`/`resBodyOmitted`.**"

**Fix.** Read the omission before the byte count in `explainMissingBody`. The `discarded` text says the body was not read because the client discarded the attempt to make the next one, and that this does not mean it was empty.

**Why it is sequenced first.** `item:producer-react-native`'s `streaming`-at-0 surfaces ticket builds on this ordering in the same function.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 MCP get_body on a row with resBodyOmitted "discarded" and resBytes 0 says the body was not read because the client discarded the attempt, and that this does not mean it was empty; it never says "had no body"
- [x] #2 A row with no omission and 0 bytes still answers "This res had no body."
- [x] #3 TransactionTools.explainMissingBody reads the omission before the byte count
- [x] #4 A TransactionToolsTest case pins both rows, shown red with the bytes == 0 check put back first
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (Small track).
Files: inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/TransactionTools.kt (explainMissingBody: branch on the recorded omission first, byte count only when none is recorded, same shape as ExportMarkdown.absentBody); inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/TransactionToolsTest.kt (one new case).
Check: TransactionToolsTest case writing a session with a res row resBodyOmitted=discarded/resBytes=0 and a plain res row at 0 bytes; asserts the discarded text (not read, client discarded the attempt, does not mean empty, never 'had no body') and that the plain row still says 'This res had no body.'
Red: test written first and run against the current ordering (bytes == 0 first), then again after the fix with the bytes == 0 branch put back first; restored and green.
Scope: STREAMING/CONTENT_TYPE wording at 0 bytes is KIN-165's; this ticket only moves the omission ahead of the byte count and adds the DISCARDED text.

Built (bob). explainMissingBody now branches on the recorded omission (DISCARDED, CONTENT_TYPE, STREAMING) before bytes == 0. New test TransactionToolsTest.get_body_reads_the_recorded_omission_before_the_byte_count: red against the old code and again with bytes == 0 put back first (both: CharSequence <This res had no body.>, substring <not read>); green restored. Side effect for KIN-165: a STREAMING row at 0 bytes now reads 'The req body (0 bytes) was streamed…' rather than 'had no body'; KIN-165 owns that wording. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA, live over real MCP on a daemon built from a snapshot. AC1: base said 'This res had no body.' for resBodyOmitted discarded at 0 bytes; fixed says 'The res body was not read: the client discarded this attempt to make the next one, ... That does not mean it was empty.' AC2: a no-reason 0-byte row still says 'This res had no body.' AC3: explainMissingBody reads the omission before the byte count (diff). AC4: guard red three ways in the snapshot; :inspector-daemon:test 617 passed, 0 failed; :repo-checks:test 39 passed, 0 failed. DoD3: client-name scan not run by QA (no term list); the conductor runs it before commit. Note: wording 'to make the next one' may be false when the app cancels its own call (follow-up candidate for the product owner).
<!-- SECTION:FINAL_SUMMARY:END -->
