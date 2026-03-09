# Boero Camera — Continuation Prompt

Use this document to resume development in a fresh conversation. Paste it in full as your first message, then attach any files you want modified.

---

## Project Overview

**Boero Camera** is an Android camera app (Kotlin, CameraX) developed for the Google Pixel 8 / Tensor G3 SoC. Its key differentiators:

- **Photos saved as WebP** (lossy quality 50–100 or lossless) to `Pictures/Boero Camera/`
- **Video recorded with hardware AV1 encoding** (Tensor G3 `c2.google.av1.encoder`) + AAC audio in **MP4 container** via `MediaCodec` + `MediaMuxer` — the first Android camera app to use the Tensor G3's AV1 encoder, which even Google's own Pixel Camera does not use
- Falls back to H.264/HEVC via CameraX `Recorder` on devices without AV1 hardware
- Full manual controls: flash, zoom, tap-to-focus, pinch-to-zoom, white balance, HDR/Night mode, ISO, shutter speed, exposure compensation, aspect ratio, focus mode, frame rate (24/30/60 fps), AV1 bitrate slider

## Package & App Identity

- **Package:** `com.boerocamera.app`
- **App name:** `Boero Camera`
- **Min SDK:** 26 | **Target SDK:** 34
- **Build tools:** AGP 8.3.0, Kotlin 1.9.22, Gradle 8.4
- **Java:** 21 (requires `java-21-openjdk-devel` on Fedora, `JAVA_HOME=/usr/lib/jvm/java-21-openjdk`)
- **Android SDK:** `~/Android/Sdk` — set in `local.properties` as `sdk.dir=/home/jboero/Android/Sdk`
- Gradle wrapper JAR sourced from `/usr/share/faust/android/gradle/wrapper/gradle-wrapper.jar`

## Project Structure

```
BoeraCamera/
└── PixelShift/                          ← project root (gradle root)
    ├── build.gradle                     ← AGP 8.3.0
    ├── settings.gradle                  ← includes ':app', rootProject.name = 'Boero Camera'
    ├── gradle.properties
    ├── gradlew / gradlew.bat
    ├── gradle/wrapper/gradle-wrapper.properties  ← Gradle 8.4
    └── app/
        ├── build.gradle                 ← namespace/appId = com.boerocamera.app
        └── src/main/
            ├── AndroidManifest.xml
            ├── java/com/boerocamera/app/
            │   ├── ui/
            │   │   ├── MainActivity.kt
            │   │   └── SettingsActivity.kt
            │   ├── viewmodel/
            │   │   └── CameraViewModel.kt
            │   └── utils/
            │       ├── Av1VideoHelper.kt
            │       ├── WebPImageSaver.kt
            │       └── CameraPreferences.kt
            └── res/
                ├── layout/
                │   ├── activity_main.xml
                │   └── activity_settings.xml
                ├── drawable/  (12 XML drawables + icons)
                └── values/
                    ├── colors.xml
                    ├── strings.xml
                    └── styles.xml
```

## Key Architecture Decisions

### AV1 Recording Pipeline
CameraX `Recorder` does **not** support AV1. `MediaRecorder.setVideoEncoder(14)` fails on API < 34.

**Pipeline:** Camera → two `Preview` use cases (one for viewfinder, one for encoder) → `MediaCodec` (`c2.google.av1.encoder`) + `AudioRecord` → `MediaCodec` (AAC) → `MediaMuxer` (MP4)

- **Container:** MP4 (`MUXER_OUTPUT_MPEG_4`) — Android `MediaMuxer` rejects AV1 in WebM (only VP8/VP9/Vorbis/Opus supported there)
- **Audio:** AAC — Opus is not supported by Android `MediaMuxer` in MP4
- **Bitrate:** VBR only — Tensor G3 AV1 encoder (`c2.google.av1.encoder`) supports CBR/VBR but **not CQ (Constant Quality)**
- **Dual Preview:** Two `Preview` use cases bound simultaneously — `av1Preview` feeds the viewfinder surface, `av1EncoderPreview` has its `SurfaceProvider` replaced with `session.inputSurface` (codec input) when recording starts. This prevents the viewfinder freezing during recording.

### MediaMuxer Threading
All muxer calls (`addTrack`, `start`, `writeSampleData`, `stop`) run on a single `av1-muxer` consumer thread. Encode threads post `MuxerCmd` objects to a `LinkedBlockingQueue`. Video track must be added before audio track (device quirk). Audio format is buffered in `pendingAudioFormat` until video format arrives.

