# XiaoLink

Unofficial Android app for the **Xiaomi AI glasses (O95)**. Use the glasses without the official, China-only app.

> **Not affiliated with, endorsed by, or supported by Xiaomi.** The protocol was found by reverse engineering, for
> interoperability. Use at your own risk: an unexpected command can change device settings or void your warranty.

## Features

| Feature | Status |
| --- | --- |
| Bluetooth connection, WearAuthV2 authentication, encrypted session | Works |
| Battery and device status | Works |
| Photo / video capture from the phone, button capture events | Works |
| Thumbnails over Bluetooth | Works |
| Full-size photo/video sync to the gallery over Wi-Fi Direct (never deletes from the glasses) | Works |
| Gemini voice assistant: touch gesture → glasses microphone → Gemini Live → answer on the glasses speakers | Works |
| "What am I looking at?": Gemini takes a photo with the glasses camera | Works |
| Fetch the glasses key from Xiaomi cloud inside the app | Untested |
| Bind reset glasses without the official app | Untested |
| Glasses settings (read/write) | Untested |
| Background connection and auto-reconnect | Untested |

The in-app **TODO** tab has the full list.

## Requirements

- Android 7.0+ (Android 13+ for companion-device background reconnect).
- Xiaomi O95 AI glasses, bound to a Xiaomi account. The glasses key (`encrypt_key`) comes from Xiaomi cloud.
- Optional: a [Gemini API key](https://aistudio.google.com/apikey) for the AI assistant.

## Setup

1. Install the APK from [Releases](../../releases), or build it (see below).
2. Pair the glasses in Android Bluetooth settings.
3. In the app, **Connect** tab: select the glasses, then get the key:
   - **Fetch key**: sign in with the Xiaomi account that owns the glasses. Your password goes only to Xiaomi
     (`account.xiaomi.com`) and is not stored. The app stores the session cookie on the phone.
4. Tap **Connect**.
5. Optional: enter your Gemini API key on the Gemini tab. Use the glasses' assistant gesture, or tap **Ask Gemini**.

## Build

```sh
./gradlew assembleDebug        # app/build/outputs/apk/debug/
./gradlew testDebugUnitTest
```

Optional `local.properties` keys (debug builds only, never in release):

```properties
gemini.apiKey=...            # default key for debug builds
gemini.model=...             # Gemini Live model
gemini.visionModel=...       # model for photo questions
```

## Privacy

- No analytics or telemetry. The app talks only to the glasses, Xiaomi cloud (key fetch / bind), and Google Gemini
  (only when you use the assistant: microphone audio and photos you ask about).
- The debug log is written to `Android/data/wtf.mahdi.glassesconnect/files/log.txt`. Check it for personal data
  before you attach it to an issue.

## Legal

The code is original. No Xiaomi code or binaries are distributed. Protocol facts (message layouts, constants) are
documented for interoperability. "Xiaomi" is a trademark of Xiaomi Inc. and is used here only to name the compatible
device.

## License

[GPL-3.0](LICENSE)
