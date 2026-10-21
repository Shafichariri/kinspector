---
id: KIN-92
title: >-
  A server is its host and a non-default port, everywhere (IPv6 brackets, byHost
  by hostPort)
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-21 19:58'
updated_date: '2026-10-21 20:18'
labels:
  - 'item:host-port-everywhere'
dependencies: []
ordinal: 92000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 19 (§3 → Bugs found). Acceptance criteria are the roadmap item's own (docs/ROADMAP.md, heading carrying this label); the engineer copies them into the plan. One PR: the urlOf mirror case runs in Kotlin and the web checks. Architect: statsOrigin in web/js/95-stats.js is a second copy of the rule and is included.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Every acceptance criterion of the roadmap item labelled item:host-port-everywhere holds, each with a check shown red when its rule is broken
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
Acceptance criteria (copied from docs/ROADMAP.md, item:host-port-everywhere):
1. hostPort brackets a host that holds a ':', so the url of a call to ::1 on :8080 is http://[::1]:8080/v6 and its label reads GET [::1]:8080/v6. A host already in brackets is not bracketed twice.
2. Decided in :inspector-model and in the web UI's urlOf mirror, with a mirror case run by both sides, so an archive recorded before the fix reads the same way.
3. A real call to the IPv6 loopback, captured through Ktor and through OkHttp, gives that url. With no IPv6 loopback the test says it was skipped and why, not that it passed.
4. byHost keys on hostPort: 127.0.0.1:8080 and 127.0.0.1:9090 are two entries; a default port adds nothing to its key.
5. Proven red with each rule removed.

Copies of the host-and-port rule found:
- NetworkTransaction.hostPort / url (inspector-model Transaction.kt) - the authority. Changed: brackets a host holding ':' unless it already starts with '['.
- web urlOf (web/js/00-core.js, @mirror urlOf) - the mirror. Gains hostPortOf in the same block; urlOf builds on it.
- web statsOrigin (web/js/95-stats.js:139) - Stats page origin per route row. Rebuilt on hostPortOf, so no third web copy.
- ExportMarkdown.kt:110 - the Markdown export's route table host column, `row.port?.let { "${row.host}:$it" } ?: row.host`. Not named by the item or the architect; same defect as statsOrigin. Rendered through NetworkTransaction.hostPort (no new public API).
- SessionRepository.summarize byHost - groups by bare host. Changed to hostPort.
Read the same way, not copies: SessionRepository timeline label and ExportMarkdown call label (already txn.hostPort), HAR and Replayer (txn.url), overlay Copy URL / URL field / cURL (txn.url), web cURL, replay editor, copy-for-agent call line, detail URL field (urlOf).
Bare-host groupings left alone (not host+port joins, and they feed host: terms, whose grammar is frozen): web glance host rows (65-browser-detail.js:81), copy-for-agent host lines (40-copy-export.js:149), pathScope host (model and web), overlay row host (ListRows.kt:298), RouteTemplates filter term host:. The route key already keeps ports apart (out of scope).
CLI: inspector summary/stats print JSON, so they change only through byHost.

Files:
- inspector-model/.../Transaction.kt (hostPort), commonTest SerializationTest PortTest (bracket cases), jvmTest MirrorCasesTest (urlOf rule)
- mirror-cases/urlOf.json (new), mirror-cases/README.md (table + projection)
- web/js/00-core.js (urlOf block), web/js/95-stats.js (statsOrigin)
- scripts/lib/mirror-runner.js (urlOf rule)
- inspector-core jvmTest: Ipv6CaptureTest.kt (new) - Ktor CIO and OkHttp against a Ktor CIO server bound to ::1
- inspector-daemon: SessionRepository.kt (byHost), ExportMarkdown.kt (route host column), tests in the summary test and ExportTest
- docs: schema.md (hostPort), decisions/capture.md (port paragraph), decisions/shared-rules.md (rule count), UNRELEASED.md line

Mirror case mirror-cases/urlOf.json, projection { url, hostPort } of one transaction:
- IPv6 loopback, non-default port: ::1 http 8080 /v6 -> http://[::1]:8080/v6, [::1]:8080
- IPv6, default port: ::1 https 443 -> https://[::1]/v6, [::1]
- IPv6, port null (archive recorded before port existed) -> bracketed, no port
- already bracketed host [::1] 8080 -> http://[::1]:8080/v6 (not [[::1]])
- archive recorded before the fix: host ::1 as OkHttp stored it, read now -> same url as a Ktor capture
- default port dropped: 127.0.0.1 http 80 -> http://127.0.0.1/json, 127.0.0.1
- non-default kept: 127.0.0.1 http 9090 -> http://127.0.0.1:9090/json
- 443 over http kept; empty query keeps '?'

