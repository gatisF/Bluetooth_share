# Bluetooth Share

Android app for playing audio on several Bluetooth headphones/speakers at once.

**Download:** grab `BluetoothShare.apk` from the [Latest build](../../releases/tag/latest) release
(built automatically by GitHub Actions on every push to `main`).

## How it works (and the limits)

Android does not let a regular app stream to multiple Bluetooth devices by itself:

| Path | Multiple devices? | Who can use it |
|---|---|---|
| Classic Bluetooth (A2DP) | One active device | Everyone |
| Samsung **Dual audio** | Two classic devices | Samsung phones, built into the media output panel |
| **LE Audio / Auracast** broadcast ("Audio sharing") | Many LE Audio devices | Android 13+ hardware with broadcast support (e.g. Pixel 8+, Galaxy S23+ on Android 15/16). The broadcast API is system-only, so apps go through the system UI |

So the app is a hub around the system feature:

1. **Capability check**: shows whether your phone supports LE Audio and Auracast broadcast, and what to use.
2. **Connected devices**: lists connected A2DP, LE Audio and hearing-aid devices, and shows when an Auracast broadcast is active.
3. **One-tap sharing**: opens the system output picker (Android 14+), the Audio sharing settings, or Bluetooth settings.
4. **Background player**: plays local audio files with a media notification; output follows whatever devices the system is sharing to.

If your phone has neither Dual audio nor Auracast, the realistic options are speakers with a party/chain mode
(JBL PartyBoost or Auracast, Sony Party Connect, UE PartyUp) or a dual-link Bluetooth transmitter.

## Build locally

```
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk (signed with the debug key)
```

Requires JDK 17 and the Android SDK (compileSdk 35). minSdk 26 (Android 8).
