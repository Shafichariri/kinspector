---
id: KIN-171
title: >-
  MCP missing-body answer: pin streaming/contentType at 0 bytes, and name
  unknown omission reasons
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-30 13:50'
updated_date: '2026-10-30 13:57'
labels:
  - 'item:mcp-discarded-body-text'
dependencies: []
type: bug
ordinal: 171000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found by QA on KIN-161 (all its criteria Verified). The roadmap item (docs/ROADMAP.md, ~line 589) asks that every recorded reason is checked before the byte count, so a streaming or contentType row at 0 does not read as 'no body', with a test for each proven red against the old order. Two gaps remain. (1) explainMissingBody (inspector-daemon/.../mcp/TransactionTools.kt) handles streaming and contentType at 0 bytes correctly now, but no test pins either. (2) A reason this build does not know (resBodyOmitted:"someFutureReason", 0 bytes) still answers 'This res had no body.'. BodyOmission is an open string set, and the Markdown export answers 'not captured (`someFutureReason`)' for it. KIN-165 builds on this order, so land this first or alongside it.

Notes (not criteria):
(a) The MCP DISCARDED wording 'to make the next one' claims more than capture knows: teeBody also records DISCARDED when the app cancels its own call. The Markdown export's 'discarded this attempt before reading its body' holds in every case. Suggest aligning the MCP text to that.
(b) A content-type row with no declared type reads 'content type was not declared is outside the capture allowlist', which is ungrammatical. Pre-existing; KIN-165 inherits it.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 A test for a streaming row at 0 bytes and a test for a contentType row at 0 bytes; each asserts the answer is never 'had no body', and each is shown red against the old order (bytes == 0L checked first)
- [x] #2 Any non-null *BodyOmitted that is not a known constant answers with the reason named, consistent with the Markdown export's 'not captured (`<reason>`)', never 'had no body'; tested, and shown red
- [x] #3 A no-reason 0-byte row still answers 'This res had no body.'
- [x] #4 ./gradlew :inspector-daemon:test :repo-checks:test is green
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Plan (bob). Files: inspector-daemon/src/main/kotlin/dev/inspector/daemon/mcp/TransactionTools.kt (explainMissingBody: an unknown non-null omission answers 'The <side> body was not captured (`<reason>`). <n> bytes were recorded.', placed after the known reasons and before bytes == 0); inspector-daemon/src/test/kotlin/dev/inspector/daemon/mcp/TransactionToolsTest.kt (one test writing a session with streaming/0, contentType/0, someFutureReason/0 and a plain 0 row). Red: (AC1) bytes == 0L put back first, streaming and contentType asserts fail on 'had no body'; (AC2) the unknown-reason assert runs red against the current code before the branch is added. Notes (a)/(b) left alone as instructed; DISCARDED wording unchanged.

Built (bob). TransactionTools.explainMissingBody: new branch after the known reasons and before bytes == 0, answering 'The <side> body was not captured (`<reason>`). <n> bytes were recorded.' DISCARDED wording and notes (a)/(b) untouched. Tests in TransactionToolsTest: get_body_does_not_call_a_streamed_body_at_zero_bytes_empty, get_body_does_not_call_an_allowlist_omission_at_zero_bytes_empty, get_body_names_an_omission_reason_this_build_does_not_know; AC3 stays pinned by get_body_reads_the_recorded_omission_before_the_byte_count (assertEquals 'This res had no body.').
Red 1 (written first, current code): 21 tests, 1 failed, unknown-reason: CharSequence <This res had no body.>, substring <not captured (`someFutureReason`)>.
Red 2 (bytes == 0L put back first): 21 tests, 4 failed; streaming: <This res had no body.> lacks <streamed>; contentType: lacks <capture allowlist>; unknown and discarded likewise.
Red 3 (only the unknown-reason line removed): 21 tests, 1 failed, the unknown-reason test.
Restored, green: 21/0. ./gradlew :inspector-daemon:test :repo-checks:test --rerun: BUILD SUCCESSFUL, daemon 620 tests 0 failed, repo-checks 39 tests 0 failed. Ready for QA.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA, live over real MCP. AC1: streaming at 0 -> '...was streamed and never held in memory...'; contentType at 0 -> '...outside the capture allowlist. 0 bytes were sent...'; red: 4 failed against main's order. AC2: someFutureReason at 0 -> 'The res body was not captured (`someFutureReason`). 0 bytes were recorded.'; red: 1 failed with the branch removed, 1 with the reason text dropped, 3 with unknown before known. AC3: no reason at 0 -> 'This res had no body.' AC4: :inspector-daemon:test 620 passed, 0 failed; :repo-checks:test 39 passed, 0 failed. Unchanged rows read identically. Intended change: an unknown reason with bytes > 0 now names the reason. Notes (a) and (b) stay open: (a) for the product owner, (b) inherited by KIN-165. Client-name scan not run by QA; the conductor runs it before commit.
<!-- SECTION:FINAL_SUMMARY:END -->
