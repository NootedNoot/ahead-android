# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The main Ahead Android app — used directly by the Type 1 diabetic. Reads CGM data via Health Connect, evaluates it against a plateau/trend model, and drives alerts, notifications, voice alerts, and a printable doctor report. See `../CLAUDE.md` for how this fits with the backend, website, dashboard, and Ahead Lite.

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

`docs/investigations/` holds dated logs from ad-hoc device/pipeline investigations (e.g. Health Connect gap monitoring) that are worth keeping for reference but don't belong in code comments — glucose values in these logs are redacted before commit; only timing/gap/source data is kept, since the values themselves aren't relevant to what's being diagnosed.

Unit tests live in `app/src/test/java/com/aheadt1d/app/`. Most are plain JUnit4, JVM-only (`alerts/CorrectionResponseMathTest`, `alerts/PlateauMathTest`, `alerts/CriticalLowMathTest`, `report/AgpMetricsCalculatorTest`) but the alert-firing/notification-posting ones need Android framework classes and use Robolectric instead (`@RunWith(RobolectricTestRunner::class)`, `@Config(sdk = [34])`) — `alerts/AlertCoordinatorTest`, `alerts/PlateauCoordinatorTest`, `alerts/CriticalLowSirenTest`. Both run via the same `testDebugUnitTest` task.

There's a `debug` source set (`app/src/debug/...`) that only compiles into debug builds — it adds `DebugMenuActivity`, `TuningActivity` (live-adjust the constants in `tuning/`), `DebugScenarios` + `DebugTrendInjector` (fake a glucose trend to test alert behavior without waiting on a real device), and `DebugInjection`. This is the fastest way to exercise alert logic end-to-end without needing to actually go low/high.

## Architecture

### Alert pipeline (the core of the app)

`notifications/GlucoseStatusService` is the foreground service that keeps checking glucose state; `work/GlucoseCheckWorker` (WorkManager, scheduled by `work/WorkScheduler` and backstopped by `work/AlarmScheduler` + `work/WatchdogAlarmReceiver` in case WorkManager itself gets killed) polls periodically. `work/GlucoseCheckRunner` does the actual read — Health Connect (`health/HealthConnectManager`) is the **only** source.

**`HealthConnectManager.calculateRatePerMinute`/`calculateDelta` dedupe near-duplicate points before computing anything** (`collapseDuplicateWrites`, 2026-08-03). Real incident: Juggluco and `ahead-ble` were both writing the same G7's readings into Health Connect at once (see the workspace `CLAUDE.md` — Juggluco is now retired specifically because of this), and Health Connect doesn't dedupe across writer apps. The two most-recent points in a query window sometimes shared the exact same timestamp, which made the rate math divide by a zero-second gap and correctly bail out to null — but the *visible* symptom was the dashboard showing "rate unknown" and no projection despite a live, continuous connection, which looked like a bug in the math rather than upstream data duplication. `collapseDuplicateWrites` merges consecutive points that share a value within a 90-second window before the two-point diff runs, since a real G7 never reports two different values that close together — it can only ever merge genuine duplicates, never two real consecutive samples. If a rate-looks-wrong report comes in again, check `records.groupingBy { it.metadata.dataOrigin.packageName }` (logged in debug builds from `readGlucosePointsInRange`) before assuming the math itself is wrong — a second writer app is the more likely culprit now that it's happened once. There used to be a Nightscout-web fallback (`health/NightscoutFallbackClient`, read from the same endpoint `ahead-dashboard`'s viewer reads) that stepped in when HC was unreadable; **removed 2026-08-01** because that endpoint had shown a real 30+-min-stale reading with no staleness check before the value fed straight into the raw-reading repository and on into the backend's RED/YELLOW classification — a silently stale substitute could suppress a real alert or fire a false one. When HC can't be read (missing client, missing permission, or a transient `RemoteException`), `readPoints` now just returns `null` and lets the existing stale/signal-lost state (`GlucoseDisplayState.Stale`, `AlertCoordinator.handleStale`) surface instead. `MainActivity`'s chart had the same fallback for the same reason (it fed the identical repository via `syncRawReadingToRepository`, unconditionally, before its own `isFresh()` display gate) and was removed the same way. `ahead-dashboard` itself is untouched and still reads Nightscout directly as its own independent view — this only removed it as an automatic input to the phone app's alert pipeline.

