# Verification and device acceptance

## Checks completed in this checkout

Environment: JDK 21, Gradle wrapper 9.3.1, Android Gradle Plugin 9.1.1, compile SDK 37, target SDK 35, min SDK 31. CI is configured for JDK 17.

Commands run successfully:

- `gradlew.bat --no-daemon -Pkotlin.compiler.execution.strategy=in-process clean testDebugUnitTest lintDebug assembleDebug`
- `gradlew.bat --no-daemon -Pkotlin.compiler.execution.strategy=in-process connectedDebugAndroidTest`

Results:

- **PASS** — 18 JVM unit tests; 0 failures and 0 errors.
- **PASS** — `lintDebug`; no lint errors.
- **PASS** — `assembleDebug`; APK metadata reports package `dev.bridge.assistant`, version `0.2.0` (version code 2), Chabi label, compile SDK 37, target SDK 35 and min SDK 31.
- **PASS** — 2 instrumentation tests on the Pixel 7 emulator running Android 15/API 35.
- **PASS** — Android 15 can install and run the instrumentation package built against SDK 37.
- **PASS** — Installed the final debug APK separately and launched `MainActivity` on the Android 15 emulator; it remained the resumed foreground activity.

The checked APK is `app/build/outputs/apk/debug/app-debug.apk`.

## Device acceptance still required

The available emulator image is Android 15/API 35. No Android 16 or API 37 emulator image and no Xiaomi/HyperOS device were available. Compilation against API 37 does not by itself prove runtime behavior on Android versions newer than 15. Validate on Android 16+ and API 37 when an image/device is available.

On Xiaomi/HyperOS, request assistant role, verify role is held before attempting wake activation. Run a local wake-only trial with screen unlocked and another with the screen locked; confirm whether VoiceInteractionService launches UI under the OEM's restrictions. Measure standby drain with wake disabled and enabled; check microphone app-ops and notification/stop behavior. Repeat ten normal phrases with varied noise to record false positives. Check that recognized wake immediately releases the foreground microphone before Android's speech recognizer begins.

Test screenshot lasso on a plain webpage, an image and a FLAG_SECURE screen. Ensure the mask matches the exact selected pixels, corner clipping remains in bounds, and screenshot submission always requires affirmative consent. After kill/restart, old screenshot content must not be shown.

Test Xiaomi Notes `ACTION_SEND` resolution on the user's exact installed package and version. Confirm that text is populated in a new note, not a previous existing note, and independently verify persistence by reopening Xiaomi Notes after the save. If no handler is exported, fall back to chooser and explicitly state that direct save is unsupported on this device. Do not mark this acceptance criterion passed without the on-device check.

These OEM and hardware checks are **NOT VERIFIED**; the emulator instrumentation results do not cover them.
