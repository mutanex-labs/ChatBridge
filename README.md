# GPT Android Bridge

Experimental Android 12+ assistant, version **0.2.0**, on Kotlin. Source release only; not yet compiled with Android SDK or tested on a device. It is not an official ChatGPT client, does not inherit the native app's conversations, and has no root requirement.

## Available source implementations

The app registers `VoiceInteractionService` and displays a `VoiceInteractionSession`. A session can receive a system-provided screenshot, display it inside `ScreenLasso`, accept a finger-drawn closed outline, mask pixels outside that outline and forward the cropped image to GPT **only after confirmation**. The source image is held in process memory, not written to media storage. `FLAG_SECURE`, Android policy, manufacturer implementations and secure apps may prevent screenshot access.

A manually enabled `WakeWordService` uses `AudioRecord` at 16-kHz/16-bit mono and local Vosk transcription to recognize `Open GPT` (and supported textual variants). When recognized, the service signals the current system assistant via an app-scoped broadcast, releases the microphone, and requests an Android assist session. That session opens the app's one-shot voice command recognizer. **Say the wake phrase and then the command after the interface starts listening.** The foreground service releases its microphone after wake detection and pauses while Android's speech recognizer handles the command. It rearms after ordinary commands; for a call it waits for the assistant activity to resume after returning from the dialer so that it does not compete with telephone audio. This is still unverified on HyperOS, and process termination can require manually enabling the service again. Continuous microphone processing has a significant energy and RAM cost and may be interrupted by HyperOS. This implementation is **not** a hardware hotword detector and has no measured false-accept or false-reject rates.

The wake model is not committed to the source repository. An explicit button downloads the official Vosk small English model (~40MB) over HTTPS, enforces a size limit, verifies the known SHA-256 and extracts it into app-private storage, with zip-slip protection and an uncompressed size limit. Alternatively the user can import the identical ZIP file from Documents. Speech data for wake detection remains on-device. The Android one-shot recognition service that handles commands **might be network-based**, depending on the device.

Commands still run locally: contacts and phone calling, app launching, and a local note backup. `Запиши в заметки ...` now tries an explicit `ACTION_SEND` intent addressed to `com.miui.notes` and falls back to the system sharing chooser if no public handler is available. **Sending an intent is not proof that Xiaomi Notes saved anything.** The user must review or save the opened note when required by their Notes version. There is no documented cross-version silent-create API, and this project does not attempt private content-provider access or high-privilege accessibility automation.

`Continue with ChatGPT` is the existing open-source plan-backed Sign in with ChatGPT flow with PKCE and local encrypted token storage. The code uses supported Responses API requests for text and optional user-approved screen images; Realtime audio is not covered. OAuth availability and supported plan models depend on current eligibility. Do not put ChatGPT passwords, tokens or API keys into the repository.

## Build and quick check

Import this directory into Android Studio with JDK 17, SDK 35 and Gradle 8.11.1. The Gradle wrapper is not included. Run `gradle testDebugUnitTest assembleDebug` or push to GitHub to run the included CI workflow, which also uploads a debug APK. **No successful Android compilation or Xiaomi device test has been performed here.** A passing CI build still does not prove that the assistant role, screen capture, Note intent and microphone background behavior work on HyperOS.

Install to Android 12+ and open the app. Use **Назначить GPT Bridge системным ассистентом** if your system exposes the assistant role. Grant microphone, contacts and phone permissions as needed. Click **Скачать офлайн-модель Vosk**, then **Включить Open GPT**. A persistent notification indicates that the foreground service is running and includes a stop button. Say "Open GPT", then after the command interface opens say "позвони маме". To select a screen region, invoke the system assistant normally, draw a closed region in the overlay and choose **Спросить GPT о выделенном**; confirm any image transfer. To test notes, type "запиши в заметки купить молоко" and check whether Xiaomi Notes offers a populated draft.

## Scope and privacy

Foreground audio capture is opt-in, visible through a persistent notification and temporarily paused after a successful wake event. The service does not silently start on boot. Android may refuse a microphone foreground service or delay background launch despite valid permissions. The screenshot is never automatically uploaded, and all processing on sensitive UI requires explicit consent. Phone calls are deterministic commands and are not initiated by a language-model tool call. If a contact or application is ambiguous, a local picker resolves it. The app's diagnostic logs never include recorded audio.

## References

OpenAI SIWC: https://developers.openai.com/siwc/token-sharing-open-source

Android VoiceInteractionSession: https://developer.android.com/reference/android/service/voice/VoiceInteractionSession

Android foreground microphone restrictions: https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start

Vosk models and license: https://alphacephei.com/vosk/models.html

Vosk Maven: https://central.sonatype.com/artifact/com.alphacephei/vosk-android/0.3.47

This project is not affiliated with, endorsed by or approved by OpenAI or Xiaomi.
