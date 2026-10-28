# Meteo Polska Widget

Android home-screen widget for Polish UM meteograms. It was derived from `MeteoTorunWidget` and expanded from a single hardcoded city to 18 Polish voivodeship cities.

## Current behavior

- Each widget instance can select its own city; Toruń is the default.
- `MeteoCaptureWorker` refreshes every 30 minutes and on manual refresh.
- `MeteogramFetcher` downloads hourly UM 4 km point-forecast data (60 h) from the ICM meteogram API and draws a meteogram bitmap: temperature/dew point, precipitation/humidity, sea-level pressure, wind/gusts/direction, and cloud layers, in local time.
- `ImageStore` caches the composed bitmap, selected city, update timestamp, and refreshing state per app-widget ID.
- `MeteoWidgetProvider` renders the city, image, status, refresh button, and city-configuration action.
- `MainActivity` provides a sample preview, city selector, manual refresh, and `requestPinAppWidget` flow.
- `BootReceiver` restores periodic refresh after reboot.

## Source endpoints

The legacy `old.meteo.pl/um/metco/mgram_pict.php` image endpoint was retired (since 2026-10 it only returns a 540×660 "Poznaj nowe Meteo ICM" promo PNG). The app now uses the same JSON API as the official Meteo ICM app (`app.meteo.pl`):

```text
GET  https://devmgramapi.meteo.pl/meteorograms/available
     -> {"um4_60": [run epoch seconds...], "um4_120": [...], "gfs": [...]}
POST https://devmgramapi.meteo.pl/meteorograms/um4_60
     {"point": {"lat": "53.0138", "lon": "18.5984"}, "date": <latest run>}
     -> {"data": {"airtmp_point": {"first_timestamp", "interval", "data": [...]}, ...}}
```

Values below -1e6 (e.g. `-327276480`) mean "no value" (typically cloud base with no cloud). The host name contains `dev` but is what the production web app calls; if it disappears, check `https://app.meteo.pl/main.dart.js` for the current `/meteorograms/` base URL.

City coordinates (lat/lon) live in `app/src/main/java/org/kawk/meteopolska/City.kt`.

## Project layout

```text
app/src/main/java/org/kawk/meteopolska/
  BootReceiver.kt          Reschedules work after reboot
  City.kt                  18-city model with lat/lon
  CityConfigActivity.kt    Per-widget city selection
  Constants.kt             URLs, intervals, preference keys, intent actions
  ImageStore.kt            Per-widget bitmap/state persistence
  MainActivity.kt          Preview, city selector, refresh, widget pinning
  MeteoCaptureWorker.kt    WorkManager fetch/refresh job
  MeteogramFetcher.kt      ICM API download and meteogram rendering
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
app-widget ID and renders it from the ICM meteogram JSON API
(devmgramapi.meteo.pl, um4_60). Read README.md first. Preserve per-widget city selection and manual/30-minute
WorkManager refresh. Build with ./gradlew assembleDebug and validate on an
Android emulator sized like a Pixel 10 Pro when changing widget UI.
```

## Boundaries for future work

- Do not commit `local.properties`, `.gradle/`, `build/`, APKs, IDE files, or generated outputs.
- Keep source changes minimal and preserve current city coordinates unless the upstream source provides corrected values.
- Treat `sample_meteogram.png` as a required app resource, not a generated build artifact.
- If network behavior differs by region, record the exact response `Content-Type`, dimensions, redirect URL, and status code before assuming a code bug.