Glucose readings feed `notifications/GlucoseDisplayState`, a sealed state (`Stale`, in-range, high, low, etc.) that `alerts/AlertCoordinator` reads to decide what to fire. Two coordinators sit on top of raw state:

- **`alerts/PlateauCoordinator`** — detects "logged a correction but glucose isn't responding" using `alerts/PlateauMath` (high side) and `alerts/CorrectionResponseMath` (low side, added for symmetric low-correction tracking — separate window/threshold constants since lows need to resolve faster than highs). Branches on which direction the correction was logged in (`KEY_CORRECTION_DIRECTION` in prefs) to call the right evaluator.
- **`alerts/AlertCoordinator`** — handles the "no data at all" (stale/signal-lost) case. Re-fires the signal-lost alert on a cooldown (`SIGNAL_LOST_REALERT_COOLDOWN_MS`, 15 min) for as long as the blackout continues, rather than firing once and going silent.

`alerts/AlertNotifier` renders the actual notifications (colors/wording differ by high vs. low via an `isLow` param), `alerts/AlertChannels` defines the notification channels, `alerts/CheckNowSuppression` debounces the manual "Check now" button. Alert sound is `alerts/AlertTones` — synthesized (not sourced) tones played directly via `MediaPlayer` rather than a channel's own sound attribute, with register/direction/repetition encoding low-vs-high and calm-vs-urgent.

**REMOVED 2026-08-20, at the owner's explicit request:** the full-screen lockout takeover (`RedAlertActivity`), the forced-volume critical-low siren (`CriticalLowSiren`), and the entire emergency-contact auto-text escalation system (`emergency/`) are all gone — reported as more of a headache (an alarm that couldn't be dismissed) than a help for what the owner actually needs. Red and yellow severity both still fire as ordinary notifications (see `AlertNotifier`) with voice alerts (kept, separately toggleable) — just nothing that locks the screen, forces alarm-stream volume, or pages a third party. The dismiss-cooldown machinery, `DebugAlertPrefs`, and the `VoiceAlertCategory.EMERGENCY` voice category that existed only to serve the takeover/siren went with it too — there's nothing left to protect or ungate.