Tests:
- model commonTest PortTest: bracketing, no double bracket, default dropped for IPv6
- core jvmTest Ipv6CaptureTest: Ktor (CIO client + plugin) and OkHttp (interceptor) to http://[::1]:<port>/v6, assert url == http://[::1]:<port>/v6 and hostPort; Assume with a message when ::1 cannot be bound
- daemon: summary byHost test (8080, 9090, default port twice, IPv6) ; ExportTest route table IPv6 host cell
- web: urlOf cases via check-web-static; statsOrigin through hostPortOf

Red proofs planned:
R1 bracketing removed from hostPort -> model PortTest, MirrorCasesTest urlOf, core Ipv6CaptureTest (Ktor or OkHttp, whichever stores bare), daemon fail
R2 double bracketing (drop the startsWith('[') check) -> already-bracketed case fails both sides
R3 byHost back to it.host -> daemon summary test fails
R4 statsOrigin unbracketed (old line) -> a check that sees it (static check or render probe)
R5 mirror case on each side: break web hostPortOf -> check-web-static urlOf NO; break Kotlin -> MirrorCasesTest red
R6 Markdown route host column old line -> ExportTest fails
R7 IPv6 test skip path: force the probe to fail -> reported skipped with reason, not passed
Goldens: git diff --exit-code api/ and inspector-daemon/src/test/resources/mcp-tools-list.json must stay clean.
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built (bob), uncommitted on shafic/host-port-everywhere from b1bbcac. Ready for QA.

Found: Ktor 3.5.0's Url.host keeps the brackets ([::1]); OkHttp's HttpUrl.host drops them (::1). Before the fix the Ktor test already passed and the OkHttp one read http://::1:<port>/v6. So "not bracketed twice" is what keeps Ktor captures right, and host: filters still see [::1] vs ::1 by capture path (filter grammar out of scope; said in capture.md and the UNRELEASED line).

Changed: NetworkTransaction.hostPort brackets a host holding ':' unless it starts with '['; web urlOf block gains hostPortOf (urlOf builds on it); statsOrigin goes through hostPortOf; SessionRepository.summarize byHost keys on hostPort; ExportMarkdown routes table host column goes through hostPort (a copy neither the item nor the architect named; built via a NetworkTransaction so no new public model function). Root build.gradle.kts logs SKIPPED test events so a skip shows in CI's console (the reason is in the JUnit report only; Gradle does not print an assumption's message).

Tests: model PortTest +1, MirrorCasesTest.urlOf_cases +1 (mirror-cases/urlOf.json, 6 cases); core Ipv6CaptureTest +2 (Ktor CIO, OkHttp, against a Ktor CIO server bound to ::1; JUnit Assume with the reason when ::1 cannot be bound); daemon StorageTest summary byHost +1, ExportTest routes table +1; check-web-static: urlOf (6 cases) and stats origin, 107 -> 109 checks.

Red proofs (each restored, sha checked): R1 bracketing removed in Kotlin -> model 2, core 1 (OkHttp), daemon 2 fail. R2 double bracketing in Kotlin -> model 2, core 1 (Ktor), daemon 1. R3 byHost by host -> daemon 1. R6 Markdown old cell -> daemon 1. R5a/b web hostPortOf unbracketed / doubled -> urlOf NO + stats origin NO. R4 old statsOrigin -> stats origin NO while urlOf yes. urlOf.json removed -> check-web-static exit 2 and MirrorCasesTest 2 fail. R7 bind forced to fail -> both IPv6 tests SKIPPED with "skipped: no IPv6 loopback on this machine (binding [::1] failed: java.net.BindException: Can't assign requested address)".

Green: model 162, core 75 (IPv6 ran, 0 skipped), daemon 362, repo-checks 35, ui 136; render-web-ui 251 passed (pinned 251), archive 48 (pinned 48); goldens unchanged (api/, daemon test resources, structure/). Not run here: iosSimulatorArm64Test (xcrun), testAndroidHostTest and full ./gradlew build (no Android SDK).
<!-- SECTION:NOTES:END -->
