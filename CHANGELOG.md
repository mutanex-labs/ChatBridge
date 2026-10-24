# Changelog

## Unreleased

- Added a Gradle 9.3.1 wrapper pinned with the official distribution SHA-256 and changed CI to use it for clean, unit-test, lint and debug-APK tasks.
- Updated to AGP 9.1.1 and compile SDK 37 with built-in Kotlin, and added emulator manifest acceptance tests alongside pure JVM tests.
- Hardened exact contact matching, foreground microphone lifecycle, signature-protected wake broadcasts, screen-selection ownership and lasso completion/bounds handling.
- Hardened Sign in with ChatGPT callback registration checks, request bounds, refresh-token rotation handling and best-effort remote revocation. The app remains single-account.
- Updated the app identity to Chabi and fixed Responses API handling for incomplete results and portrait screenshots.

## 0.2.0 - 2026-10-23

Added optional local `Open GPT` recognition with Vosk and an explicit microphone foreground service, stop notification, SHA-256-checked model acquisition/import and app-scoped wake event to Android VoiceInteractionService. Added screenshot lasso masking and user-confirmed GPT submission. Added explicit Xiaomi Notes share target with local backup and chooser fallback. Added assistant role request, foreground wake pause/resume after commands, and parser unit tests. No Android build or hardware validation has yet been completed.

## 0.1.0 - 2026-10-23

Initial system assistant registration, typed/one-shot speech commands, contact calls, app launching, local notes, sign-in support and Responses API client.
