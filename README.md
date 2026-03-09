# Boero Camera

An Android camera app for Google Pixel 8 that records video using hardware AV1 encoding and saves photos as WebP — the first camera app to use the Tensor G3's dedicated AV1 encoder block. Even Google's own Pixel Camera doesn't use it.

## Features

- 📸 **WebP photos** — lossy (quality 50–100) or lossless, saved to `Pictures/Boero Camera/`
- 🎬 **AV1 + AAC video** — hardware AV1 via `c2.google.av1.encoder`, AAC audio, MP4 container
- 📱 **H.264/HEVC fallback** — via CameraX Recorder on non-Tensor devices
- ⚙️ **Manual controls** — ISO, shutter, white balance, exposure compensation, focus mode
- 🎞️ **Frame rate** — 24 / 30 / 60 fps
- 📊 **AV1 bitrate slider** — 2–40 Mbps (VBR; Tensor G3 encoder supports CBR/VBR only, no CQ)
- 📐 **Aspect ratio** — 4:3 / 16:9

## Requirements

- Android API 26+ (Android 8.0)
- AV1 encoding: Pixel 8 / Tensor G3 or later
- Build: Java 21, AGP 8.3.0, Gradle 8.4

## Build

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.boerocamera.app/.ui.MainActivity
```

## Package

`com.boerocamera.app`

## Credits

Developed by **Johnny Boero**.
Coding architecture and implementation assistance by **Claude** (Anthropic), including the AV1 MediaCodec pipeline, dual-Preview viewfinder-during-recording solution, orientation hint fix, and threading model.

## License

MIT
