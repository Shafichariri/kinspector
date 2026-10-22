package dev.inspector.daemon

import dev.inspector.daemon.archive.CustomValueRule
import dev.inspector.daemon.archive.ShapeCheck
import dev.inspector.daemon.archive.ValueShape
import dev.inspector.daemon.service.FindingMask
import dev.inspector.daemon.service.HostClass
import dev.inspector.daemon.service.SensitiveKind
import dev.inspector.daemon.service.SensitiveMask
import dev.inspector.daemon.service.SensitiveValues
import dev.inspector.daemon.service.ValueMatch
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The value rules and their masks. Each rule is validated, never a bare pattern, and each mask passes
 * the findings engine's own guard, so the guard never has to blank a sensitive hint. Shown red with
 * the Luhn check removed, one IBAN length changed, the issuer digit removed and the `alg` bound removed.
 */
class SensitiveValuesTest {

    /** [prefix] with the Luhn check digit appended, computed here and not by the code under test. */
    private fun luhnComplete(prefix: String): String {
        for (check in '0'..'9') {
            val candidate = prefix + check
            var sum = 0
            candidate.reversed().forEachIndexed { i, c ->
                var d = c - '0'
                if (i % 2 == 1) d = (d * 2).let { if (it > 9) it - 9 else it }
                sum += d
            }
            if (sum % 10 == 0) return candidate
        }
        error("unreachable")
    }

    private fun texts(text: String, matches: List<ValueMatch>) = matches.map { text.substring(it.start, it.end) }

    private fun jwt(header: String) =
        Base64.getUrlEncoder().withoutPadding().encodeToString(header.toByteArray()) + ".eyJzdWIiOiIxIn0.c2ln"

    @Test
    fun `Luhn passes a valid number and fails it with any one digit changed`() {
        assertTrue(SensitiveValues.luhn("4000123412341234"))
        assertFalse(SensitiveValues.luhn("4000123412341235"))
        assertFalse(SensitiveValues.luhn("4000123412341224"))
        assertTrue(SensitiveValues.isCard("4000123412341234"))
        assertFalse(SensitiveValues.isCard("4000123412341235"), "a Luhn failure is not a card")
    }

    @Test
    fun `a card starts 2 to 6, so a Luhn-valid number starting 1, 7, 8, 9 or 0 is not one`() {
        for (first in listOf('1', '7', '8', '9', '0')) {
            val number = luhnComplete("${first}00012341234123")
            assertTrue(SensitiveValues.luhn(number))
            assertFalse(SensitiveValues.isCard(number), number)
            assertEquals(emptyList(), SensitiveValues.cards("pay $number now"), number)
        }
        for (first in '2'..'6') assertTrue(SensitiveValues.isCard(luhnComplete("${first}00012341234123")))
    }

    @Test
    fun `13 and 19 digits are cards, 12 and 20 are not`() {
        assertTrue(SensitiveValues.isCard(luhnComplete("4".padEnd(12, '1'))))
        assertTrue(SensitiveValues.isCard(luhnComplete("4".padEnd(18, '1'))))
        assertFalse(SensitiveValues.isCard(luhnComplete("4".padEnd(11, '1'))))
        assertFalse(SensitiveValues.isCard(luhnComplete("4".padEnd(19, '1'))))
    }

    @Test
    fun `single spaces or dashes may split a card, and the run is bounded by non-digits`() {
        val text = "a 4000 1234 1234 1234, b 4000-1234-1234-1234; c id4000123412341234x; d 4000  1234 1234 1234"
        assertEquals(
            listOf("4000 1234 1234 1234", "4000-1234-1234-1234", "4000123412341234"),
            texts(text, SensitiveValues.cards(text)),
        )
        assertEquals(emptyList(), SensitiveValues.cards("ref 94000123412341234"), "digits in a longer run are not a card")
    }

    @Test
    fun `a JSON number is a card only under a card field name`() {
        assertTrue(SensitiveValues.cardInJsonNumber("4000123412341234", "card_number"))
        assertTrue(SensitiveValues.cardInJsonNumber("4000123412341234", "creditCardNumber"))
        assertTrue(SensitiveValues.cardInJsonNumber("4000123412341234", "PAN"))
        assertFalse(SensitiveValues.cardInJsonNumber("4000123412341234", "id"))
        assertFalse(SensitiveValues.cardInJsonNumber("4000123412341234", "createdAt"))
        assertFalse(SensitiveValues.cardInJsonNumber("4000123412341234", "company"), "pan is matched whole only")
        assertFalse(SensitiveValues.cardInJsonNumber("4000123412341234", null))
        assertFalse(SensitiveValues.cardInJsonNumber("4000123412341235", "card_number"))
    }

