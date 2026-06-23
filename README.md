# MultiCam Grid

A native Android app that **auto-detects every camera sensor on your phone** and tries to
show a live preview from **all of them at once** in a grid. It's a phone "test" tool: see how
many of your cameras (e.g. 3 rear + 1 front) the hardware can actually drive concurrently.

## What it does

- Enumerates every logical camera via the Camera2 API.
- Shows one grid tile per camera, each labeled with facing (front/back), lens type
  (ultrawide / wide / tele), focal length, and 35mm-equivalent.
- A top banner reports the total cameras found and which **combinations the hardware
  officially supports running concurrently** (`CameraManager.getConcurrentCameraIds()`,
  Android 11+), plus a live "Streaming X / N" counter.
- Attempts to open **all** cameras simultaneously (opens are staggered slightly). Any camera
  the hardware refuses shows a clear reason ("Device's concurrent camera limit reached",
  "Camera busy", etc.) with a **Retry** button — freeing one camera may let another open.

> **Reality check:** Most phones cannot stream every camera at the same time (limited image
> signal processors). This app is designed to reveal and explain that limit, not promise that
> all four will run together.

## Get the APK (no computer needed)

Every push to the dev branch triggers a GitHub Actions build (`.github/workflows/android.yml`)
that publishes a debug APK:

1. Go to the repo's **Releases** → **Latest debug build** (tag `latest-debug`).
2. On your phone, download `app-debug.apk`.
3. Open it and install (allow "install unknown apps" for your browser if prompted).
4. Launch the app and grant the camera permission.

You can also grab the APK from the workflow run's **Artifacts** (`multicam-debug-apk`), or
trigger a build manually via the Actions tab (**Build APK → Run workflow**).

## Build locally (optional)

Requires JDK 17+ and the Android SDK (platform 35).

```bash
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The Gradle wrapper (`gradlew`, `gradle/wrapper/gradle-wrapper.jar`) is committed, so no
system Gradle install is needed.

## Tech

Kotlin · Camera2 API · Views + ViewBinding · RecyclerView grid · minSdk 24 / target 35 ·
AGP 8.7 / Gradle 8.11 / Kotlin 2.0.
