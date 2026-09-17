package dev.inspector.ui

import platform.Foundation.NSTimeZone
import platform.Foundation.localTimeZone
import platform.Foundation.secondsFromGMT

/**
 * `secondsFromGMT` already accounts for daylight saving on the current date.
 *
 * Imported explicitly: it comes from the `NSExtendedTimeZone` category, which the Kotlin/Native
 * Foundation bindings surface as a package-level extension property rather than as a member.
 */
internal actual fun localUtcOffsetSeconds(): Int = NSTimeZone.localTimeZone.secondsFromGMT.toInt()