**Sound is tier-dependent, and the differences are deliberate, not drift:**
- *Yellow* — tone (via `AlertTones`) plus the channel's own fallback sound, two-pulse vibration, DND-respecting.
- *Red* — **no tone at all, in either branch** (2026-08-01, at the owner's request: across several lows in one day the repeated alarm-stream tone became punishing). Red is now one ordinary DND-bypassing notification tier with two copy variants gated on `recovering: Boolean` (`AlertNotifier.showRedAlert`) — "still low, rising" (calmer) vs. "URGENT... check now" — vibration + voice only, no siren, no screen takeover. The RED voice category **bypasses the voice master toggle** (`VoiceAlertEngine.UNGATED_CATEGORIES`) — otherwise removing the tone would silently reduce red to vibration only whenever that toggle happened to be off, which it was. Its channel is `setSound(null, null)` but still `setBypassDnd(true)` with a vibration pattern: no sound is not no interruption.

Channel IDs are versioned (`SOUND_SCHEME_VERSION`) because Android channels are immutable once created and delete+recreate with the same ID silently resurrects the old settings. The version gate is shared across red and yellow, so bumping it re-migrates both even when only one changed.

#### Alarm fatigue: the "recovery just stopped" instant re-fire needs a floor

`AlertCoordinator.fireRedIfWarranted` (low side): if a value was rising (recovering) and then flattens or reverses, that's treated as new information worth an immediate alert, bypassing the normal cooldown. 2026-08-01: this had **no minimum time floor** — a rate hovering right around zero (real physiological noise during a slow, sticky low, not sensor error) could flip the recovering flag every single check cycle and re-fire every cycle with it. Reported directly by the owner as alarm fatigue during a real sticky low: many more red alerts than the actual rate of change justified, to the point of "more focused on shutting up my phone than trying to wait and see if my low goes up."

Fix: `MIN_REALERT_GAP_MS` (5 min). The instant "recovery just stopped" re-fire now only fires if at least that long has passed since the last actual alert; below that floor, the state tracking still updates (so a later stall still gets flagged as new once the floor clears) but nothing re-interrupts. Brand-new episodes (`forceFire`) are never gated by this — only the sign-flip re-fire is. Test: `AlertCoordinatorTest`'s `... within MIN_REALERT_GAP_MS` / `... once MIN_REALERT_GAP_MS has passed` pair.

#### Post-hypo recovery grace window

`POST_HYPO_RECOVERY_GRACE_WINDOW_MS` (40 min): for 40 minutes after a treated low (≤80 mg/dL), intentional fast rises (+2.5, +3.5 mg/dL/min) and expected rebound spikes stay completely silent unless glucose breaches `RECOVERY_REBOUND_CEILING_MGDL` (240) — a normal post-treatment rebound shouldn't re-trigger a high-side alert on its own.

All the tunable numbers (thresholds, windows, rate cutoffs) live in `tuning/TuningPrefs` and `tuning/PlateauTuningPrefs` — backed by SharedPreferences with `coerceIn(...)` bounds, editable live from the debug-only `TuningActivity`.

#### Threshold constants are duplicated on purpose — keep them in sync by hand

There is no single shared constants file for the app's several 70 mg/dL-adjacent thresholds — `AlertCoordinator.LOW_HIGH_SPLIT` (now shared with `AlertNotifier` via `AlertThresholds.kt`, see below), the user-tunable `DEFAULT_RED_LOW` (TuningPrefs), and `DEFAULT_LOW_THRESHOLD` (PlateauTuningPrefs) are three independently-declared constants that all happen to be 70 today. These are *not* all the same concept — some are raw current-value checks, some are **15-minute**-projected checks evaluated backend-side (`projected`; `projectedExtended` is the 30-min one), some are tuning defaults meant to be independently adjustable — so unifying them naively would be wrong. But it does mean **changing one low/high threshold requires deliberately auditing the others**, and a mismatch here is silent and safety-relevant rather than a compile error. (2026-08-26: the yellow-projected-low threshold is a proven case of this actually drifting — see `TuningPrefs`' own comment.)

**One exception, fixed 2026-08-26**: `isLowSide(value, projected)` itself — the actual boolean function, not just the 70 mg/dL number — used to be hand-copied verbatim in both `AlertCoordinator` and `AlertNotifier`. It's now one shared function in `alerts/AlertThresholds.kt`, imported by both. The *other* threshold constants above remain deliberately independent (different concepts); this was specifically two files reimplementing the identical formula with no compiler link between them, which was worth collapsing.

One trap worth knowing before touching this area: low-vs-high classification must consider `projected`, not just the current value. A reading of 79 can already be red because it's projected to crash through 70 within 15 min; classifying that by raw value alone would route it down the high-side path (real bug, fixed 2026-08-01 — this predates and is unrelated to the removed emergency-contact system mentioned above, but was caught in the same area of code). `isLowSide` checks `value <= 70 || projected <= 70`.

`ahead-lite-android` keeps its own separate `GlucoseSeverity` thresholds (`RED_LOW`, `YELLOW_LOW`, `RED_HIGH`) that must stay semantically consistent with this app's, across repo boundaries, with nothing enforcing it.

#### High-side red alerts: flat cooldown + correction-aware grace, no peak tracking