    @Test
    fun `the eleven published test numbers are cards marked as test numbers`() {
        assertEquals(11, SensitiveValues.TEST_CARDS.size)
        for (number in SensitiveValues.TEST_CARDS) {
            val match = SensitiveValues.cards(" $number ").single()
            assertTrue(match.testNumber, number)
        }
        assertFalse(SensitiveValues.cards("4000123412341234").single().testNumber)
    }

    @Test
    fun `every IBAN table entry has a registry example of its length that passes mod-97`() {
        assertEquals(SensitiveValues.IBAN_LENGTHS.keys, IBAN_EXAMPLES.map { it.take(2) }.toSet(), "one example per table entry")
        val wrong = IBAN_EXAMPLES.filterNot { example ->
            SensitiveValues.IBAN_LENGTHS[example.take(2)] == example.length && SensitiveValues.isIban(example)
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `an IBAN with its last digit changed or its length off by one fails`() {
        val wrong = IBAN_EXAMPLES.filter { example ->
            val last = example.last()
            val changed = example.dropLast(1) + if (last.isDigit()) ('0' + (last - '0' + 1) % 10) else if (last == 'Z') 'A' else last + 1
            SensitiveValues.isIban(changed) || SensitiveValues.isIban(example.dropLast(1)) || SensitiveValues.isIban(example + "0")
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `IBANs are found in text in groups of four, in any case, and not inside a longer word`() {
        val text = "to GB82 WEST 1234 5698 7654 32 or de89370400440532013000; not XGB82WEST12345698765432; nl91 abna 0417 1643 00x"
        assertEquals(listOf("GB82 WEST 1234 5698 7654 32", "de89370400440532013000"), texts(text, SensitiveValues.ibans(text)))
        assertEquals(emptyList(), SensitiveValues.ibans("ZZ82WEST12345698765432"), "an unregistered country")
    }

    @Test
    fun `a JWT needs three segments and a header that decodes to JSON with a string alg`() {
        val good = jwt("""{"alg":"HS256","typ":"JWT"}""")
        assertTrue(SensitiveValues.isJwt(good))
        assertEquals(listOf(good), texts("/v1/x?t=$good&y=1", SensitiveValues.jwts("/v1/x?t=$good&y=1")))
        assertEquals(listOf(good), texts("see $good.", SensitiveValues.jwts("see $good.")), "a sentence's full stop is not a segment")
        assertFalse(SensitiveValues.isJwt(jwt("""{"typ":"JWT"}""")), "no alg")
        assertFalse(SensitiveValues.isJwt(jwt("""{"alg":256}""")), "alg not a string")
        assertFalse(SensitiveValues.isJwt(jwt("""["alg"]""")), "not an object")
        assertFalse(SensitiveValues.isJwt(good.substringBeforeLast('.')), "two segments")
        assertEquals(emptyList(), SensitiveValues.jwts("archive.tar.gz and v1.2.3"))
    }

    @Test
    fun `emails and phones in international form`() {
        val text = "mail a.b+c@mail.example.com, x@y, @no.com, d@host.c0m; call +44 20 7946 0958 or +1-555-0100 x or 1+12345678"
        assertEquals(listOf("a.b+c@mail.example.com"), texts(text, SensitiveValues.emails(text)))
        assertEquals(listOf("+44 20 7946 0958", "+1-555-0100"), texts(text, SensitiveValues.phones(text)), "8 digits is the least; a + after a digit is not a phone")
        assertEquals(listOf("+15550100123"), texts("+15550100123", SensitiveValues.phones("+15550100123")))
        assertEquals(emptyList(), SensitiveValues.phones("+1234567"), "7 digits")
        assertEquals(emptyList(), SensitiveValues.phones("+1234567890123456"), "16 digits")
    }

    @Test
    fun `secret parameter names are matched normalised and whole`() {
        for (name in listOf("api_key", "Api-Key", "access_token", "id_token", "client-secret", "Password", "session.id", "auth")) {
            assertTrue(SensitiveValues.isSecretName(name), name)
        }
        for (name in listOf("monkey", "author", "tokens", "pin", "page")) assertFalse(SensitiveValues.isSecretName(name), name)
        // A documented miss: whole names only, so a vendor-style prefix is not stripped.
        assertFalse(SensitiveValues.isSecretName("X-Api-Key"))
    }

    @Test
    fun `host classes, the emulator aliases included`() {
        val cases = mapOf(
            "localhost" to HostClass.LOCAL, "127.0.0.1" to HostClass.LOCAL, "127.8.9.1" to HostClass.LOCAL,
            "::1" to HostClass.LOCAL, "[::1]" to HostClass.LOCAL, "10.0.2.2" to HostClass.LOCAL, "10.0.3.2" to HostClass.LOCAL,
            "10.1.2.3" to HostClass.PRIVATE, "172.16.0.1" to HostClass.PRIVATE, "172.31.255.1" to HostClass.PRIVATE,
            "192.168.1.20" to HostClass.PRIVATE, "fd12:3456::1" to HostClass.PRIVATE, "fc00::1" to HostClass.PRIVATE,
            "printer.local" to HostClass.PRIVATE,
            "172.32.0.1" to HostClass.PUBLIC, "8.8.8.8" to HostClass.PUBLIC, "api.example.invalid" to HostClass.PUBLIC,
            "2001:db8::1" to HostClass.PUBLIC, "localhost.example.invalid" to HostClass.PUBLIC, "256.1.1.1" to HostClass.PUBLIC,
        )
        assertEquals(cases, cases.mapValues { SensitiveValues.hostClass(it.key) })
    }

    @Test
    fun `a custom shape matches bounded by non-alphanumerics and passes its check`() {
        val member = CustomValueRule("member-id", "Member", ValueShape("AA-######-#"), ShapeCheck.NONE)
        val text = "id XY-123456-7, ZXY-123456-7, XY-123456-78, xy-000001-2"
        assertEquals(listOf("XY-123456-7", "xy-000001-2"), texts(text, SensitiveValues.shapes(text, member)))
        val luhn = member.copy(shape = ValueShape("################"), check = ShapeCheck.LUHN)
        assertEquals(1, SensitiveValues.shapes("4000123412341234 4000123412341235", luhn).size)
        val mod97 = member.copy(shape = ValueShape("XXXXXXXXXXXXXXXXXXXX"), check = ShapeCheck.MOD97)
        // A legal entity identifier: twenty characters, ISO 7064 MOD 97-10 over the whole.
        assertEquals(1, SensitiveValues.shapes("lei 5493001KJTIIGC8Y1R12 or 5493001KJTIIGC8Y1R13", mod97).size)
    }

    @Test
    fun `every mask is exactly as tabled`() {
        val cases = listOf(
            SensitiveMask.hint(SensitiveKind.CARD, "4000-1234-1234-4242") to "•••• 4242",
            SensitiveMask.hint(SensitiveKind.IBAN, "gb82 west 1234 5698 7654 32") to "GB82 •••• 5432",
            SensitiveMask.hint(SensitiveKind.JWT, jwt("""{"alg":"HS256"}""")) to "•••• HS256",
            SensitiveMask.hint(SensitiveKind.JWT, jwt("""{"alg":"ES256K"}""")) to "•••• ES256K",
            SensitiveMask.hint(SensitiveKind.JWT, jwt("""{"alg":"RSA-OAEP"}""")) to "••••",
            SensitiveMask.hint(SensitiveKind.JWT, jwt("""{"alg":"ABCDEFG"}""")) to "••••",
            SensitiveMask.hint(SensitiveKind.EMAIL, "someone@mail.example.COM") to "•••@•••.com",
            SensitiveMask.hint(SensitiveKind.EMAIL, "someone@mail.example.museum") to "•••@•••.museum",
            SensitiveMask.hint(SensitiveKind.EMAIL, "someone@studio.example.abcdefg") to "•••@•••",
            SensitiveMask.hint(SensitiveKind.PHONE, "+44 20 7946 0967") to "•••• 67",
            SensitiveMask.hint(SensitiveKind.AUTHORIZATION, "bearer abc.def") to "Bearer ••••",
            SensitiveMask.hint(SensitiveKind.AUTHORIZATION, "Negotiate YIIabc") to "Negotiate ••••",
            SensitiveMask.hint(SensitiveKind.AUTHORIZATION, "Custom secretvalue") to "••••",
            SensitiveMask.hint(SensitiveKind.AUTHORIZATION, "rawtokenwithoutscheme") to "••••",
            SensitiveMask.hint(SensitiveKind.SECRET, "hunter2-and-more") to "••••",
            SensitiveMask.hint(SensitiveKind.CUSTOM, "XY-123456-7") to "••••",
        )
        assertEquals(cases.map { it.second }, cases.map { it.first })
    }

    @Test
    fun `every mask kind at its longest passes the findings engine's guard`() {
        val longest = listOf(
            SensitiveMask.hint(SensitiveKind.CARD, "4000123412341234123"),
            SensitiveMask.hint(SensitiveKind.IBAN, "LC55HEMM000100010012001200023015"),
            SensitiveMask.hint(SensitiveKind.JWT, jwt("""{"alg":"ES256K"}""")),
            SensitiveMask.hint(SensitiveKind.JWT, jwt("""{"alg":"ABCDEFGHIJKLMNOP"}""")),
            SensitiveMask.hint(SensitiveKind.EMAIL, "someone@mail.example.museum"),
            SensitiveMask.hint(SensitiveKind.EMAIL, "someone@example.photography"),
            SensitiveMask.hint(SensitiveKind.PHONE, "+123456789012345"),
            SensitiveMask.hint(SensitiveKind.AUTHORIZATION, "Negotiate YIIabc"),
            SensitiveMask.hint(SensitiveKind.AUTHORIZATION, "SomeVeryLongSchemeName credential"),
            SensitiveMask.hint(SensitiveKind.SECRET, "x".repeat(500)),
            SensitiveMask.hint(SensitiveKind.CUSTOM, "y".repeat(40)),
        )
        assertEquals(emptyList(), longest.filterNot(FindingMask::isMasked))
    }

    @Test
    fun `a key that matches a value rule is written as one dot in a path`() {
        val token = jwt("""{"alg":"HS256"}""")
        assertEquals(SensitiveMask.KEY, SensitiveMask.pathKey(token))
        assertEquals(SensitiveMask.KEY, SensitiveMask.pathKey("4000123412341234"))
        val member = CustomValueRule("member-id", "Member", ValueShape("AA-######-#"))
        assertEquals(SensitiveMask.KEY, SensitiveMask.pathKey("XY-123456-7", listOf(member)))
        assertEquals("XY-123456-7", SensitiveMask.pathKey("XY-123456-7", listOf(member.copy(enabled = false))))
        assertEquals("sessions", SensitiveMask.pathKey("sessions"))
    }

    private companion object {
        /** One example per country, from the SWIFT IBAN Registry; `GB82WEST…` is the widely published second. */
        val IBAN_EXAMPLES = """
            AD1200012030200359100100 AE070331234567890123456 AL47212110090000000235698741 AT611904300234573201
            AZ21NABZ00000000137010001944 BA391290079401028494 BE68539007547034 BG80BNBG96611020345678
            BH67BMAG00001299123456 BI4210000100010000332045181 BR1800360305000010009795493C1
            BY13NBRB3600900000002Z00AB00 CH9300762011623852957 CR05015202001026284066 CY17002001280000001200527600
            CZ6508000000192000145399 DE89370400440532013000 DJ2100010000000154000100186 DK5000400440116243
            DO28BAGR00000001212453611324 EE382200221020145685 EG380019000500000000263180002 ES9121000418450200051332
            FI2112345600000785 FK88SC123456789012 FO6264600001631634 FR1420041010050500013M02606 GB29NWBK60161331926819
            GB82WEST12345698765432 GE29NB0000000101904917 GI75NWBK000000007099453 GL8964710001000206
            GR1601101250000000012300695 GT82TRAJ01020000001210029690 HR1210010051863000160 HU42117730161111101800000000
            IE29AIBK93115212345678 IL620108000000099999999 IQ98NBIQ850123456789012 IS140159260076545510730339
            IT60X0542811101000000123456 JO94CBJO0010000000000131000302 KW81CBKU0000000000001234560101
            KZ86125KZT5004100100 LB62099900000001001901229114 LC55HEMM000100010012001200023015 LI21088100002324013AA
            LT121000011101001000 LU280019400644750000 LV80BANK0000435195001 LY83002048000020100120361
            MC5811222000010123456789030 MD24AG000225100013104168 ME25505000012345678951 MK07250120000058984
            MN121234123456789123 MR1300020001010000123456753 MT84MALT011000012345MTLCAST001S
            MU17BOMM0101101030300200000MUR NI45BAPR00000013000003558124 NL91ABNA0417164300 NO9386011117947
            OM810180000001299123456 PK36SCBL0000001123456702 PL61109010140000071219812874 PS92PALS000000000400123456702
            PT50000201231234567890154 QA58DOHB00001234567890ABCDEFG RO49AAAA1B31007593840000 RS35260005601001611379
            RU0304452522540817810538091310419 SA0380000000608010167519 SC18SSCB11010000000000001497USD
            SD2129010501234001 SE4550000000058398257466 SI56263300012039086 SK3112000000198742637541
            SM86U0322509800000000270100 SO211000001001000100141 ST68000100010051845310112 SV62CENR00000000000000700025
            TL380080012345678910157 TN5910006035183598478831 TR330006100519786457841326 UA213223130000026007233566001
            VA59001123000012345678 VG96VPVG0000012345678901 XK051212012345678906 YE15CBYE0001018861234567891234
        """.trim().split(Regex("\\s+"))
    }
}