### Orientation Fix
`cameraInfo.sensorRotationDegrees` (absolute rotation of sensor from device natural orientation) is passed to `muxer.setOrientationHint()` before `muxer.start()`. For Pixel 8 back camera = 90°, front = 270°. Do **not** use `getSensorRotationDegrees(displayRotation)` — that returns rotation relative to current display orientation and is 90° off.

### updateState Race Fix
`postValue()` from background threads was clobbering concurrent state updates. All state mutations use `mainHandler.post { _state.value = _state.value!!.copy(...) }` which reads fresh state at execution time on the main thread.

### Preview Freeze Fix
AV1 recording binds **two** `Preview` use cases in `bindCamera()`. The viewfinder preview's `SurfaceProvider` is never replaced. Only `av1EncoderPreview.setSurfaceProvider` is swapped to the codec input surface at record start.

### Save Notifications
**No Toast popups.** A small pill overlay (`saveIndicator` in top-right corner) fades in/out with a spinner and status text. Shows "Saving…" immediately on capture/stop, then "WebP saved" / "AV1 video saved" on completion. Errors shown in red without spinner.

### Translucent Controls During Recording
`bottomControls.animate().alpha(0.25f)` when recording starts, back to `1.0f` on stop. 300ms transition.

### FPS Setting
`Preview.Builder().setTargetFrameRate(Range(fps, fps))` applied to both preview use cases. Also passed to `MediaFormat.KEY_FRAME_RATE` in `Av1VideoHelper`. Options: 24/30/60 fps.

## SharedPreferences Keys (`"boerocamera_settings"`)

| Key | Type | Default | Notes |
|-----|------|---------|-------|
| `webp_quality` | Int | 90 | 50–100 |
| `lossless_webp` | Boolean | false | |
| `use_av1` | Boolean | true | |
| `video_quality` | String | "FHD" | SD/HD/FHD/UHD |
| `aspect_ratio` | Int | RATIO_4_3 | |
| `focus_mode` | String | "CONTINUOUS" | |
| `hdr` | Boolean | false | |
| `night_mode` | Boolean | false | |
| `iso` | Int | -1 | -1 = auto |
| `shutter` | Long | -1 | -1 = auto |
| `exposure_comp` | Int | 0 | |
| `av1_bitrate_mbps` | Int | 8 | 2–40 Mbps |
| `frame_rate` | Int | 30 | 24 / 30 / 60 |

## Build & Deploy (fish shell)

```fish
set SRC ~/Downloads/PixelShift/app/src/main/java/com/boerocamera/app
mv ~/Downloads/MainActivity.kt $SRC/ui/
mv ~/Downloads/SettingsActivity.kt $SRC/ui/
mv ~/Downloads/CameraViewModel.kt $SRC/viewmodel/
mv ~/Downloads/Av1VideoHelper.kt $SRC/utils/
mv ~/Downloads/activity_main.xml ~/Downloads/PixelShift/app/src/main/res/layout/
mv ~/Downloads/activity_settings.xml ~/Downloads/PixelShift/app/src/main/res/layout/

cd ~/Downloads/PixelShift
./gradlew assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk \
  && adb shell am start -n com.boerocamera.app/.ui.MainActivity

# Logcat
adb logcat -s BoeraCamera:I CameraViewModel:I Av1VideoHelper:I AndroidRuntime:E
```

## Known Limitations / Pending Work

- **AV1 pause/resume not supported** — `MediaCodec` has no built-in pause. Would require buffering or frame dropping.
- **Opus in MP4** — Not supported by Android `MediaMuxer`. Would require `mp4parser` library for manual MP4 box writing.
- **Thumbnail in capture row** — bottom-left thumbnail display not yet implemented.
- **Front camera + AV1** — works (sensorRotationDegrees = 270) but front camera mirroring in the output is not explicitly handled; players typically handle this.
- **4K AV1** — encoder supports it, but the UHD option in settings is there. Thermal throttling on Pixel 8 during long 4K recordings is a known hardware issue.

## Google Play Store Description (draft)

> **Boero Camera** is a professional camera app that makes the most of your Pixel 8's unique hardware capabilities.
>
> 📸 **Photos in WebP** — dramatically smaller files than JPEG at the same quality, saving space without compromise.
>
> 🎬 **AV1 Video Encoding** — the first camera app to harness the Pixel 8's dedicated AV1 hardware encoder, recording up to 40% smaller video files than H.264 at equivalent quality. Not even Google's own Pixel Camera uses this chip.
>
> ⚙️ **Full Manual Controls** — ISO, shutter speed, white balance, exposure compensation, focus mode, frame rate (24/30/60fps), and AV1 bitrate slider.
>
> Developed by Johnny Boero. Encoding assistance by Claude (Anthropic).

---

*This prompt was generated at the end of a development session. The full source is in the accompanying zip file `BoeraCamera.zip`.*
