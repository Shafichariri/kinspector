package dev.inspector.ui

import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That the JVM actual reports this machine's real offset.
 *
 * The common test can only check the value is plausible, and a stub returning `0` is plausible in
 * London. This compares against `java.time`, which is a different route to the same fact — so an
 * actual that stopped asking the platform would disagree here even though the number still looked
 * like a timezone.
 *
 * JVM-only on purpose: there is no second route on Kotlin/Native that is not the same Foundation
 * call the actual already makes, so an iOS version of this would only assert that a function
 * returns what it returns.
 */
class LocalOffsetJvmTest {

    @Test
    fun `the offset is this machine's own`() {
        val expected = ZonedDateTime.now(ZoneId.systemDefault()).offset.totalSeconds
        assertEquals(expected, localUtcOffsetSeconds())
    }
}
