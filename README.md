# Electric Sheep Camera — v0.2.0

A creative camera app with Electric Sheep fractal overlays, real-time effects, and AI-powered features.

## What's new in v0.2.0

| Feature | Details |
|---|---|
| 🔄 Front/Back toggle | Instantly swap cameras; effects adapt to either |
| 🎙 Audio-reactive mode | Mic input drives overlay intensity & glitch pulse in real time |
| 📹 Video recording | Saves MP4 to `Movies/ElectricSheepCamera` with audio |
| 👁 Face tracking | ML Kit on-device detection — glowing halos + crosshairs on faces |

## Modes
- **Dream** — soft cyan tones, subtle scanlines
- **Neural** — green tones, tighter scanlines
- **Glitch** — pink/magenta, heavy blocks, most reactive to audio

## Build
1. Unzip and open the `electric-sheep-camera/` folder in Android Studio Hedgehog or later
2. Let Gradle sync (~2 min first time, downloads ML Kit model automatically)
3. Plug in a device running Android 9+ (API 28)
4. Grant Camera, Microphone permissions when prompted
5. **Build → Build Bundle(s) / APK(s) → Build APK(s)**
6. APK lands at `app/build/outputs/apk/debug/app-debug.apk`

## Permissions
- `CAMERA` — live preview, photo, video
- `RECORD_AUDIO` — audio-reactive effects + video audio track
- `WRITE_EXTERNAL_STORAGE` — Android 9 only, handled automatically on Android 10+
