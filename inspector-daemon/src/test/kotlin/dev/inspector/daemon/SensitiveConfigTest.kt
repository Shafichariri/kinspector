package dev.inspector.daemon

import dev.inspector.daemon.archive.CustomValueRule
import dev.inspector.daemon.archive.DismissalEntry
import dev.inspector.daemon.archive.FindingSettings
import dev.inspector.daemon.archive.FindingSeverity
import dev.inspector.daemon.archive.SensitiveConfig
import dev.inspector.daemon.archive.SensitiveSettings
import dev.inspector.daemon.archive.ShapeCheck
import dev.inspector.daemon.archive.ValueShape
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `findings.sensitive` and `findingDismissals`: every message in this file's words, the shape
 * grammar, and the read that skips a bad entry and keeps the rest. `ConfigFileTest` holds the `PUT`
 * side, where the first problem refuses the save whole.
 */
class SensitiveConfigTest {

    private fun problem(text: String): String? = SensitiveConfig.problem(Json.parseToJsonElement(text))

    private fun read(text: String): Pair<SensitiveSettings, List<String>> {
        val problems = ArrayList<String>()
        return SensitiveConfig.read(Json.parseToJsonElement(text)) { problems += it } to problems
    }

    private fun dismissalsProblem(text: String): String? = SensitiveConfig.dismissalsProblem(Json.parseToJsonElement(text))

    @Test
    fun `a valid key is read whole, hosts lowercased and names normalised`() {
        val (settings, problems) = read(
            """{"firstPartyHosts":["API.example.invalid","*.example.invalid"],
                "fieldNames":{"add":["staff_Number"],"remove":["firstName"]},
                "valueRules":[{"id":"member-id","label":"Member number","shape":"AA-######-#","check":"luhn",
                               "severity":"high","enabled":false,"fields":["memberId"]}]}""",
        )
        assertEquals(emptyList(), problems)
        assertEquals(listOf("api.example.invalid", "*.example.invalid"), settings.firstPartyHosts)
        assertEquals(listOf("staffnumber"), settings.addFieldNames)
        assertEquals(listOf("firstname"), settings.removeFieldNames)
        assertEquals(
            listOf(CustomValueRule("member-id", "Member number", ValueShape("AA-######-#"), ShapeCheck.LUHN, FindingSeverity.HIGH, false, listOf("memberid"))),
            settings.valueRules,
        )
        assertEquals("sensitive.custom.member-id", settings.valueRules.single().ruleId)
    }

    @Test
    fun `a custom rule defaults to no check, medium, enabled and any field`() {
        val rule = read("""{"valueRules":[{"id":"a","label":"A","shape":"####"}]}""").first.valueRules.single()
        assertEquals(CustomValueRule("a", "A", ValueShape("####"), ShapeCheck.NONE, FindingSeverity.MEDIUM, true, emptyList()), rule)
    }

