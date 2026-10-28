# Meteo Polska Widget

Android home-screen widget for Polish UM meteograms. It was derived from `MeteoTorunWidget` and expanded from a single hardcoded city to 18 Polish voivodeship cities.

## Current behavior

- Each widget instance can select its own city; Toruń is the default.
- `MeteoCaptureWorker` refreshes every 30 minutes and on manual refresh.
- `MeteogramFetcher` downloads a meteogram PNG and shared legend PNG from `old.meteo.pl`.
- The fetched meteogram is banner-cropped and white-trimmed. The legend is split into seven sections and arranged below the meteogram in two columns.
- `ImageStore` caches the composed bitmap, selected city, update timestamp, and refreshing state per app-widget ID.
- `MeteoWidgetProvider` renders the city, image, status, refresh button, and city-configuration action.
- `MainActivity` provides a sample preview, city selector, manual refresh, and `requestPinAppWidget` flow.
- `BootReceiver` restores periodic refresh after reboot.

## Source endpoints

The current implementation expects static images:

```text
https://old.meteo.pl/um/metco/mgram_pict.php?ntype=0u&row={row}&col={col}&lang=pl
https://old.meteo.pl/um/metco/leg_um_pl_cbase_256.png
```

City grid coordinates live in `app/src/main/java/org/kawk/meteopolska/City.kt`.

## Known issue observed on 2026-10-28

The meteogram endpoint returned a 540×660 “Poznaj nowe Meteo ICM” promotional PNG instead of the expected UM meteogram for Toruń (`col=209`, `row=383`). The app still renders correctly, but the live widget displays the promo image. The bundled `app/src/main/res/raw/sample_meteogram.png` shows the intended composed layout.

Before changing rendering code, verify whether `old.meteo.pl` temporarily replaced the image, started serving a different response to this user agent/IP, or moved the real meteogram endpoint.

## Project layout

```text
app/src/main/java/org/kawk/meteopolska/
  BootReceiver.kt          Reschedules work after reboot
  City.kt                  18-city model and meteogram grid coordinates
  CityConfigActivity.kt    Per-widget city selection
  Constants.kt             URLs, intervals, preference keys, intent actions
  ImageStore.kt            Per-widget bitmap/state persistence
  MainActivity.kt          Preview, city selector, refresh, widget pinning
  MeteoCaptureWorker.kt    WorkManager fetch/refresh job
  MeteogramFetcher.kt      Download, crop, trim, split, and compose images
  MeteoWidgetProvider.kt   RemoteViews rendering and widget actions
  Scheduler.kt             Periodic and one-shot WorkManager scheduling
app/src/main/res/raw/sample_meteogram.png
                             Bundled expected-layout sample image
```

## Build

Requirements:

- JDK 17
- Android SDK `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`
- Gradle wrapper or Gradle 8.9

```bash
./gradlew assembleDebug
```

Output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Emulator validation used on 2026-10-28

The debug APK built successfully and ran on an Android 15 emulator configured as a Pixel 10 Pro approximation (1280×2856, 495dpi). The app launched, the bundled sample rendered, and `requestPinAppWidget` placed a 4×5 widget on the home screen.

Useful commands for a future verification session:

```bash
sdkmanager "platform-tools" "platforms;android-34" \
  "build-tools;34.0.0" "emulator" \
  "system-images;android-35;google_apis;x86_64"

avdmanager create avd -n pixel10pro \
  -k "system-images;android-35;google_apis;x86_64" \
  -d pixel_7_pro

# Optional: patch ~/.android/avd/pixel10pro.avd/config.ini to
# hw.lcd.width=1280, hw.lcd.height=2856, hw.lcd.density=495.

emulator -avd pixel10pro -no-snapshot-save
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell monkey -p org.kawk.meteopolska \
  -c android.intent.category.LAUNCHER 1
adb exec-out screencap -p > screenshot.png
```

## Development handoff prompt

Use this as a compact starting prompt for the next session:

```text
Work on var0l/meteo_android, a Kotlin Android home-screen widget.
The app supports 18 Polish cities and caches a composed meteogram per
app-widget ID. Read README.md first. Verify whether the old.meteo.pl
meteogram endpoint still returns a Meteo ICM promo PNG; if so, find the
replacement/static endpoint or a robust parser/fallback before changing
layout logic. Preserve per-widget city selection and manual/30-minute
WorkManager refresh. Build with ./gradlew assembleDebug and validate on an
Android emulator sized like a Pixel 10 Pro when changing widget UI.
```

## Boundaries for future work

- Do not commit `local.properties`, `.gradle/`, `build/`, APKs, IDE files, or generated outputs.
- Keep source changes minimal and preserve current city coordinates unless the upstream source provides corrected values.
- Treat `sample_meteogram.png` as a required app resource, not a generated build artifact.
- If network behavior differs by region, record the exact response `Content-Type`, dimensions, redirect URL, and status code before assuming a code bug.
