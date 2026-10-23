# GPT Android Bridge

Experimental, Android 12+ (minSdk 31) system assistant shell for local commands and ChatGPT plan-backed AI requests. **Prototype, not a released or device-validated application.** No root, no remote backend, and no access to the official ChatGPT app session. Repository initially private; choose an OSI-approved license and verify compliance with OpenAI terms before open-source release.

## What is implemented

The Android `VoiceInteractionService` registers a candidate system assistant, with a `VoiceInteractionSession` that can receive a system-provided screenshot and open the command screen. The command screen accepts typed commands or one-shot voice transcription using an Android-recognizer installed on the device. Deterministic local commands are matched before invoking any remote AI inference. `Позвони маме` searches accessible phone contacts and starts a call if `CALL_PHONE` permission is granted; ambiguous matches open a picker, and if direct-call permission is missing it opens the dialer. `Открой Telegram` launches a visible matching app. `Запиши в заметки ...` saves locally and offers Android Share; it does **not** directly write into Xiaomi Notes. The screenshot, if Android supplies one, is sent only when the user presses the screen-analysis button and explicitly confirms. Protected windows and OEM restrictions can make screenshots unavailable.

**Sign in with ChatGPT** uses the documented open-source flow: loopback callback on `127.0.0.1`, dynamic client registration, host ID, PKCE S256, state/nonce, RSA-signed ID token verification against OpenAI JWKS, grant-scope checking, encrypted tokens in Android Keystore-backed local storage, and refresh-token rotation. The model list is read from the account's `/v1/models`. GPT requests use Responses API `store:false` and `stream:true`, consume response text deltas until `response.completed`, and optionally include a user-approved screenshot. This is not connected to ChatGPT conversations or memory. OAuth or AI availability is conditional on account eligibility, consent, policy and usage limits.

## Not implemented

Always-listening `Open GPT` hotword, low-power hardware hotword detection, background calling without first opening the assistant UI, native Circle to Search-style lasso, direct Xiaomi Notes adapter, continuous streaming speech or Realtime API, model-controlled tool calling, secure multi-user account picker, automated OEM-device validation, production credential recovery, and signed releases. SIWC plan usage does not cover Realtime audio. Voice transcription depends on whichever Android speech recognition service is available and may use its own network service.

## Development

Open the root folder in Android Studio with Android SDK 35, JDK 17, Gradle 8.11.1 and an internet connection for Gradle dependencies. A Gradle wrapper is not bundled yet; install Gradle 8.11.1 or generate its wrapper using `gradle wrapper --gradle-version 8.11.1` before running `./gradlew`. Build with `gradle testDebugUnitTest assembleDebug`. The GitHub Actions workflow uses pinned Gradle to compile and upload a debug APK on successful checks. There is no prebuilt APK in this source archive.

Install a debug build on an Android 12+ device; open the app, grant the needed individual permissions, and test typed local commands first. For ChatGPT requests, tap **Continue with ChatGPT**, complete login and plan-sharing consent in your system browser, choose a model, and submit a prompt. For system assistant invocation, choose GPT Bridge in Android Settings → Apps → Default apps → Digital assistant; precise menu names vary by OEM. The system invocation panel currently has an **Open voice and actions** button. Testing on Xiaomi HyperOS is required before asserting compatibility.

## Boundaries

Never send contact data, screenshots, or foreground-app content to OpenAI without explicit user action. Phone calls are a direct local command, not an action an LLM may invoke. Keep OAuth tokens out of logs, crash reports, and repository history; tokens are stored encrypted locally. `CALL_PHONE`, `READ_CONTACTS` and `RECORD_AUDIO` are requested at runtime and can be denied. All external actions should be idempotent where possible and must handle ambiguous targets. Do not use an accessibility service to bypass screenshot protection or app-specific access controls. Android and OpenAI APIs can change.

## Sources

OpenAI Sign in with ChatGPT: https://developers.openai.com/siwc/token-sharing-open-source

OpenAI registration: https://developers.openai.com/siwc/token-sharing-open-source/sign-in

OpenAI inference: https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference

OpenAI preview limitations: https://developers.openai.com/siwc/token-sharing-open-source/preview-limitations

Android VoiceInteractionSession: https://developer.android.com/reference/android/service/voice/VoiceInteractionSession

This is a learning/prototype project and is not affiliated with, approved by, or endorsed by OpenAI.
