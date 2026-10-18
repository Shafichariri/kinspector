# sample — agent guide

The reference app on three platforms: `desktop`, `android` and `ios` (an Xcode project linking
the `:sample:ios:shared` framework), all compiling `shared/kotlin`. It is a demo, not a tool, and
it swaps to the noop modules under `-Pinspector=off` exactly as a consuming app would.

Read before changing anything here: `docs/decisions/devices-and-stream.md`.

Also: `docs/decisions/twins-and-api.md` — the sample passes a `ReplaySigner` to keep that
parameter's noop parity honest at compile time.

@../docs/decisions/devices-and-stream.md

## Commands

```bash
./gradlew :sample:desktop:run -Dinspector.sample.autofire=true   # scripted traffic, no clicking
./gradlew :sample:desktop:run                     # runnable reference app
./gradlew :sample:android:installDebug            # the same app on a device or emulator
adb shell am start -n dev.inspector.sample/.MainActivity        # ... and launch it
xcodebuild -project sample/ios/iosApp/iosApp.xcodeproj -scheme iosApp \
  -sdk iphonesimulator -destination 'platform=iOS Simulator,name=iPhone 17 Pro Max' build
xcrun simctl install booted <path>/iosApp.app && xcrun simctl launch booted dev.inspector.sample
# ... on a physical iPhone over USB: sign it (the project ships unsigned), install, run `inspector serve`
xcodebuild -project sample/ios/iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS,id=<udid>' \
  -allowProvisioningUpdates CODE_SIGNING_ALLOWED=YES CODE_SIGN_STYLE=Automatic DEVELOPMENT_TEAM=<team> build
xcrun devicectl device install app --device <id> <path>/iosApp.app
```
