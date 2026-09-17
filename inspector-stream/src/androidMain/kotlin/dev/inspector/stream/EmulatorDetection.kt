package dev.inspector.stream

import android.os.Build

/**
 * Whether this process is running on an emulator, decided from public [Build] fields only.
 *
 * The obvious signal is `ro.kernel.qemu`, which every current AVD still sets to `1`. There is no
 * public accessor for it: `android.os.SystemProperties` is not in the SDK — checked against
 * `android-36`, where `javap` cannot find the class — so reading it means the hidden-API path,
 * which is not something a library should push onto a consumer. [Build.HARDWARE] is the public
 * mirror of `ro.hardware`, which the emulator kernel sets to `goldfish` or `ranchu`, so it carries
 * the same fact through a supported API.
 */
internal fun isAndroidEmulator(): Boolean = looksLikeEmulator(
    hardware = Build.HARDWARE,
    board = Build.BOARD,
    device = Build.DEVICE,
    product = Build.PRODUCT,
    brand = Build.BRAND,
    model = Build.MODEL,
    fingerprint = Build.FINGERPRINT,
)

/**
 * The decision itself, over the raw field values, so it can be tested against profiles read off
 * real images with `adb shell getprop` rather than only against whatever this machine is.
 *
 * Ordered sturdiest first: board identity, then image identity, and only last the marketing
 * strings. That order is the point of this function. The check it replaces looked for `generic`
 * in the fingerprint and `Emulator` or `Android SDK built for` in the model, and a current AVD
 * reports `google/sdk_gphone64_arm64/emu64a:14/...` and `sdk_gphone64_arm64` — none of which
 * match, which is why 18 archived sessions from one AVD are labelled `android-device`.
 *
 * Every argument is nullable because these are plain static fields: a JVM unit test running
 * against the stock `android.jar` sees null for all of them, and reporting "not an emulator"
 * there is better than throwing inside a debugging aid.
 */
internal fun looksLikeEmulator(
    hardware: String?,
    board: String?,
    device: String?,
    product: String?,
    brand: String?,
    model: String?,
    fingerprint: String?,
): Boolean {
    // ro.hardware. Set by the kernel, not by an image's branding, so it is the one field that
    // stays true as image names churn. goldfish is the original QEMU board and ranchu its
    // replacement; cutf is Cuttlefish, vbox86 is Genymotion, android_x86 is the BlueStacks family.
    if (hardware.hasPrefix("goldfish", "ranchu", "cutf", "vbox86", "android_x86")) return true

    // ro.product.board. Not redundant with the above: a current AVD reports ranchu for hardware
    // and goldfish_arm64 for board at the same time, so either alone can be the one that fires.
    if (board.hasPrefix("goldfish", "ranchu")) return true

    // ro.product.device. emu64a / emu64a16k on current AVDs, generic_x86 and generic_arm64 on
    // AOSP images, vsoc_ on Cuttlefish.
    if (device.hasPrefix("emu64", "generic", "vsoc_", "vbox86")) return true

    // ro.product.name. sdk_gphone64_arm64 and sdk_gphone16k_arm64 today, google_sdk and
    // sdk_phone_* historically, aosp_cf_ on Cuttlefish.
    if (product.hasPrefix("sdk", "google_sdk", "aosp_cf", "vbox86", "emulator")) return true

    // An AOSP image carries no vendor name in either field.
    if (brand.hasPrefix("generic")) return true
    if (fingerprint.hasPrefix("generic", "unknown")) return true

    // Last, and deliberately: these are the strings the previous check relied on. They still
    // identify some images, so they are kept — but they are the ones that went stale, so nothing
    // else depends on them firing.
    if (model.hasSubstring("emulator", "android sdk built for", "sdk_gphone")) return true

    return false
}

private fun String?.hasPrefix(vararg prefixes: String): Boolean {
    val value = this?.lowercase() ?: return false
    return prefixes.any { value.startsWith(it) }
}

private fun String?.hasSubstring(vararg needles: String): Boolean {
    val value = this?.lowercase() ?: return false
    return needles.any { value.contains(it) }
}
