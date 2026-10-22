---
id: KIN-120
title: >-
  Sensitive data T1: value and field-name rules, masks and the
  findings.sensitive config
status: In Progress
assignee:
  - '@engineer'
created_date: '2026-10-22 09:40'
updated_date: '2026-10-22 10:47'
labels:
  - 'item:sensitive-data-detection'
dependencies:
  - KIN-113
type: feature
ordinal: 120000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Order row 30, docs/ROADMAP.md §6 → Sensitive-data detection — detect, never redact. Built to the architect's design note for item:sensitive-data-detection (questions answered 2026-10-22 by the maintainer's proxy: findings and findingDismissals are read live; an empty firstPartyHosts raises nothing for where data was sent and the page says why; no MCP tool can dismiss). Plugs into Findings (item:findings-payment-safety) as one FindingSource; no public API, no schema, no change to what capture records. Files: archive/SensitiveConfig.kt, archive/FindingSettings.kt (the sensitive sub-object, problem, canonical()), archive/DaemonConfig.kt (findingDismissals declared on FileConfig), archive/ConfigFile.kt, service/SensitiveValues.kt, service/SensitiveMask.kt, tests SensitiveValuesTest, SensitiveFieldNamesTest, SensitiveConfigTest, ConfigFileTest.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Card: 13-19 digits, first digit 2-6, Luhn; a JSON number counts only under a card field name; the 11 published test numbers are info
- [ ] #2 IBAN: the ISO 13616 length per country and mod-97; every table entry has a registry example that passes, and fails with its last digit changed or its length off by one
- [ ] #3 JWT (first segment decodes to a JSON object with a string alg), secret parameter names, email, phone and host classes (loopback, private, emulator aliases 10.0.2.2 and 10.0.3.2) as tabled
- [ ] #4 Field names normalised (lowercase, _ - . and spaces removed), whole below six characters, suffix from six; four classes as tabled; fieldNames add and remove
- [ ] #5 Custom value rules are shapes (# A X and literals, 4-40 characters) with check none, luhn or mod97 and their own severity and enabled; no regular expression is accepted; findings.rules refuses a sensitive.custom.* id
- [ ] #6 Masks as tabled; nothing but SensitiveMask.hint reads a matched value; every mask kind at its longest, an over-long alg, a 7-letter top-level domain and an unknown auth scheme pass FindingMask.isMasked
- [ ] #7 PUT /api/config refuses a bad findings.sensitive or findingDismissals whole, naming the problem; at read a bad entry is named and skipped
- [ ] #8 Each guard shown red: Luhn removed, one IBAN length changed, issuer digit removed, the alg bound removed, the suffix rule applied at every length, validation skipped on PUT
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Every acceptance criterion is Verified in the qa-verifier's table, with the command and the decisive line of output
- [ ] #2 Each new guard was proven in both directions: broken, seen failing, restored
- [ ] #3 No client, employer or product name in the ticket, the diff, or the commit message
<!-- DOD:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
Ready for QA once its PR merges. archive/SensitiveConfig.kt (ValueShape and its grammar, ShapeCheck, CustomValueRule, SensitiveSettings with firstParty(), DismissalEntry, SensitiveConfig.read/problem/readDismissals/dismissalsProblem/normalise), archive/FindingSettings.kt (sensitive sub-object, KEYS, canonical(), findings.rules refuses sensitive.custom.*), archive/DaemonConfig.kt (FileConfig.findingDismissals: JsonArray so a non-list makes the file malformed), archive/ConfigFile.kt (KNOWN_KEYS + validation), service/SensitiveValues.kt (cards, test numbers, JSON-number rule, IBAN table of 89 countries + mod-97, JWT, email, phone, custom shapes, secret names, host classes, FieldNames), service/SensitiveMask.kt (hint, pathKey). Tests: SensitiveValuesTest, SensitiveFieldNamesTest, SensitiveConfigTest, ConfigFileTest (+3). Red proofs (each run, seen failing, restored): Luhn sum check removed -> 'Luhn passes a valid number…' (Expected value to be false), the JSON-number test and the luhn custom-shape test (expected <1> but was <2>); GB length 22->23 -> 'every IBAN table entry…' (was [GB29NWBK…, GB82WEST…]) and the text-finder test; issuer digit removed -> 'a card starts 2 to 6…' (1000123412341230); alg bound widened to [A-Za-z0-9-]+ -> 'every mask is exactly as tabled' and 'every mask kind at its longest passes the findings engine's guard' (was [•••• ABCDEFGHIJKLMNOP]); suffix rule at every length -> 'a shorter listed name matches only whole' and the add/remove test (patientMrn read as IDENTITY); findings and findingDismissals validation skipped on PUT -> both ConfigFileTest refusal tests (Written instead of Invalid). Known miss pinned in SensitiveValuesTest: secret names are whole, so X-Api-Key (xapikey) is not a secret name. IBAN table: lengths and one example per country transcribed from the SWIFT IBAN Registry; every example passes mod-97 at its table length, so a typo in either fails. — bob

Merged in PR #156 (merge f0c53a2), CI build, release-swap, repo and web all pass. — bob
<!-- SECTION:NOTES:END -->
