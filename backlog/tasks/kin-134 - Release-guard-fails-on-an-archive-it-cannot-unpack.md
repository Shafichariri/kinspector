---
id: KIN-134
title: Release guard fails on an archive it cannot unpack
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 16:49'
updated_date: '2026-10-22 18:06'
labels:
  - 'item:release-guard-unzip'
dependencies: []
ordinal: 134000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Found while fixing KIN-132 (release-guard-klib hand-over). scripts/check-release-clean.sh runs unzip with '|| true', for the target and for nested jars, so a corrupt or non-zip archive unpacks to an empty folder and reads as clean: a truncated jar or a text file named .klib gives PASSED, exit 0. That is a false pass in a release guard. Any archive the script cannot unpack must fail with exit 2, 'nothing was proven', naming the file, as the script already treats a missing target and its own internal errors.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 A truncated jar and a text file renamed .klib each give exit 2, naming the file, where main gives exit 0
- [x] #2 Real core and noop artifacts get the same verdict as before (core exit 1, noop exit 0)
- [x] #3 --self-test and the default check produce output byte-identical to main
- [x] #4 CI's packed-klib release-guard step pins the new cases
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Branch shafic/release-guard-unzip from 9481339. Hand-over: run-2026-10-20/handover/release-guard-unzip.md.

Fix: scripts/check-release-clean.sh keeps unzip's exit status, for the target and for each nested jar. unzip exits 1 for a warning with everything extracted and >=2 when it cannot read the archive, so >=2 marks the target UNREADABLE. After scanning, any UNREADABLE target ends the run with exit 2, "Nothing was proven about them", and the files listed, the same way a missing target is handled. The status is tested inline. A helper function returning 1 inside $(...) was tried first: under set -E it ran the ERR trap and printed a false "internal error" beside the real message.

Proofs, run against main's script and the fix on the same tree:
- truncated core jar (half of inspector-core-jvm-0.1.0-SNAPSHOT.jar, unzip exit 9): main "clean" + "PASSED: no capture code found in 1 artifact(s)", exit 0. Fix: "UNREADABLE (unzip exit 9): <file>" and "ERROR: 1 archive(s) could not be unpacked:" + the file, exit 2.
- text file named .klib: main exit 0 PASSED. Fix: exit 2, same message.
- aar wrapping a truncated classes.jar: main exit 0. Fix: exit 2, "UNREADABLE (unzip exit 9 on classes.jar)".
- folder holding a good noop jar and the text .klib: main exit 0. Fix: exit 2.
- real artifacts, stdout and stderr byte-identical to main: core iosArm64 klib exit 1, core jvm jar exit 1, noop klib exit 0, noop jvm jar exit 0, core libs dir exit 1, noop libs dir exit 0.
- --self-test and the default check, byte-identical to main: "SELF-TEST PASSED: canary detected in 4 core artifact(s)" and "PASSED: no capture code found in 3 artifact(s)", both exit 0. This machine has no Android SDK, so there are no aars here; CI's build job scans more.
- The CI step was extracted and run locally. Fix: all 8 cases pass. Main's script: the 6 existing cases pass, and both new ones fail with "expected exit 2, got 0".

Closed 2026-10-22 by the delivery tracker on QA's report qa/release-guard-unzip-report.md (verdict: close yes, no blocking findings), PR #173, merge 0b364f5.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA, old script against new on one tree: #1 truncated jar 'UNREADABLE (unzip exit 9): .../truncated.jar', 'ERROR: 1 archive(s) could not be unpacked:', exit 2 (old: PASSED, exit 0); a text file named notazip.klib and an aar wrapping a truncated classes.jar likewise; #2 real core (1) and noop (0) jar and klib output byte-identical (cmp); #3 --self-test 'SELF-TEST PASSED: canary detected in 4 core artifact(s)' and the default check 'PASSED: no capture code found in 2 artifact(s)' identical; #4 the ci.yml step run verbatim: new script 8 of 8 cases, old script 2 mismatches, exactly the new cases.
<!-- SECTION:FINAL_SUMMARY:END -->
