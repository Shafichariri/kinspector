---
id: KIN-161
title: >-
  MCP: get_body says a discarded response was not read, never that it had no
  body
status: To Do
assignee: []
created_date: '2026-10-30 13:35'
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
- [ ] #1 MCP get_body on a row with resBodyOmitted "discarded" and resBytes 0 says the body was not read because the client discarded the attempt, and that this does not mean it was empty; it never says "had no body"
- [ ] #2 A row with no omission and 0 bytes still answers "This res had no body."
- [ ] #3 TransactionTools.explainMissingBody reads the omission before the byte count
- [ ] #4 A TransactionToolsTest case pins both rows, shown red with the bytes == 0 check put back first
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->
