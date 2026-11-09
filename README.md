# XiaoLink

Unofficial Android app for the **Xiaomi AI glasses (O95)**. Use the glasses without the official, China-only app.

> **Not affiliated with, endorsed by, or supported by Xiaomi.** The protocol was found by reverse engineering, for
> interoperability. Use at your own risk: an unexpected command can change device settings or void your warranty.

## Features

| Feature | Status |
| --- | --- |
| Bluetooth connection, WearAuthV2 authentication, encrypted session | Works |
| Battery and device status; glasses clock set from the phone on connect | Works |
| Photo / video capture from the phone, button capture events | Works |
| Gallery: thumbnails over Bluetooth, full-size sync over Wi-Fi Direct (never deletes from the glasses) | Works |
| Audio recordings: start/stop from the phone or the two-finger gesture, sync and play in Gallery → Recordings | Works |
| Gemini voice assistant: touch gesture → glasses microphone → Gemini Live → answer on the glasses speakers | Works |
| "What am I looking at?": Gemini takes a photo with the glasses camera | Works |
| Live streaming: the glasses push RTMP to any server (480p–1080p, 15–30 fps) | Works |
| Live preview on the phone (built-in RTMP receiver, video only) | Works |
| Bind reset glasses to your Xiaomi account without the official app | Works |
| Fetch the glasses key from Xiaomi cloud inside the app | Works |
| Glasses settings (camera, AI, audio, light) | Works |
| Gemini history, "save a photo" by voice | Works |
| Background connection and auto-reconnect | Works |

The full list is in the app: **More → Roadmap**.

## Requirements

- Android 7.0+ (Android 13+ for companion-device background reconnect). On Android 17+, allow local network access
  for the Wi-Fi gallery sync.
- Xiaomi O95 AI glasses and a Xiaomi account. The glasses key (`encrypt_key`) comes from Xiaomi cloud.
- Optional: a [Gemini API key](https://aistudio.google.com/apikey) for the AI assistant.

## Setup

1. Install the APK from [Releases](../../releases), or build it (see below).
2. Pair the glasses in Android Bluetooth settings.
3. Open the app. The setup wizard starts:
   1. **Sign in** with the Xiaomi account that owns the glasses. Your password goes only to Xiaomi
      (`account.xiaomi.com`) and is not stored. The app stores the session cookie on the phone.
      Or paste the hex key and the glasses Bluetooth address manually.
   2. **Select the glasses** from the paired devices, then tap **Connect**.
   - Reset or new glasses: use **Pair new glasses** in step 2. The app binds them to your account, fetches the new
     key and connects.
4. Optional: open the **Gemini** tab, tap **Set up** and enter your Gemini API key. Then use the glasses' assistant
   gesture to talk to Gemini.

The app has four tabs: **Device** (status and capture), **Gallery** (photos, videos, recordings), **Gemini** and
**More** (glasses settings, live stream, connection, developer tools).

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
- Live streams go only to the RTMP URL you enter, or to the phone itself for the preview.
- The debug log is written to `Android/data/wtf.mahdi.glassesconnect/files/log.txt`. Check it for personal data
  before you attach it to an issue.

## Legal

The code is original. No Xiaomi code or binaries are distributed. Protocol facts (message layouts, constants) are
documented for interoperability. "Xiaomi" is a trademark of Xiaomi Inc. and is used here only to name the compatible
device.

## License

[GPL-3.0](LICENSE)
