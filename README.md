# X Fold Duo Effect v0.6

Experimental no-root Android implementation of the iPhone Duo fold transition for vivo X Fold3 Pro / V2337A.

## What this version does

**Closing (inner → cover)**

- When the hinge starts closing, one frame of the inner screen (cached continuously via MediaProjection) is frozen into a full-screen overlay.
- The moving half (left by default, the half with the cover screen behind it) shows that frame through a fixed front-view projection: an eye ray from a viewer ~2.2 screen widths in front of the hinge is cast back onto the unfolded plane, so the picture appears to stay flat in space while the panel turns.
- Blur and darkening grow with fold progress and towards the panel's outer edge (same curves as the iPhone Duo study: `2 · motion · ((edge − 0.2) / 0.8)^1.35`). Wide blurs sample a box-filtered pyramid (1/4, 1/16, 1/64) so they stay smooth instead of ghosting.
- When the logical display switches to the cover panel, the cover shows the half of the frame it now lies over, aligned to its hinge-side edge, and resolves from blurred and dark to sharp. The frozen frame then cross-fades into the live cover layout, which is itself blurred by cross-window blur and sharpens at the same time.

**Opening (cover → inner)**

- When the inner panel takes over while the hinge is still partly closed, a window over the moving half blurs the live content behind it and darkens it towards the outer edge; both fade out as the hinge opens.

**Safety**

- Stopping halfway (tent / laptop posture) for ~1 s fades the overlay out; every effect phase also has an 8 s hard timeout.
- The overlay window exists on screen only while an effect runs.

## First run on the phone

1. Install the APK.
2. In vivo settings, enable continuing the current app on the cover screen after folding. Otherwise folding locks the phone and the lock screen covers the effect.
3. Open the app and grant **Display over other apps**.
4. Tap **Start Duo fold effect** and accept the full-display screen capture prompt.
5. Tap **Test once on the inner screen** to check the shader without folding.
6. Fully unfold, then slowly close. If something looks wrong, fold/unfold once and use **Share full diagnostic report**: it contains an event log of every phase change.

The app does not use root, Shizuku, accessibility, bootloader unlock, or system-file modification.

## Important limits

- The iPhone Duo cover screen lights up while the device is still folding. On the X Fold3 Pro the system turns the cover on only after closing, and third-party apps cannot light it early (the in-app WindowArea probe reports whether OriginOS exposes this; it normally reports UNSUPPORTED). The cover transition here therefore starts at the moment the cover turns on.
- The opening effect cannot use the fixed-view projection: the inner screen's new layout can only be captured together with the overlay itself, so it is blurred and darkened live instead.
- Only the inner screen's natural orientation is handled; folds started in landscape are skipped (and logged).
- Protected content (`FLAG_SECURE`), DRM video, some banking screens and the lock screen cannot be captured by MediaProjection.
- An application overlay cannot replace SurfaceFlinger/SystemUI transitions; system bars stay above it, and vivo's own fold animation may overlap.
- Cross-window blur may be disabled by the system (battery saver, some vendor builds); the diagnostic report says whether it is on. Without it, the frozen-frame part of the transitions still runs.

## Build

Open in Android Studio with Android SDK 35 installed, or push to GitHub: `.github/workflows/build-apk.yml` builds and uploads a debug APK automatically.

For a local build, restore the signing key first so installs stay upgradeable:

```sh
base64 -d -i ci/xfold-stable-debug.keystore.b64 -o app/xfold-stable-debug.keystore
gradle :app:assembleDebug
```