The high side of `AlertCoordinator.fireRedIfWarranted` is deliberately simple: a flat `RED_HIGH_REALERT_COOLDOWN_MS` (45 min) heartbeat, plus (2026-08-26) a correction-aware grace layered on top — logging a correction holds off a re-alert for `HIGH_CORRECTION_GRACE_MS` (90 min, **rolling**: each additional correction during a multi-hour high pushes it forward again) as long as the value isn't still climbing. The low side's equivalent grace (`LOW_CORRECTION_GRACE_MS`, 30 min, **fixed** to the first correction, not extended by repeats) is intentionally asymmetric — a low needs to resolve fast; a high can legitimately be managed over hours with several doses. Both read `PlateauCoordinator`'s correction-tracking state through two narrow, read-only accessors — see that class's own doc for the boundary reasoning.

An earlier version of this file described a more elaborate high-side "local peak/re-arm" tracker here. That function was fully implemented but never actually called anywhere in the codebase — confirmed dead code, removed 2026-08-26 rather than wired in, since the flat cooldown + correction grace already cover what came up in real use.

Also fixed the same day: a yellow↔red **flap** (dipping to yellow for a cycle or two right at the boundary, then back to red) used to be treated as a "brand new episode" and bypass the 45-minute cooldown entirely — real reported case, two alerts 14 minutes apart. Only a genuinely fresh episode (entering red from being in-range) force-fires now; a flap falls through to the ordinary cooldown. The mirror-image bug also existed on the way *down*: a **red→yellow downgrade** while actively falling (IOB working) still force-alerted if the value was numerically ≥240, with no regard for direction — also fixed 2026-08-26 (`fireYellowIfWarranted`'s `improvingFromRed` check). Low side is intentionally unchanged in both cases — a fluctuating low re-entering red still always alerts immediately, since that's the direction where staying quiet is the dangerous default.

### Event tagging & reporting

`events/` is a Room database (`AppDatabase`, `UserEventDao`/`UserEventRepository`, entity `UserEvent`) for user-tagged events (meals, corrections, exercise) shown on the trend chart and editable from `EventHistoryActivity`/`EventEditHelper`/`EventLogDialogs`; `EventCsvExporter` exports them. `report/` builds the doctor-facing AGP (Ambulatory Glucose Profile) report: `ReportDataAggregator` + `AgpMetricsCalculator` compute stats, `GlucoseReportChartRenderer`/`InteractiveReportGenerator` render it, `DoctorReportPdfGenerator` produces the PDF from `ReportExportActivity`.

### Setup & chart rendering

`setup/SetupPrefs` holds the CGM source (`cgm_path`: Dexcom vs. Juggluco vs. unsure) and other first-run config from `SetupWizardActivity`; it's also user-editable post-setup from `MainActivity`'s footer (`showCgmPathDialog()`) since this value has been observed to silently regress and previously required a manual `adb shell` prefs edit to fix. `chart/` (`ChartDataSource`, `ChartGeometry`, `ChartRange`, `AxisTicks`, `GapSegmenter`, `SeverityColoring`, `EventMarkerLayout`) is custom MPAndroidChart rendering logic for the main trend chart — full pan/zoom/interaction, unlike the simplified static chart in `ahead-lite-android`.

`state/` holds cross-cutting app state: `AppForegroundTracker` (is the app in foreground, affects notification behavior), `LatestTrendRepository`/`LatestTrendStore` (caches the backend's trend-detection result), `DebugGlucoseOverride` (debug-only fake reading injection). `voice/` (`VoiceAlertEngine`, `VoiceAlertCategory`, `VoiceAlertPrefs`, `VoiceAlertsActivity`) is text-to-speech alerts, independently toggleable from push notifications. `network/BackendClient` talks to `ahead-backend`'s Railway deployment (`BuildConfig.BACKEND_BASE_URL`) — currently does not send the `X-Ahead-Api-Key`/`X-Ahead-Device-Id` headers the backend's auth middleware expects (see `../CLAUDE.md`).
