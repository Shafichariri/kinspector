# Decisions — devices and stream
Owned by `:inspector-stream`: how an app on an emulator, simulator or phone reaches the daemon. Imported by the `inspector-stream` and `sample` guides.

**A Compose Multiplatform iOS app aborts at launch without `CADisableMinimumFrameDurationOnPhone`
in its Info.plist.** `androidx.compose.ui.uikit.PlistSanityCheck.performIfNeeded` throws, the
Kotlin terminate handler calls abort, and the app dies before anything renders — the crash report
names the check, which is the only reason it takes minutes rather than hours. Only a hand-written
plist can get this wrong; the wizard templates include it. `sample/ios/iosApp/iosApp/Info.plist`
carries it with a comment, and `INTEGRATION.md` section 5 tells consumers.

**Android blocks cleartext by default; the daemon connection is cleartext.** `ws://10.0.2.2:8099`
fails silently on API 28+ without a debug-only `network_security_config.xml`. This is the most
common reason an Android app records nothing, and the integration guide claiming "no manifest
entries, no permissions" was wrong until it was corrected — see `docs/INTEGRATION.md` 6d.

**Emulator detection reads the board, not the marketing strings, and iOS decides at link time.**
The Android check used to look for `generic` in `Build.FINGERPRINT` and `Emulator` or
`Android SDK built for` in `Build.MODEL`. A current AVD reports
`google/sdk_gphone64_arm64/emu64a:14/...` and `sdk_gphone64_arm64` — none of those match — so it
answered "physical device" for every emulator session, and an archive here holds 18 of them. The
replacement is ordered sturdiest first: `Build.HARDWARE` (`goldfish`/`ranchu`), then `Build.BOARD`,
`Build.DEVICE`, `Build.PRODUCT`, and only last the model strings, which are kept but are no longer
load-bearing. `ro.kernel.qemu` would be the obvious signal and every AVD still sets it, but
`android.os.SystemProperties` is not in the SDK — verified against `android-36` — and
`Build.HARDWARE` is its public mirror, so there is nothing to gain from the hidden-API path.

Every emulator profile in `EmulatorDetectionTest` was transcribed from `adb shell getprop` on a
booted image rather than invented; add a real one when a new image family appears. The
physical-device profiles are the exception and the test says so — no phone has been attached.

The iOS side no longer asks whether `SIMULATOR_DEVICE_NAME` is in the environment. That was a
sound tell with an unfalsifiable half: nothing proves its absence means hardware, and nobody has
ever run this on an iPhone to find out. `IS_IOS_SIMULATOR` is `expect`/`actual` across
`iosArm64Main` and `iosSimulatorArm64Main` instead, so it is fixed at link time and cannot
disagree with where the process is running. The env var is still read, for the device *name* only.
Do not collapse the two actuals into one `iosMain` constant: removing either one must fail that
target's compile, which is the only thing keeping the fact structural.

**A physical Android device connects over `adb reverse`, and that is why the daemon's bind did not
have to move.** `adb reverse tcp:8099 tcp:8099` forwards the *device's* loopback to the same port
on the machine running adb, so the app dials `127.0.0.1` from inside the phone and the daemon goes
on listening on `127.0.0.1` on the host. Nothing is exposed to the network, so the security
boundary in `http/InspectorDaemon.kt` is untouched. Verified end to end on a booted emulator,
which speaks the same adb protocol as hardware: `127.0.0.1:8099` from inside the device was refused before the
forward, answered `HTTP/1.1 200 OK` from the real daemon after it, and was refused again once the
forward was removed — while `lsof` still showed the daemon bound to `127.0.0.1` alone and the
`10.0.2.2` path kept working.

**A physical iPhone connects over USB through usbmuxd, and the connection runs backwards.** There
is no `adb reverse` on iOS. There is usbmuxd — the service Xcode uses, on every Mac, nothing to
install — and it tunnels only one way: from the Mac *into* a port on the phone. So on a physical
iPhone `StreamSink` **accepts** instead of dialling: `UsbListenerTransport` listens on the phone's
own `127.0.0.1:<port>`, and `UsbBridge`, started by `inspector serve`, polls usbmuxd and dials that
port on every USB-attached phone. The daemon's bind did not move, and it does not know the leg
exists: the bridge hands each message to its ordinary `WS /ingest` on loopback, so a bridged app
runs through exactly the ingest path every other test covers.

Settled by a spike on hardware before a line of this was written, because the one thing documentation
could not answer was the thing everything rested on: **a usbmuxd connection does reach a listener
bound to the phone's loopback**, and arrives from `127.0.0.1`. Over the `Network` route it does not —
usbmuxd reaches the phone's LAN address there, which a loopback listener never sees — so the bridge
dials only `USB` entries. That is the right trade rather than a limitation to engineer around: it is
what keeps the listener unreachable from the phone's network.

