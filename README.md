# TravelGuard (Android)

TravelGuard is a calm, offline-capable Android passenger safety companion for bus, train, taxi, and private-vehicle journeys.

## Features
- **Wake Me Up (Offline Proximity Alert):** Triggers a repeating alarm (sound + vibration + persistent notification) when you enter a configurable `0.1 km – 10.0 km` radius of your destination. Works without internet once a destination is set.
- **Route Guard (Independent Deviation Alert):** Monitors your GPS position against a cached OSRM route polyline using sustained-sample confirmation (`0.2 km – 5.0 km` threshold). Disabling Route Guard never disables Wake Me Up.
- **Live Journey Dashboard:** Displays OpenStreetMap via `osmdroid`, live proximity circle, remaining route/direct distance, ETA, local arrival time, progress percentage, and GPS freshness.
- **Open in Google Maps:** Launches Google Maps directions (with browser fallback) from your latest valid GPS fix to your selected destination without interrupting background monitoring.
- **Material 3 Light & Dark Themes:** System-default, Light, and Dark themes with custom branding and SplashScreen API support.

## Supported Android Versions & Build Requirements
- **minSdk:** 26 (Android 8.0)
- **targetSdk / compileSdk:** 34 (Android 14)
- **JDK:** 17
- **Gradle:** 8.7 (AGP 8.4.2, Kotlin 1.9.24)

## Build & GitHub Actions
Upload the contents of this folder to the root of your GitHub repository. The included `.github/workflows/build-apk.yml` workflow will automatically:
1. Run `gradle --no-daemon testDebugUnitTest`
2. Run `gradle --no-daemon assembleDebug`
3. Publish `travelguard-debug-apk` (`app/build/outputs/apk/debug/app-debug.apk`) as a downloadable artifact.

You can also trigger a build manually anytime via **Actions > Build TravelGuard Android > Run workflow**.

## Permissions & Privacy
- **Location (`ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `FOREGROUND_SERVICE_LOCATION`):** Required for real-time proximity and route deviation monitoring while the app is backgrounded or when switching to Google Maps.
- **Notifications (`POST_NOTIFICATIONS`):** Required on Android 13+ for foreground monitoring and Wake Me Up alerts.
- **Local-only storage:** All destination, route, and alert data remain on-device. No analytics, ads, or location-tracking servers are used.

## Open-Source & Data Attributions
- **Maps:** [osmdroid](https://github.com/osmdroid/osmdroid) (Apache-2.0) & OpenStreetMap contributors (ODbL)
- **Geocoding:** OpenStreetMap Nominatim
- **Routing:** OSRM (BSD-2-Clause)

*Disclaimer: TravelGuard is a passenger convenience aid and is not a certified emergency or life-safety system. Android background execution varies by manufacturer battery optimization settings.*
