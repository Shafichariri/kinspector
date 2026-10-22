---
id: KIN-132
title: Release guard unpacks published klibs and exits 2 on its own errors
status: Done
assignee:
  - '@engineer'
created_date: '2026-10-22 16:00'
updated_date: '2026-10-22 18:06'
labels:
  - 'item:release-guard-klib'
dependencies: []
ordinal: 132000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Faults F2 and F3 from the 1.4.0 release rehearsal (KIN-130). F2: check-release-clean.sh --paths over a published .klib (a deflate zip) reports clean although the canary is inside it, in default/ir/strings.knt, because the archive patterns in scan_target and the directory find leave out *.klib. F3: a long absolute --paths target becomes a temp folder name longer than NAME_MAX; mkdir fails with 'File name too long' and the script exits 1, which means canary found, instead of 2. Out of scope: the default and --self-test target lists, which must behave exactly as before, and the POM fault F1.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 --paths over a published inspector-core iOS klib, as a file or as its publication directory, finds the canary and exits 1; the same command on main before the fix reports clean.
- [x] #2 --paths over the published inspector-noop iOS klibs reports clean and exits 0.
- [x] #3 A --paths target whose path is longer than NAME_MAX is scanned with its true result, and any internal error of the script exits 2, never 1.
- [x] #4 --self-test and the default real check scan the same artifacts and give the same result and exit code as before the change.
- [x] #5 The new cases run in CI, and each was proven red against the old behaviour.
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [x] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [x] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Built on branch shafic/release-guard-klib from 201a46c. scan_target unpacks *.klib like a jar, and the directory find includes *.klib files (-type f). Directories are dispatched before the name-based archive case, so a folder named x.klib is grepped as a tree and not handed to unzip. Unpack folders are numbered (WORK/1, WORK/2...). An ERR trap with set -E makes any failure the script did not decide on exit 2; the find pipelines that feed loops end '|| true' because the trap also reaches process substitutions, where an empty match is not an error. collect_artifacts targets are unchanged: --self-test and the default check printed byte-identical output before and after (5 core artifacts; 11 noop artifacts on this tree). CI build job, new step 'Release guard — packed klibs, deep paths, internal errors': packs iosArm64 klibs with :inspector-core:iosArm64Klib and :inspector-noop:iosArm64Klib and pins six exit codes. Against main's script four of them fail (core klib 0 instead of 1, deep core folder 0 instead of 1, deep noop folder 1 'File name too long' instead of 0, stubbed failing mktemp 1 instead of 2); with the directory-first line removed, the deep core folder and unpacked.klib cases fail. Not changed: unzip errors on a corrupt archive are still masked by '|| true' and read as clean (existing behaviour, outside F2/F3).

Closed 2026-10-22 by the delivery tracker on QA's report qa/release-guard-klib-report.md (verdict: close yes, no blocking findings), PR #171, merge f32734e. QA's follow-up, a corrupt archive reading clean, was KIN-134. Not run by QA: the full gradlew build and -Pinspector=off (no Android SDK); CI covers them.
<!-- SECTION:NOTES:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
QA, old script (201a46c) against new (f32734e) on the same tree: #1 --paths on the published core iosArm64 klib: old 'PASSED' exit 0, new 'CANARY FOUND' exit 1 (the simulator klib and the publication folder alike); #2 noop klib folders PASSED exit 0; #3 a path past NAME_MAX gives the true verdict (old died with 'File name too long', exit 1) and a failing mktemp gives exit 2 with 'ERROR: internal error at line 107 (exit 1); nothing was proven.'; #4 --self-test and the default check byte-identical (cmp); #5 the ci.yml step run verbatim: new script 0 mismatches, old script 4.
<!-- SECTION:FINAL_SUMMARY:END -->
