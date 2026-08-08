# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app that interrupts short-video doomscrolling. Ten minutes of *active scrolling* on
a Reels/Shorts/TikTok feed triggers a 10-second countdown, then a full-screen block on **that
one app's feed** for 5 minutes. Messages, the rest of the app, and every other app stay usable.
There is no daily time limit — enforcement reacts to behaviour, not the clock.

GPL-3.0. Distributed by sideload/F-Droid, not Google Play. No accounts, no analytics, no network.

## Commands

Requires JDK 17 and the Android SDK (`compileSdk 35`, `minSdk 26`). Gradle finds the SDK via
`ANDROID_HOME` or a git-ignored `local.properties`; without one, `:app` tasks fail to configure.

```bash
./gradlew test                    # all unit tests (JVM only — no emulator needed)
./gradlew :core:domain:test       # the engine + detection matcher (the tests that matter)
./gradlew :core:model:test        # the user-adjustable timing rules
./gradlew lintDebug
./gradlew assembleDebug           # app/build/outputs/apk/debug/
./gradlew assembleRelease         # signed if keystore.properties exists, else *-unsigned.apk
```

Run one test — method names are backtick-quoted sentences, so quote the pattern:

```bash
./gradlew :core:domain:test --tests "*DoomscrollEngineTest.a long absence resets the session"
./gradlew :core:domain:test --tests "*SignalEvaluatorTest*"
```

Inspecting a built APK (Windows build-tools use `.bat`/`.exe` suffixes; CI on Linux does not):

```bash
SDK=$ANDROID_HOME/build-tools/35.0.0
$SDK/aapt2 dump permissions app/build/outputs/apk/release/app-release.apk
$SDK/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

**Installing requires `adb`.** Play Protect hard-blocks sideloaded apps declaring an
accessibility service when they arrive from a browser or file manager. `adb install -r <apk>`
is unaffected. This is not a bug and no build setting avoids it.

## Architecture

```
core/model     pure JVM   TargetAppCatalog, Signal, InterruptPolicy, TimingRule,
                          EnforcementState, AppTimeLeft
