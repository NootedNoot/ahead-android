/**
 * This package is two parallel alert-firing systems, not one - read this
 * before assuming a change to one covers the other.
 *
 * **The rate-based pipeline** ([AlertCoordinator]): reacts to the current
 * value/rate/projection on every reading. Owns the red/yellow severity
 * alerts and the signal-lost alert. Backed by its own SharedPreferences
 * file (`ahead_alert_state`). Rendered by [AlertNotifier], which also owns
 * the plateau/correction copy below - [AlertNotifier] is shared between both
 * pipelines, everything else in this package is not.
 *
 * **The episode-tracking pipeline** ([PlateauCoordinator]): reacts to
 * *duration* and *logged human intervention* - "has this been high too
 * long" (Gap 1, high-only) and "did a logged correction actually work"
 * (Gap 2, both directions). Backed by its own SharedPreferences file
 * (`ahead_plateau_state`) and its own tuning file (`PlateauTuningPrefs`,
 * separate from the rate pipeline's `TuningPrefs`). Its own pure-math
 * helpers ([PlateauMath], [CorrectionResponseMath]) live in this package too.
 *
 * They deliberately never share state, with exactly ONE narrow, documented
 * exception: [PlateauCoordinator.activeLowCorrectionAnchorMs] and
 * [PlateauCoordinator.activeHighCorrectionAnchorMs] are read-only accessors
 * [AlertCoordinator] calls so a logged correction can hold off a rate-based
 * re-alert for a while - see either function's doc for why low and high are
 * asymmetric there. [AlertCoordinator] never writes into
 * `ahead_plateau_state`, and [PlateauCoordinator]'s own escalation timing is
 * completely unaffected by who reads those two values.
 *
 * A genuinely new episode can be red-alert-worthy under one pipeline while
 * plateau-alert-worthy under the other AT THE SAME TIME, and both will fire
 * independently - that's intentional, not a bug to chase down. If something
 * in this package "feels like it should already know about" something in
 * the other pipeline, it very likely doesn't, on purpose - check the narrow
 * exception above before assuming a wire is missing.
 */
package com.aheadt1d.app.alerts
