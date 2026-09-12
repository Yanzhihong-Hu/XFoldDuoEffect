# X Fold Duo Effect v0.2

Experimental no-root Android implementation for vivo X Fold3 Pro / V2337A.

## What this version does

- Reads Android hinge-angle sensor (`Sensor.TYPE_HINGE_ANGLE`, type 36), with a small vivo/vendor fallback probe.
- Uses Android MediaProjection to capture the current display locally.
- Uses a full-screen `TYPE_APPLICATION_OVERLAY` that does not receive touch input.
- When folding starts, freezes one current-screen frame and renders it with a GPU `RuntimeShader`:
  - fixed-hinge projection on the moving half,
  - progressive spatial blur,
  - distance-based darkening,
  - cover-screen handoff after the display aspect ratio changes.
- Includes a manual test animation and a shareable sensor report.

## First run on the phone

1. Install the APK.
2. Open the app and grant **Display over other apps**.
3. Tap **Start Duo fold effect**.
4. Accept the Android full-display screen capture prompt.
5. Keep the foreground service running.
6. Fully unfold the X Fold3 Pro, then slowly close it.

The app does not use root, Shizuku, accessibility, bootloader unlock, or system-file modification.

## Important limits

- Protected/secure content (`FLAG_SECURE`), DRM video, some banking screens, and lock-screen content cannot be captured by MediaProjection.
- Android application overlays cannot replace SurfaceFlinger/SystemUI transitions. This is a user-space approximation layered above apps.
- Whether continuous angle input works depends on OriginOS exposing type 36 (or a discoverable vendor sensor) to third-party apps.
- v0.2 focuses on **closing: inner display -> cover display**. Reverse/opening animation is not yet tuned.

## Build

Open in Android Studio with Android SDK 35 installed, or push this folder to GitHub: `.github/workflows/build-apk.yml` builds and uploads a debug APK automatically.