The pieces, and why each is shaped the way it is:

- **The wire is the same `WireMsg` JSON, framed by `UsbFraming`** — a 4-byte big-endian length —
  rather than WebSocket, because no Ktor client engine can run a WebSocket over a socket it
  *accepted*. The codec lives in `:inspector-model` so the phone and the bridge run one copy.
  `MAX_FRAME_BYTES` is 64 MiB and a header above it drops the link: an HTTP client that dials the
  port by mistake sends `GET `, which reads as a 1.2 GB frame.
- **`listensForUsb(host)` picks the route**, true only on `iosArm64` (via the link-time
  `IS_IOS_SIMULATOR`) and only while the host is loopback — which `defaultDaemonHost()` returns. An
  app that names another host has its own route and keeps dialling it. No public API changed, so
  neither the noop twin nor either golden file did.
- **`StreamSink.transport` is an `internal var`** purely so `UsbListenerTransportTest` can drive the
  USB route on the JVM, where `listensForUsb` is never true. Internal members are name-mangled on
  the JVM, so `ApiSurface` drops it and the parity test is unchanged. Do not make it a constructor
  parameter: an `internal` constructor is JVM-public, and would land in the golden file.
- **The port is one number on both ends.** The app listens on `StreamSink`'s `port`, and the bridge
  dials the daemon's own port unless `--usb-port` says otherwise — the flag exists for running a
  second daemon beside your usual one, which is exactly how this was verified on hardware.
- **The bridge is started by `serve`, not by `InspectorDaemon`.** The daemon tests construct
  `InspectorDaemon` dozens of times; a bridge inside it would have them dialling whatever phone is
  plugged into the machine running the suite.
- **`Plist` must not fetch the DTD.** Every usbmuxd reply names Apple's DTD by URL, and a default
  JDK parser fetches it — a request to apple.com per message, from a daemon whose posture is
  loopback-only. External DTD loading and entity expansion are both off.

**The phone's loopback is the new trust boundary, and it is wider than the host's in one respect.**
Any process on the phone that can reach its loopback can connect to the listener — just as any
process on the Mac can reach the daemon. It would receive captured traffic and could send a
`SignRequest`, which makes a registered `ReplaySigner` answerable to it. That is the same class of
exposure the module already carries on every platform, and the module is debug-only by
construction; but a signer is the sharpest thing in it, and anyone widening this route should read
this paragraph first.

**`reuseAddress` on the listener is load-bearing, and only a Native test can see it.** Found on
hardware: kill the app mid-session, relaunch, and the new process got `EADDRINUSE` for about 25
seconds because the dead link sat in TIME_WAIT on the listening port — and the help message it
printed blamed "another app", wrong in the commonest case of all. A JVM regression test was written
first and **passed with the fix deleted**: the JDK enables `SO_REUSEADDR` on server sockets by
default. Kotlin/Native's sockets are raw POSIX, so `UsbListenerRebindTest` lives in `iosTest`,
where it fails with the phone's exact error without the fix. Do not move it back to `jvmTest`.

Widening the daemon's bind to reach iPhones is **still** the wrong answer, and now also an
unnecessary one: that is an authentication decision first and a transport one second, and the
archive holds unredacted credentials by default.

`defaultDaemonHost()` on Android is therefore `10.0.2.2` on an emulator and `127.0.0.1` on
hardware. It is the first caller to use the emulator detection for a *decision* rather than a
label, which is why fixing that detection came first: while it was wrong, every emulator would
have been sent to `127.0.0.1`.

**The connection-failure message is per-platform, and it names the address it actually tried.**
On a physical iPhone it describes the *listener* instead — nothing reaches `connectionHelp` there
merely because the cable is out, since the app just waits; what does is a port another app holds.
`connectionHelp` is `expect`/`actual` rather than one shared constant, because the remedies do not
travel: `adb reverse` and a cleartext exemption mean nothing on iOS, and on Android the address
that failed is the only thing separating "you forgot the forward" from "that alias reaches nothing
on a phone" — two failures that look identical from inside the app. Interpolating the host and
port is load-bearing, not cosmetic; `DaemonHostTest` fails if either is hardcoded, because advice
about an address the app never dialled sends someone to edit the wrong file.

**StreamSink reports why it failed.** Connection errors used to be swallowed by `runCatching`,
leaving "the web UI is empty" undiagnosable from inside the app. Each distinct reason is now
printed once (not per retry) and exposed as `lastError`. Keep it that way.
