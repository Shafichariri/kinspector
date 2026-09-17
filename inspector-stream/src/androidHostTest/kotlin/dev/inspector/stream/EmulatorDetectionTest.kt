package dev.inspector.stream

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Profiles, not invented strings.
 *
 * Every emulator case below was read off a booted image with `adb shell getprop` and transcribed
 * field for field; `Build.HARDWARE`, `Build.BOARD` and the rest are the documented mirrors of the
 * `ro.*` properties named beside each one. The physical-device cases are the exception and say so:
 * no phone was attached to the machine that wrote this, so they are representative vendor values
 * rather than transcriptions. What they pin is the direction that matters for them — that nothing
 * in the rule set fires on a field a shipping phone actually carries.
 */
class EmulatorDetectionTest {

    @Test
    fun `the AVD that produced the mislabelled sessions is an emulator`() {
        // ro.* from a booted API 34 AVD. An archive on this machine holds 18 sessions from this
        // exact image recorded as android-device, which is the defect this test exists for.
        assertTrue(
            looksLikeEmulator(
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
    fun `the newest AVD image is an emulator`() {
        // ro.* from a booted API 37 AVD. Different device and product strings from the API 34
        // image above, which is the churn the old marketing-string match could not survive.
        assertTrue(
            looksLikeEmulator(
                hardware = "ranchu",
                board = "goldfish_arm64",
                device = "emu64a16k",
                product = "sdk_gphone16k_arm64",
                brand = "google",
                model = "sdk_gphone16k_arm64",
                fingerprint =
                    "google/sdk_gphone16k_arm64/emu64a16k:17/CP31.260623.012/16064790:user/dev-keys",
            ),
        )
    }

    @Test
    fun `the strings the old check looked for still identify an emulator`() {
        // An older AVD, the shape the replaced check was written against. Keeping it passing is
        // the whole reason the marketing-string rules were kept rather than deleted.
        assertTrue(
            looksLikeEmulator(
                hardware = "goldfish_x86",
                board = "goldfish_x86",
                device = "generic_x86",
                product = "sdk_google_phone_x86",
                brand = "generic_x86",
                model = "Android SDK built for x86",
                fingerprint =
                    "generic_x86/sdk_google_phone_x86/generic_x86:8.0.0/OSR1.170901.032/1:user/release-keys",
            ),
        )
    }

    @Test
    fun `hardware alone is enough`() {
        // Every other field blank, so this asserts the ro.hardware rule and nothing else. It is
        // the rule that is meant to hold when image branding changes again.
        assertTrue(
            looksLikeEmulator(
                hardware = "ranchu",
                board = "",
                device = "",
                product = "",
                brand = "",
                model = "",
                fingerprint = "",
            ),
        )
    }

    @Test
    fun `other virtual devices are emulators`() {
        // Cuttlefish, Android's own cloud emulator.
        assertTrue(
            looksLikeEmulator(
                hardware = "cutf_cvm",
                board = "cutf_cvm",
                device = "vsoc_arm64",
                product = "aosp_cf_arm64_phone",
                brand = "Android",
                model = "Cuttlefish arm64 phone",
                fingerprint =
                    "Android/aosp_cf_arm64_phone/vsoc_arm64:14/UPB5.230623.003/1:userdebug/test-keys",
            ),
        )
        // Genymotion, which is VirtualBox underneath.
        assertTrue(
            looksLikeEmulator(
                hardware = "vbox86",
                board = "unknown",
                device = "vbox86p",
                product = "vbox86p",
                brand = "generic",
                model = "Custom Phone - 11.0.0 - API 30 - 1080x1920",
                fingerprint =
                    "generic/vbox86p/vbox86p:11/RSR1.201013.001/6903271:userdebug/test-keys",
            ),
        )
    }

    @Test
    fun `a physical phone is not an emulator`() {
        // Representative vendor values, not transcribed from attached hardware — see the class
        // comment. A Pixel: ro.hardware is the SoC codename, never a QEMU board.
        assertFalse(
            looksLikeEmulator(
                hardware = "zuma",
                board = "zuma",
                device = "husky",
                product = "husky",
                brand = "google",
                model = "Pixel 8 Pro",
                fingerprint = "google/husky/husky:14/UD1A.230803.041/10808477:user/release-keys",
            ),
        )
        // A Samsung, whose ro.hardware is the vendor platform.
        assertFalse(
            looksLikeEmulator(
                hardware = "qcom",
                board = "kalama",
                device = "dm3q",
                product = "dm3qxxx",
                brand = "samsung",
                model = "SM-S918B",
                fingerprint =
                    "samsung/dm3qxxx/dm3q:14/UP1A.231005.007/S918BXXU4BWLB:user/release-keys",
            ),
        )
    }

    @Test
    fun `nothing known is not an emulator`() {
        // A JVM unit test against the stock android.jar sees null for every Build field. The
        // answer there is uninformative either way; what matters is that it is an answer.
        assertFalse(
            looksLikeEmulator(
                hardware = null,
                board = null,
                device = null,
                product = null,
                brand = null,
                model = null,
                fingerprint = null,
            ),
        )
    }
}
