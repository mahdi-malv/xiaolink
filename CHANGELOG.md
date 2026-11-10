# Changelog

All notable changes to XiaoLink are listed here. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

## [0.1.0] - 2026-11-10

First public release.

### Added

- **Connection**: Bluetooth connection to Xiaomi O95 AI glasses with WearAuthV2 authentication and an encrypted session. Runs in the background and reconnects automatically. On Android 13+, it reconnects through the companion device service.
- **Setup wizard**: sign in with a Xiaomi account to get the glasses key from Xiaomi cloud, or enter the key and Bluetooth address manually. Select the glasses and connect.
- **Pair new glasses**: bind reset or new glasses to your Xiaomi account without the official app.
- **Device tab**: battery and device status. The glasses clock is set from the phone on connect.
- **Capture**: take photos and videos from the phone. Capture events from the glasses button are shown in the app.
- **Gallery**: thumbnails over Bluetooth and full-size sync over Wi-Fi Direct. The app never deletes media from the glasses.
- **Audio recordings**: start and stop from the phone or with the two-finger gesture. Sync and play them in Gallery → Recordings.
- **Gemini assistant**: use the touch gesture to talk to Gemini Live through the glasses microphone. Answers play on the glasses speakers. Includes conversation history and "save a photo" by voice.
- **"What am I looking at?"**: Gemini takes a photo with the glasses camera and answers questions about it.
- **Live streaming**: the glasses push RTMP to any server (480p–1080p, 15–30 fps).
- **Live preview**: watch the glasses camera on the phone with the built-in RTMP receiver (video only).
- **Glasses settings**: camera, AI, audio and light.
- **Developer tools**: log, raw commands and an in-app roadmap (More → Roadmap).

[Unreleased]: https://github.com/mahdi-malv/xiaolink/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/mahdi-malv/xiaolink/releases/tag/v0.1.0
