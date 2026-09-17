package dev.inspector.ui

import java.util.TimeZone

/** Asked of the default zone at call time, so it is correct across DST for the current session. */
internal actual fun localUtcOffsetSeconds(): Int =
    TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
