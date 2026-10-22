package dev.inspector.daemon

import dev.inspector.daemon.archive.SensitiveConfig
import dev.inspector.daemon.archive.SensitiveSettings
import dev.inspector.daemon.service.FieldClass
import dev.inspector.daemon.service.FieldNames
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Field-name rules: normalised, whole below six characters and as a suffix from six, so `pin` never
 * matches `shipping` and `newPassword` is a password. Shown red with the suffix rule applied at every
 * length.
 */
class SensitiveFieldNamesTest {

    private val names = FieldNames()

    @Test
    fun `a name is lowercased with underscores, dashes, dots and spaces removed`() {
        assertEquals(listOf("firstname"), listOf("firstName", "first-name", "FIRST_NAME", "first.name", "first name").map(SensitiveConfig::normalise).distinct())
        assertEquals(
            List(5) { FieldClass.NAME },
            listOf("firstName", "first-name", "FIRST_NAME", "first.name", "first name").map(names::classOf),
        )
    }

    @Test
    fun `a listed name of six or more characters matches as a suffix`() {
        val cases = mapOf(
            "newPassword" to FieldClass.SECRET,
            "user_password" to FieldClass.SECRET,
            "creditCardNumber" to FieldClass.PAYMENT,
            "payee_account_number" to FieldClass.PAYMENT,
            "customerDateOfBirth" to FieldClass.IDENTITY,
            "holder_last_name" to FieldClass.NAME,
            "clientSecret" to FieldClass.SECRET,
        )
        assertEquals(cases, cases.mapValues { names.classOf(it.key) })
    }

    @Test
    fun `a shorter listed name matches only whole`() {
        val whole = mapOf("pin" to FieldClass.SECRET, "OTP" to FieldClass.SECRET, "cvv" to FieldClass.PAYMENT, "iban" to FieldClass.PAYMENT, "dob" to FieldClass.IDENTITY, "pan" to FieldClass.PAYMENT)
        assertEquals(whole, whole.mapValues { names.classOf(it.key) })
        val none = listOf("shipping", "footprint", "opinion", "payeeIban", "company", "spinner", "cvvHint", "adobe", "username", "id")
        assertEquals(none.associateWith { null }, none.associateWith { names.classOf(it) })
    }

    @Test
    fun `every shipped name is in exactly one class, as tabled`() {
        val all = FieldNames.SHIPPED.flatMap { it.second }
        assertEquals(all.distinct(), all)
        assertEquals(
            mapOf(FieldClass.SECRET to 7, FieldClass.PAYMENT to 8, FieldClass.IDENTITY to 15, FieldClass.NAME to 7),
            FieldNames.SHIPPED.associate { it.first to it.second.size },
        )
    }

    @Test
    fun `add puts a name in the identity class and remove drops a shipped one`() {
        val edited = FieldNames(SensitiveSettings(addFieldNames = listOf("staffnumber", "mrn"), removeFieldNames = listOf("firstname", "pin")))
        assertEquals(FieldClass.IDENTITY, edited.classOf("staff_number"))
        assertEquals(FieldClass.IDENTITY, edited.classOf("primaryStaffNumber"), "an added name of six or more is a suffix too")
        assertEquals(FieldClass.IDENTITY, edited.classOf("MRN"))
        assertEquals(null, edited.classOf("patientMrn"), "an added name under six matches whole only")
        assertEquals(null, edited.classOf("firstName"))
        assertEquals(null, edited.classOf("pin"))
        assertEquals(FieldClass.NAME, edited.classOf("lastName"), "the rest of the class stays")
    }
}
