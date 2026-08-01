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

Unit tests live in `app/src/test/java/com/aheadt1d/app/`. Most are plain JUnit4, JVM-only (`alerts/CorrectionResponseMathTest`, `alerts/PlateauMathTest`, `alerts/CriticalLowMathTest`, `report/AgpMetricsCalculatorTest`) but the alert-firing/notification-posting ones need Android framework classes and use Robolectric instead (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`) — `alerts/AlertCoordinatorTest`, `alerts/PlateauCoordinatorTest`, `alerts/CriticalLowSirenTest`. Both run via the same `testDebugUnitTest` task.

There's a `debug` source set (`app/src/debug/...`) that only compiles into debug builds — it adds `DebugMenuActivity`, `TuningActivity` (live-adjust the constants in `tuning/`), `DebugScenarios` + `DebugTrendInjector` (fake a glucose trend to test alert behavior without waiting on a real device), and `DebugInjection`. This is the fastest way to exercise alert logic end-to-end without needing to actually go low/high.

## Architecture

### Alert pipeline (the core of the app)

`notifications/GlucoseStatusService` is the foreground service that keeps checking glucose state; `work/GlucoseCheckWorker` (WorkManager, scheduled by `work/WorkScheduler` and backstopped by `work/AlarmScheduler` + `work/WatchdogAlarmReceiver` in case WorkManager itself gets killed) polls periodically. `work/GlucoseCheckRunner` does the actual read — it tries Health Connect (`health/HealthConnectManager`) first and falls back to `health/NightscoutFallbackClient` only on a null client, missing permission, or `RemoteException` — **not** on a successful-but-empty read, which matters when diagnosing "why didn't it fall back" incidents.

Glucose readings feed `notifications/GlucoseDisplayState`, a sealed state (`Stale`, in-range, high, low, etc.) that `alerts/AlertCoordinator` reads to decide what to fire. Two coordinators sit on top of raw state:

- **`alerts/PlateauCoordinator`** — detects "logged a correction but glucose isn't responding" using `alerts/PlateauMath` (high side) and `alerts/CorrectionResponseMath` (low side, added for symmetric low-correction tracking — separate window/threshold constants since lows need to resolve faster than highs). Branches on which direction the correction was logged in (`KEY_CORRECTION_DIRECTION` in prefs) to call the right evaluator.
- **`alerts/AlertCoordinator`** — handles the "no data at all" (stale/signal-lost) case. Re-fires the signal-lost alert on a cooldown (`SIGNAL_LOST_REALERT_COOLDOWN_MS`, 15 min) for as long as the blackout continues, rather than firing once and going silent.

`alerts/AlertNotifier` renders the actual notifications (colors/wording differ by high vs. low via an `isLow` param), `alerts/AlertChannels` defines the notification channels, `alerts/RedAlertActivity` is the full-screen emergency alert UI, `alerts/CheckNowSuppression` debounces the manual "Check now" button. Alert sound is `alerts/AlertTones` — synthesized (not sourced) tones played directly via `MediaPlayer` rather than a channel's own sound attribute, with register/direction/repetition encoding low-vs-high and calm-vs-urgent.

**Sound is tier-dependent and the differences are deliberate, not drift:**
- *Yellow and below* — tone (via `AlertTones`) plus the channel's own fallback sound, two-pulse vibration, DND-respecting.
- *Red* — **no tone at all** (2026-08-01, at the owner's request: across several lows in one day the repeated alarm-stream tone became punishing). Red is vibration + voice, and because of that the RED voice category now **bypasses the voice master toggle** (`VoiceAlertEngine.UNGATED_CATEGORIES`) — otherwise removing the tone would silently reduce red to vibration only whenever that toggle happened to be off, which it was. Its channel is `setSound(null, null)` but still `setBypassDnd(true)` with a vibration pattern: no sound is not no interruption.
- *Critical-low siren* — always loud, forced alarm-stream volume, looping ringtone. Unaffected by the above on purpose; it is the last-resort tier.

Channel IDs are versioned (`SOUND_SCHEME_VERSION`) because Android channels are immutable once created and delete+recreate with the same ID silently resurrects the old settings. The version gate is shared across red and yellow, so bumping it re-migrates both even when only one changed.

`alerts/CriticalLowSiren` is a critical-low emergency system, deliberately independent of AlertCoordinator end-to-end (own notification channel/id, own SharedPreferences, own AlarmManager chain) — built after a real incident where the normal red alert's notification posted correctly but its sound/vibration silently didn't, because channel-mediated audio depends on a fragile OS permission. It fires sound/vibration directly (`USAGE_ALARM` audio attributes + raw `Vibrator` calls, both DND-exempt without needing notification-policy access) and has two bands, decided by `alerts/CriticalLowMath`:

- **tanking** — opens at ≤73 mg/dL while falling at least 1.0 mg/dL/min (a flat or rising value in that range opens nothing). Once open it pings on a *descending ladder* — `TANKING_RUNGS = 73/70/67/63`, each rung once, downward only — plus a "still not resolved" heartbeat every 10 min, plus an immediate ping whenever recovery stalls or reverses. The owner's framing for the ladder: a stepped low-battery warning, but for glucose; one ping on the way down is a single point of failure for someone with hypoglycemia unawareness. Same delivery as the emergency band, minus the continuous loop and the forced takeover.
- **emergency** — ≤55 (`DEFAULT_FLOOR`), repeats every ~25–60s until dismissed or recovered, full-screen takeover, plus its own tighter 10-min emergency-contact timer (`CriticalLowEmergencyScheduler`/`Receiver`, separate from the ordinary 15-min one below so the two can never cross-cancel each other).

Both bands end only at `RECOVERY_THRESHOLD` (**75**, matching `AlertCoordinator.LOW_RED_CLEAR_HYSTERESIS`) or an explicit acknowledgment. Climbing to 58 is not resolution, so neither the ladder nor the contact timer resets on the way up. An episode escalates/de-escalates between bands mid-flight as it crosses 55.

Two non-obvious invariants here, both from bugs found in the 2026-08-01 audit — break either and the failure is silent:
- **Acknowledgment is per-tier.** Dismissing a *tanking* warning must never suppress a later *emergency* siren (`KEY_ACKNOWLEDGED_BAND`). It previously did, which meant dismissing at 65 silenced the siren all the way down. Dismissing the emergency band *does* suppress re-fire at the same tier, deliberately.
- **The emergency-contact timer arms once per episode** (`KEY_CONTACT_TIMER_ARMED`), never per band crossing. A low oscillating across 55 would otherwise reset the clock forever and never actually reach a human.

`alerts/DebugAlertPrefs` (lives in `main`, not `debug`, since production code reads it) backs a debug-menu toggle that skips only the full-screen takeover while leaving sound/vibration/voice/notification untouched, for testing without a forced lockout.

All the tunable numbers (thresholds, windows, rate cutoffs) live in `tuning/TuningPrefs` and `tuning/PlateauTuningPrefs` — backed by SharedPreferences with `coerceIn(...)` bounds, editable live from the debug-only `TuningActivity`.

#### Threshold constants are duplicated on purpose — keep them in sync by hand

There is no single shared constants file. The same 70 mg/dL low/high boundary is independently redeclared as `LOW_HIGH_SPLIT` in `AlertCoordinator`, `AlertNotifier`, and `RedAlertActivity`, and again as the user-tunable `DEFAULT_RED_LOW` (TuningPrefs) / `DEFAULT_LOW_THRESHOLD` (PlateauTuningPrefs). These are *not* all the same concept — some are raw current-value checks, some are **15-minute**-projected checks evaluated backend-side (`projected`; `projectedExtended` is the 30-min one), some are recovery thresholds — so unifying them naively would be wrong. But it does mean **changing one low/high threshold requires deliberately auditing the others**, and a mismatch here is silent and safety-relevant rather than a compile error.

The siren's own numbers are separate again and must not be conflated with the above: `CriticalLowMath.DEFAULT_FLOOR` (55) and `RECOVERY_THRESHOLD` (75) are a distinct, more severe tier, and 75 is chosen to match `AlertCoordinator.LOW_RED_CLEAR_HYSTERESIS` rather than the 70 split. `CriticalLowMathTest` pins the ladder's relationship to both bounds (every rung must sit strictly between the floor and the recovery threshold) — that test exists because a rung at 70 was once unreachable dead code.

Two related traps worth knowing before touching this area:
- Low-vs-high classification must consider `projected`, not just the current value. A reading of 79 can already be red because it's projected to crash through 70 within 30 min; classifying that by raw value alone routed it down the high-side path and armed an emergency-contact text saying the person was HIGH while they were actually crashing low (real bug, fixed 2026-08-01). All three `isLowSide` copies now check `value <= 70 || projected <= 70`.
- `ahead-lite-android` keeps its own separate `GlucoseSeverity` thresholds (`RED_LOW`, `YELLOW_LOW`, `RED_HIGH`) that must stay semantically consistent with this app's, across repo boundaries, with nothing enforcing it.

### Emergency escalation

`emergency/` is a self-contained Room-backed (via `EmergencyDao`) contact list (`EmergencyContact`, `EmergencyContactsPrefs`, `EmergencyContactsActivity`) plus `EmergencyAlertScheduler` / `EmergencyAlertReceiver` / `EmergencyAlertRepository`, which schedule escalating alerts (e.g. `EmergencyAlertType.NO_DATA`) that page emergency contacts if the primary alert goes unacknowledged (default 15 min, `EmergencyContactsPrefs.alertTimeoutMinutes`).

`CriticalLowSiren` deliberately does **not** reuse this scheduler for its emergency band — it has its own parallel `alerts/CriticalLowEmergencyScheduler`/`Receiver` with its own prefs file and request code, so the ordinary red path resolving can never silently cancel the critical-low timer or vice versa. Its tanking band, by contrast, arms *no* contact timer at all, because a tanking reading is also a low-side red as far as `AlertCoordinator` is concerned and already got the standard 15-min timer from there — a second timer for the same episode would be a cross-contamination risk, not extra safety.

**Testing caution:** this path sends real SMS to real people. Before exercising any emergency-escalation flow on a physical device, check whether `EmergencyContactsPrefs.isEnabled` is true and consider `adb shell pm revoke com.aheadt1d.app android.permission.SEND_SMS` first (re-grant with `pm grant` after). This has already come close to firing a live text to a real contact during testing.

### Event tagging & reporting

`events/` is a Room database (`AppDatabase`, `UserEventDao`/`UserEventRepository`, entity `UserEvent`) for user-tagged events (meals, corrections, exercise) shown on the trend chart and editable from `EventHistoryActivity`/`EventEditHelper`/`EventLogDialogs`; `EventCsvExporter` exports them. `report/` builds the doctor-facing AGP (Ambulatory Glucose Profile) report: `ReportDataAggregator` + `AgpMetricsCalculator` compute stats, `GlucoseReportChartRenderer`/`InteractiveReportGenerator` render it, `DoctorReportPdfGenerator` produces the PDF from `ReportExportActivity`.

### Setup & chart rendering

`setup/SetupPrefs` holds the CGM source (`cgm_path`: Dexcom vs. Juggluco vs. unsure) and other first-run config from `SetupWizardActivity`; it's also user-editable post-setup from `MainActivity`'s footer (`showCgmPathDialog()`) since this value has been observed to silently regress and previously required a manual `adb shell` prefs edit to fix. `chart/` (`ChartDataSource`, `ChartGeometry`, `ChartRange`, `AxisTicks`, `GapSegmenter`, `SeverityColoring`, `EventMarkerLayout`) is custom MPAndroidChart rendering logic for the main trend chart — full pan/zoom/interaction, unlike the simplified static chart in `ahead-lite-android`.

`state/` holds cross-cutting app state: `AppForegroundTracker` (is the app in foreground, affects notification behavior), `LatestTrendRepository`/`LatestTrendStore` (caches the backend's trend-detection result), `DebugGlucoseOverride` (debug-only fake reading injection). `voice/` (`VoiceAlertEngine`, `VoiceAlertCategory`, `VoiceAlertPrefs`, `VoiceAlertsActivity`) is text-to-speech alerts, independently toggleable from push notifications. `network/BackendClient` talks to `ahead-backend`'s Railway deployment (`BuildConfig.BACKEND_BASE_URL`) — currently does not send the `X-Ahead-Api-Key`/`X-Ahead-Device-Id` headers the backend's auth middleware expects (see `../CLAUDE.md`).
