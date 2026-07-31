# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The main Ahead Android app — used directly by the Type 1 diabetic. Reads CGM data (primarily via Health Connect, with a Nightscout-based fallback), evaluates it against a plateau/trend model, and drives alerts, notifications, voice alerts, emergency-contact escalation, and a printable doctor report. See `../CLAUDE.md` for how this fits with the backend, website, dashboard, and Ahead Lite.

Kotlin, single `app` module, namespace/applicationId `com.aheadt1d.app`, minSdk 28 / targetSdk 35 / compileSdk 35, JVM 17, AGP 9.2.1, Kotlin 2.2.10.

## Commands

Run from the repo root. On Windows use `.\gradlew.bat`; the examples below use the bash wrapper.

```bash
./gradlew assembleDebug          # build debug APK
./gradlew installDebug           # build + install on connected device/emulator
./gradlew test                   # run all JVM unit tests (app/src/test)
./gradlew testDebugUnitTest --tests "com.aheadt1d.app.alerts.CorrectionResponseMathTest"   # single test class
./gradlew lint                   # Android lint
```

Unit tests are plain JUnit4, JVM-only (no Robolectric/emulator needed) — they live in `app/src/test/java/com/aheadt1d/app/` and currently cover `alerts/CorrectionResponseMathTest`, `alerts/PlateauMathTest`, `report/AgpMetricsCalculatorTest`.

There's a `debug` source set (`app/src/debug/...`) that only compiles into debug builds — it adds `DebugMenuActivity`, `TuningActivity` (live-adjust the constants in `tuning/`), `DebugScenarios` + `DebugTrendInjector` (fake a glucose trend to test alert behavior without waiting on a real device), and `DebugInjection`. This is the fastest way to exercise alert logic end-to-end without needing to actually go low/high.

## Architecture

### Alert pipeline (the core of the app)

`notifications/GlucoseStatusService` is the foreground service that keeps checking glucose state; `work/GlucoseCheckWorker` (WorkManager, scheduled by `work/WorkScheduler` and backstopped by `work/AlarmScheduler` + `work/WatchdogAlarmReceiver` in case WorkManager itself gets killed) polls periodically. `work/GlucoseCheckRunner` does the actual read — it tries Health Connect (`health/HealthConnectManager`) first and falls back to `health/NightscoutFallbackClient` only on a null client, missing permission, or `RemoteException` — **not** on a successful-but-empty read, which matters when diagnosing "why didn't it fall back" incidents.

Glucose readings feed `notifications/GlucoseDisplayState`, a sealed state (`Stale`, in-range, high, low, etc.) that `alerts/AlertCoordinator` reads to decide what to fire. Two coordinators sit on top of raw state:

- **`alerts/PlateauCoordinator`** — detects "logged a correction but glucose isn't responding" using `alerts/PlateauMath` (high side) and `alerts/CorrectionResponseMath` (low side, added for symmetric low-correction tracking — separate window/threshold constants since lows need to resolve faster than highs). Branches on which direction the correction was logged in (`KEY_CORRECTION_DIRECTION` in prefs) to call the right evaluator.
- **`alerts/AlertCoordinator`** — handles the "no data at all" (stale/signal-lost) case. Re-fires the signal-lost alert on a cooldown (`SIGNAL_LOST_REALERT_COOLDOWN_MS`, 15 min) for as long as the blackout continues, rather than firing once and going silent.

`alerts/AlertNotifier` renders the actual notifications (colors/wording differ by high vs. low via an `isLow` param), `alerts/AlertChannels` defines the notification channels, `alerts/RedAlertActivity` is the full-screen emergency alert UI, `alerts/CheckNowSuppression` debounces the manual "Check now" button.

All the tunable numbers (thresholds, windows, rate cutoffs) live in `tuning/TuningPrefs` and `tuning/PlateauTuningPrefs` — backed by SharedPreferences with `coerceIn(...)` bounds, editable live from the debug-only `TuningActivity`.

### Emergency escalation

`emergency/` is a self-contained Room-backed (via `EmergencyDao`) contact list (`EmergencyContact`, `EmergencyContactsPrefs`, `EmergencyContactsActivity`) plus `EmergencyAlertScheduler` / `EmergencyAlertReceiver` / `EmergencyAlertRepository`, which schedule escalating alerts (e.g. `EmergencyAlertType.NO_DATA`) that page emergency contacts if the primary alert goes unacknowledged.

### Event tagging & reporting

`events/` is a Room database (`AppDatabase`, `UserEventDao`/`UserEventRepository`, entity `UserEvent`) for user-tagged events (meals, corrections, exercise) shown on the trend chart and editable from `EventHistoryActivity`/`EventEditHelper`/`EventLogDialogs`; `EventCsvExporter` exports them. `report/` builds the doctor-facing AGP (Ambulatory Glucose Profile) report: `ReportDataAggregator` + `AgpMetricsCalculator` compute stats, `GlucoseReportChartRenderer`/`InteractiveReportGenerator` render it, `DoctorReportPdfGenerator` produces the PDF from `ReportExportActivity`.

### Setup & chart rendering

`setup/SetupPrefs` holds the CGM source (`cgm_path`: Dexcom vs. Juggluco vs. unsure) and other first-run config from `SetupWizardActivity`; it's also user-editable post-setup from `MainActivity`'s footer (`showCgmPathDialog()`) since this value has been observed to silently regress and previously required a manual `adb shell` prefs edit to fix. `chart/` (`ChartDataSource`, `ChartGeometry`, `ChartRange`, `AxisTicks`, `GapSegmenter`, `SeverityColoring`, `EventMarkerLayout`) is custom MPAndroidChart rendering logic for the main trend chart — full pan/zoom/interaction, unlike the simplified static chart in `ahead-lite-android`.

`state/` holds cross-cutting app state: `AppForegroundTracker` (is the app in foreground, affects notification behavior), `LatestTrendRepository`/`LatestTrendStore` (caches the backend's trend-detection result), `DebugGlucoseOverride` (debug-only fake reading injection). `voice/` (`VoiceAlertEngine`, `VoiceAlertCategory`, `VoiceAlertPrefs`, `VoiceAlertsActivity`) is text-to-speech alerts, independently toggleable from push notifications. `network/BackendClient` talks to `ahead-backend`'s Railway deployment (`BuildConfig.BACKEND_BASE_URL`) — currently does not send the `X-Ahead-Api-Key`/`X-Ahead-Device-Id` headers the backend's auth middleware expects (see `../CLAUDE.md`).