core/domain    pure JVM   DoomscrollEngine, SignalEvaluator, CooldownStore, TimeSource
app            Android    accessibility service, overlays, DataStore, Compose settings UI
```

**The `core` modules must never depend on the Android SDK.** That constraint is the reason the
whole timing policy is testable in milliseconds with a fake clock instead of needing an emulator
and a ten-minute wait. Both also build with `allWarningsAsErrors`.

### The engine is the only thing that decides anything

`DoomscrollEngine` is the single source of truth for timing. It takes three inputs — a surface
change, a scroll, a tick — and answers each with the `EnforcementState` the screen should
reflect. `DoomscrollAccessibilityService` converts an `AccessibilityNodeInfo` tree into an inert
`ScreenSnapshot`, hands it over, and makes the screen match whatever comes back. It contains no
policy. Adding rules to the service instead of the engine is the main way to get this wrong.

### Detection is data, not code

Each supported app is one `TargetApp` in `TargetAppCatalog`: a package set plus a `Signal` — a
small boolean algebra (`ViewId` / `ContentDescriptionExact` / `ContentDescriptionPrefix` /
`AnyOf` / `AllOf` / `NoneOf`) evaluated by `SignalEvaluator.matches`. **Adding an app means
adding a catalog entry; the matcher never changes.**

View IDs are internal details of Instagram/YouTube/TikTok/Facebook and can be renamed by any
vendor update, which is why entries prefer several alternatives over one exact match.
`docs/DETECTION.md` explains how to re-derive them with `uiautomator dump`.

Instagram's rule wraps the pager in `NoneOf(direct-message containers)` because a Reel forwarded
into a chat renders with the same player as the feed — without it, blocking Reels would block
reading messages.

### Two runtime paths

- **Events** drive the accurate path: a window change triggers a bounded (160-node) BFS scan.
  Throttled to 250 ms and coalesced, because a playing feed emits content changes continuously.
- **A timer** drives the cheap path: advances the clock, checks only the foreground package.
  This is what removes the overlay when the user leaves — the manifest restricts accessibility
  events to supported apps, so going to the launcher produces no event at all.

### Settings screen reads the live engine

`EnforcementTimers` is a process-local handle the service attaches its engine to; `HomeViewModel`
polls `snapshot()` for per-app `AppTimeLeft`. An enabled accessibility service runs in the app's
own process, so no binder or broadcast is needed. `snapshot()` returning `null` means the service
is off (distinct from an empty map) and the UI must say so rather than show frozen timers.

The countdowns are *derived* from the same session state the block reads. Never recompute them
independently — the screen must not be able to promise time the block disregards.

### Adjustable timings are a table, not four sliders

`TimingRule` enumerates the parts of `InterruptPolicy` a user may change and the values each
may take. The settings screen iterates `TimingRule.entries`, so **a new knob means a new enum
entry plus a label in `HomeScreen`'s exhaustive `when`** — no new bounds arithmetic. Values are
a list of round durations rather than a range and a step, because a linear minute-by-minute
slider puts a hundred stops under a fingertip.

Never build an `InterruptPolicy` from UI state by hand. `TimingRule.applyTo` is the only writer:
it snaps to an offered value and settles the warning-versus-trigger constraint *before*
constructing, since `copy` runs the same `require` and would throw on the intermediate pair.
`SettingsRepository.setTimingRule` does that read-modify-write inside one DataStore `edit`, so
a fast drag's several commits each apply to what is actually stored.

Changes reach the running service through the settings flow it already collects. Sessions
survive, and a block already running keeps the deadline it was given — lowering the block
length must not become an escape hatch from a pause in progress.

## Invariants that are easy to break

- **No `INTERNET` permission.** CI runs `aapt2 dump permissions` against the built APK and fails
  if it appears. The manifest also carries `tools:node="remove"` so a dependency adding it gets
  stripped. The only permission the APK legitimately reports is AndroidX's app-private
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.
- **`NodeSnapshot` has no text field.** Detection reads view IDs, content descriptions, selected
  state and bounds — never `AccessibilityNodeInfo.getText()`. Captions, usernames and message
  bodies are never loaded into the process. Adding a text field breaks the privacy claim the
  README makes, not just its wording.
- **`packageNames` in `res/xml/accessibility_service_config.xml` must stay in sync with
  `TargetAppCatalog`.** The system filters events before dispatch, so an app missing from that
  list is silently never monitored.
- **Enforcement uses `SystemClock.elapsedRealtime`, never wall-clock time**, so a block cannot be
  ended by changing the date. `PersistentCooldownStore` stores both and converts across reboots,
  clamping the restored remainder so a backwards clock cannot manufacture an endless block.
- **Time credited is derived from the scrolling window, not tick count**, so a throttled or
  missed timer cannot change when a block fires.
- **The block must not interfere with home or recents.** Only back is suppressed (via both
  `KEYCODE_BACK` and `OnBackInvokedCallback`). Trapping a user on one screen would be worse than
  the habit the app addresses.
- **The overlay uses `TYPE_ACCESSIBILITY_OVERLAY`, not `SYSTEM_ALERT_WINDOW`.** An accessibility
  service may use it without the "draw over other apps" grant. Do not add that permission.
- **`EnforcementTimers.detach` checks identity** — during a teardown/rebind race the replacement
  service can connect before the outgoing one is destroyed, and an unconditional clear would drop
  the live handle.

## Releasing

Push a `v*` tag; `.github/workflows/release.yml` builds, signs from GitHub Secrets, and publishes.
It refuses to proceed if the tag disagrees with `versionName`, if the APK declares `INTERNET`, or
if the signing certificate does not match the fingerprint pinned in the workflow. Bump **both**
`versionCode` (must increase) and `versionName` in `app/build.gradle.kts` first.

`docs/RELEASING.md` has the one-time secret setup. Never commit `keystore.properties`,
`keystore.b64`, or any `*.jks`/`*.keystore`.

## Known limitations

Detection breaks when vendors redesign. Android 17's Advanced Protection Mode revokes
accessibility for apps not declaring `isAccessibilityTool="true"` — deliberately not declared
here, since this is a wellbeing tool rather than assistive technology. Not distributed on Google
Play, whose policy would require a user-accessible way to disable the block.