    @Test
    fun `the shape grammar is placeholders and literals, 4 to 40 characters, never a regular expression`() {
        assertNull(ValueShape.problem("AA-######-#"))
        assertNull(ValueShape.problem("####"))
        assertNull(ValueShape.problem("X".repeat(40)))
        assertNull(ValueShape.problem("###.###.###-##"), "a dot is a literal")
        val refused = mapOf(
            "###" to "a shape is 4 to 40 characters",
            "#".repeat(41) to "a shape is 4 to 40 characters",
            "\\d{4}-\\d{4}" to "a shape is not a regular expression",
            "[0-9]{8}" to "a shape is not a regular expression",
            "AB(#|#)" to "a shape is not a regular expression",
            "####+" to "a shape is not a regular expression",
            "^###$" to "a shape is not a regular expression",
            "abc-def" to "a shape needs at least one # (digit), A (letter) or X (either)",
        )
        val wrong = refused.mapNotNull { (shape, expected) ->
            val got = ValueShape.problem(shape)
            if (got != null && expected in got) null else "$shape → $got"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `every bad value is named in this file's words`() {
        val cases = mapOf(
            """[]""" to "findings.sensitive must be an object",
            """{"hosts":[]}""" to "findings.sensitive has an unknown key 'hosts'; the keys are firstPartyHosts, fieldNames, valueRules",
            """{"firstPartyHosts":"api.example.invalid"}""" to "findings.sensitive.firstPartyHosts must be a list",
            """{"firstPartyHosts":["https://api.example.invalid"]}""" to "entry 'https://api.example.invalid' is not a host",
            """{"firstPartyHosts":["api.example.invalid:8443"]}""" to "is not a host",
            """{"firstPartyHosts":["api.*.invalid"]}""" to "is not a host",
            """{"firstPartyHosts":["*example.invalid"]}""" to "is not a host",
            """{"firstPartyHosts":[3]}""" to "findings.sensitive.firstPartyHosts entry 3 must be a string",
            """{"fieldNames":["a"]}""" to "findings.sensitive.fieldNames must be an object",
            """{"fieldNames":{"added":[]}}""" to "findings.sensitive.fieldNames has an unknown key 'added'",
            """{"fieldNames":{"add":"staff"}}""" to "findings.sensitive.fieldNames.add must be a list",
            """{"fieldNames":{"remove":["__"]}}""" to "findings.sensitive.fieldNames.remove entry '__' has no letters or digits",
            """{"valueRules":{}}""" to "findings.sensitive.valueRules must be a list",
            """{"valueRules":["####"]}""" to "findings.sensitive.valueRules entry \"####\" must be an object",
            """{"valueRules":[{"label":"A","shape":"####"}]}""" to "id must be 1 to 40 lowercase letters, digits and -",
            """{"valueRules":[{"id":"Member","label":"A","shape":"####"}]}""" to "id must be 1 to 40 lowercase letters",
            """{"valueRules":[{"id":"a","label":"A","shape":"####","regex":"x"}]}""" to "'a' has an unknown key 'regex'",
            """{"valueRules":[{"id":"a","shape":"####"}]}""" to "'a' label must be 1 to 80 characters",
            """{"valueRules":[{"id":"a","label":"A"}]}""" to "'a' shape: a shape is a string",
            """{"valueRules":[{"id":"a","label":"A","shape":"\\d{6}"}]}""" to "'a' shape: a shape is not a regular expression",
            """{"valueRules":[{"id":"a","label":"A","shape":"####","check":"crc"}]}""" to "'a' check must be none, luhn, mod97",
            """{"valueRules":[{"id":"a","label":"A","shape":"####","severity":"low"}]}""" to "'a' severity must be high, medium, info",
            """{"valueRules":[{"id":"a","label":"A","shape":"####","enabled":"yes"}]}""" to "'a' enabled must be true or false",
            """{"valueRules":[{"id":"a","label":"A","shape":"####","fields":"memberId"}]}""" to "'a' fields must be a list",
            """{"valueRules":[{"id":"a","label":"A","shape":"####"},{"id":"a","label":"B","shape":"####"}]}""" to "two rules with the id 'a'",
        )
        val wrong = cases.mapNotNull { (text, expected) ->
            val got = problem(text)
            if (got != null && expected in got) null else "$text → $got"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `the findings key names sensitive, and findings rules refuses a custom rule's id`() {
        val unknown = FindingSettings.problem(Json.parseToJsonElement("""{"sensitiv":{}}"""))
        assertEquals("findings has an unknown key 'sensitiv'; the keys are rules, payment, sensitive", unknown)
        val custom = FindingSettings.problem(
            Json.parseToJsonElement("""{"rules":{"sensitive.custom.member-id":{"enabled":false}}}"""),
        )
        assertEquals(
            "findings.rules cannot take the custom rule 'sensitive.custom.member-id'; set enabled and severity on its entry in findings.sensitive.valueRules",
            custom,
        )
        assertEquals(
            "findings.sensitive has an unknown key 'x'; the keys are firstPartyHosts, fieldNames, valueRules",
            FindingSettings.problem(Json.parseToJsonElement("""{"sensitive":{"x":1}}""")),
        )
    }

    @Test
    fun `a read skips each bad entry, names it, and keeps the rest`() {
        val (settings, problems) = read(
            """{"firstPartyHosts":["api.example.invalid","http://bad","*.example.invalid"],
                "fieldNames":{"add":["staffNumber", 7]},
                "valueRules":[{"id":"ok","label":"Ok","shape":"####"},{"id":"bad","label":"Bad","shape":"\\d+"}]}""",
        )
        assertEquals(listOf("api.example.invalid", "*.example.invalid"), settings.firstPartyHosts)
        assertEquals(listOf("staffnumber"), settings.addFieldNames)
        assertEquals(listOf("ok"), settings.valueRules.map { it.id })
        assertEquals(3, problems.size, problems.toString())
    }

    @Test
    fun `canonical carries sensitive, stable under order and spelling`() {
        fun canonical(sensitive: String) = FindingSettings.read(Json.parseToJsonElement("""{"sensitive":$sensitive}""")) {}.canonical()
        val a = canonical("""{"firstPartyHosts":["b.example.invalid","A.example.invalid"],"fieldNames":{"add":["staff_number"]}}""")
        val b = canonical("""{"fieldNames":{"add":["StaffNumber"],"remove":[]},"firstPartyHosts":["a.example.invalid","b.example.invalid"]}""")
        assertEquals(a, b)
        assertEquals(FindingSettings.DEFAULT.canonical(), canonical("""{}"""))
        assertNotEquals(a, canonical("""{"firstPartyHosts":["a.example.invalid"]}"""))
        assertNotEquals(
            canonical("""{"valueRules":[{"id":"a","label":"A","shape":"####"}]}"""),
            canonical("""{"valueRules":[{"id":"a","label":"A","shape":"####","enabled":false}]}"""),
        )
    }

    @Test
    fun `first-party hosts are exact, or subdomains under a leading star that does not match the bare domain`() {
        val settings = SensitiveSettings(firstPartyHosts = listOf("api.example.invalid", "*.cdn.example.invalid"))
        assertTrue(settings.firstParty("API.example.invalid"))
        assertTrue(settings.firstParty("img.cdn.example.invalid"))
        assertTrue(settings.firstParty("a.b.cdn.example.invalid"))
        assertFalse(settings.firstParty("cdn.example.invalid"))
        assertFalse(settings.firstParty("evilcdn.example.invalid"))
        assertFalse(settings.firstParty("api.example.invalid.evil.invalid"))
        assertFalse(SensitiveSettings().firstParty("api.example.invalid"), "an empty list holds nothing")
    }

    @Test
    fun `a valid dismissal list is read whole`() {
        val list = SensitiveConfig.readDismissals(
            Json.parseToJsonElement(
                """[{"ruleId":"sensitive.field.secret","route":"POST https://auth.example.invalid/login","location":"reqBody",
                     "path":"$.password","reason":"the login form","at":"2026-10-21"},
                    {"ruleId":"payment.retry-with-key","route":"POST https://api.example.invalid/pay","reason":"expected"}]""",
            ),
        ) { error(it) }
        assertEquals(
            listOf(
                DismissalEntry("sensitive.field.secret", "POST https://auth.example.invalid/login", "reqBody", "$.password", "the login form", "2026-10-21"),
                DismissalEntry("payment.retry-with-key", "POST https://api.example.invalid/pay", null, null, "expected", null),
            ),
            list,
        )
    }

    @Test
    fun `every bad dismissal is named in this file's words`() {
        val good = """"ruleId":"r.x","route":"GET https://a.example.invalid/x","reason":"why""""
        val cases = mapOf(
            """{}""" to "findingDismissals must be a list",
            """["x"]""" to "findingDismissals entry 1 must be an object",
            """[{$good,"session":"s1"}]""" to "findingDismissals entry 1 has an unknown key 'session'",
            """[{"route":"GET /x","reason":"why"}]""" to "entry 1 needs a ruleId",
            """[{"ruleId":"r.x","route":" ","reason":"why"}]""" to "entry 1 needs a route",
            """[{"ruleId":"r.x","route":"GET /x"}]""" to "entry 1 needs a reason of 1 to 200 characters",
            """[{"ruleId":"r.x","route":"GET /x","reason":"   "}]""" to "needs a reason",
            """[{"ruleId":"r.x","route":"GET /x","reason":"${"r".repeat(201)}"}]""" to "needs a reason of 1 to 200",
            """[{$good,"location":"body"}]""" to "location must be url, query, reqHeader, resHeader, reqBody, resBody or null",
            """[{$good,"path":3}]""" to "path must be a string or null",
            """[{$good},{"ruleId":"r.y"}]""" to "findingDismissals entry 2 needs a route",
        )
        val wrong = cases.mapNotNull { (text, expected) ->
            val got = dismissalsProblem(text)
            if (got != null && expected in got) null else "$text → $got"
        }
        assertEquals(emptyList(), wrong)
        assertNull(dismissalsProblem("""[{"ruleId":"r.x","route":"GET /x","reason":"${"r".repeat(200)}"}]"""))
        assertNull(dismissalsProblem("""[{$good,"location":null,"path":null}]"""))
        val many = (1..1001).joinToString(",") { "{$good}" }
        assertEquals("findingDismissals holds 1001 entries; the most is 1000", dismissalsProblem("[$many]"))
    }

    @Test
    fun `a dismissal read skips each bad entry, names it, and keeps the rest`() {
        val problems = ArrayList<String>()
        val list = SensitiveConfig.readDismissals(
            Json.parseToJsonElement("""[{"ruleId":"a","route":"GET /a","reason":"r"},{"ruleId":"b","route":"GET /b"},{"ruleId":"c","route":"GET /c","reason":"r"}]"""),
        ) { problems += it }
        assertEquals(listOf("a", "c"), list.map { it.ruleId })
        assertEquals(listOf("findingDismissals entry 2 needs a reason of 1 to 200 characters: why the finding is expected"), problems)
    }
}
