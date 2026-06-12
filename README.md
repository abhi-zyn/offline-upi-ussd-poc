# Offline UPI USSD Automation — Proof of Concept

A **personal-use** Android PoC that completes a UPI payment over Canara Bank's
`*99#` USSD service **without internet**, by driving the interactive USSD dialogs
programmatically. Native Kotlin, min SDK 26 (Android 8.0).

> ⚠️ This is an experiment for a device you fully control. It auto-types into your
> bank's USSD session and reads your SMS. The UPI PIN is **never stored** — you enter
> it for every payment.

## Why this architecture

Android's official `TelephonyManager.sendUssdRequest()` only reliably handles a
**single** request/response — it cannot navigate the multi-step `*99#` menu, and
`*99#` rejects pre-stuffed "concatenated" strings (`*99*1*...#`) by design. So the
engine is an **Accessibility-Service state machine**: it reads each USSD dialog,
decides the next input, types it, and taps Send — reacting screen-by-screen. The
UPI PIN is entered by you into a secure field for every payment.

## Build via GitHub Actions (no local setup)

This repo is meant to build the debug APK on GitHub's servers via
`.github/workflows/build-apk.yml`. On every push it builds the APK; open the
**Actions** tab → latest run → download the **`app-debug-apk`** artifact. You can
also trigger it manually (workflow_dispatch).

> NOTE: The API token used to push this repo could not write the workflow file
> (`.github/workflows/`) because it lacked the `workflow` scope. Add
> `.github/workflows/build-apk.yml` manually via the GitHub web UI (the contents
> are in the project README / chat) to enable the automated build.

## Build locally

- **Android Studio:** `File → Open` this folder; it syncs Gradle (and generates the
  wrapper jar) automatically. Plug in a phone and hit **Run**.
- **CLI:** `gradle wrapper` then `./gradlew assembleDebug` (needs a local Android SDK).

Output APK: `app/build/outputs/apk/debug/app-debug.apk`

## First-run steps (on the phone)

1. Tap **Grant permissions** (Phone + SMS).
2. Tap **Enable Accessibility service** → turn on *“Offline UPI USSD Automator”*.
3. Ensure `*99#` is registered for your Canara account and your SIM allows USSD
   (often needs 2G/3G; may not work on VoLTE-only).
4. Enter a recipient UPI ID + a tiny amount (e.g. ₹1) and tap **Pay via *99#**.
5. Enter your UPI PIN when prompted. Watch for the ✅ / ❌ result.

## You will almost certainly need to tune

- **`CanaraFlow.kt`** option numbers & keyword matchers — use `CANARA_FLOW_WORKSHEET.md` first.
- **`SmsConfirmationReceiver.kt`** regexes — match Canara's exact SMS wording.
- OEM differences: the USSD dialog package name and Send button label vary;
  `looksLikeUssdDialog()` and `clickSend()` may need adjusting for your phone.

## Out of scope (Phase 2)

QR scanner, GPay-like UI, payment history, multi-bank support, encrypted PIN +
biometric unlock, background/queued payments.
