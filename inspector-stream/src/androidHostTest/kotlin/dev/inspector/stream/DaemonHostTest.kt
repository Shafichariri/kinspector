package dev.inspector.stream

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The host choice, and the sentence a developer gets when it does not work.
 *
 * The two are tested together on purpose: the address and the remedy have to agree, and they are
 * written in different files. A message telling someone to run `adb reverse` while the sink is
 * dialling the emulator alias is worse than no message, because it is confidently wrong.
 */
class DaemonHostTest {

    // The same profiles EmulatorDetectionTest uses, run through the real detection rather than a
    // hand-set boolean, so this asserts the whole path from Build fields to a dialled address.
    private fun hostFor(
        hardware: String,
        board: String,
        device: String,
        product: String,
        brand: String,
        model: String,
        fingerprint: String,
    ) = daemonHostFor(
        looksLikeEmulator(hardware, board, device, product, brand, model, fingerprint),
    )

    @Test
    fun `an emulator dials the host alias`() {
        assertEquals(
            "10.0.2.2",
            hostFor(
                hardware = "ranchu",
                board = "goldfish_arm64",
                device = "emu64a",
                product = "sdk_gphone64_arm64",
                brand = "google",
                model = "sdk_gphone64_arm64",
                fingerprint =
                    "google/sdk_gphone64_arm64/emu64a:14/UE1A.230829.050/12077443:userdebug/dev-keys",
            ),
        )
    }

    @Test
    fun `a phone dials its own loopback, which adb reverse forwards`() {
        assertEquals(
            "127.0.0.1",
            hostFor(
                hardware = "zuma",
                board = "zuma",
                device = "husky",
                product = "husky",
                brand = "google",
                model = "Pixel 8 Pro",
                fingerprint = "google/husky/husky:14/UD1A.230803.041/10808477:user/release-keys",
            ),
        )
    }

    @Test
    fun `the phone message names adb reverse and the emulator message does not`() {
        val onPhone = connectionHelp(DEVICE_LOOPBACK, 8099)
        assertTrue(onPhone.contains("adb reverse tcp:8099 tcp:8099"), onPhone)

        // The emulator alias needs no adb reverse, so offering it there would send someone to run
        // a command that changes nothing. It may still *mention* the command while explaining
        // what to do on hardware, so the assertion is about which address it tells you to use.
        val onEmulator = connectionHelp(EMULATOR_HOST_ALIAS, 8099)
        assertFalse(
            onEmulator.contains("$EMULATOR_HOST_ALIAS is this device's own loopback"),
            onEmulator,
        )
        assertTrue(onEmulator.contains("reaches nothing"), onEmulator)
    }

    @Test
    fun `every message names the address that was actually tried`() {
        // The failure this guards is a hardcoded address in the advice. Someone who set host to
        // a value of their own gets a sentence about 10.0.2.2, edits a manifest for an address
        // their app never dialled, and learns nothing.
        for (host in listOf(EMULATOR_HOST_ALIAS, DEVICE_LOOPBACK, "localhost", "192.168.1.42")) {
            val help = connectionHelp(host, 8099)
            assertTrue(help.contains(host), "help for $host did not name it: $help")
        }
    }

    @Test
    fun `the port in the message is the port that was tried`() {
        val help = connectionHelp(DEVICE_LOOPBACK, 9999)
        assertTrue(help.contains("adb reverse tcp:9999 tcp:9999"), help)
        assertFalse(help.contains("8099"), help)
    }

    @Test
    fun `cleartext is still named, because it is the most common cause`() {
        for (host in listOf(EMULATOR_HOST_ALIAS, DEVICE_LOOPBACK, "192.168.1.42")) {
            assertTrue(connectionHelp(host, 8099).contains("cleartext"), host)
        }
    }
}
