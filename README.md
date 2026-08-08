# Doomscrolling Interruptor

An Android app that interrupts short-video doomscrolling — and only that.

Scroll Reels, Shorts, TikTok or Facebook Reels for ten minutes and the feed pauses behind a
full-screen block you cannot dismiss. Messages, search, and the rest of the app keep working.
Every other app on the phone is untouched. There is no daily time limit and no allowance to
budget: the app reacts to the behaviour, not the clock.

Free software, GPL-3.0. No accounts, no analytics, and **no internet permission at all**.

---

## What it does

| | |
|---|---|
| **Trigger** | 10 minutes of *active scrolling* on a short-video feed |
| **Warning** | a 10-second countdown appears before the block |
| **Block** | full-screen, no exit or back button, for 5 minutes |
| **Scope** | that one app's short-video feed — nothing else |

Supported out of the box: Instagram Reels, YouTube Shorts, TikTok, Facebook Reels,
Facebook Lite, Snapchat Spotlight.

### What "active scrolling" means

Time accrues only while you are actually swiping. Stop to watch one long video and the timer
stops with you, twenty seconds after your last swipe. Watching is not the problem the app is
trying to solve; the compulsive swipe is.

### Why leaving the feed does not reset the timer

Accrued time survives a two-minute absence. Without that, the ten-minute threshold would be
defeated by flicking to the home feed and straight back. Leave for longer and the session
genuinely resets.

Each app is tracked separately, so time in Instagram never pushes YouTube toward a block, and
blocking one leaves the others alone.

### What the block does and does not do

The block covers the short-video feed and swallows the back gesture, so it cannot be
dismissed from within. It deliberately does **not** interfere with home or recents. Blocking
those requires device-owner privileges, and an app that could trap you on one screen would be
a worse thing to install than the habit it was meant to fix. Pressing home is the intended
exit — and if you come back to the feed, the block is still there, counting down.

### Seeing where you stand

Each app in the list carries a live countdown: how much scrolling it has left before its feed
pauses, or — while a block is in force — how long until it unlocks. The scrolling figure is
not wall-clock time and holds still when you are not swiping, which is the same rule the
interrupt itself runs on.

Both numbers are read from the running engine rather than recalculated for display, so what
the screen promises and what the block actually does cannot drift apart. With the
accessibility service off, nothing is being timed and no countdown is shown.

---

## Privacy

The strong claim first: **the app declares no `INTERNET` permission.** Android denies network
sockets to a UID that lacks it, so no code in this app — or in any library it links — can
transmit anything anywhere. This is enforced by the kernel, not promised in a policy.

Two things keep that true rather than merely intended. The manifest carries a
`tools:node="remove"` instruction for `INTERNET`, so that a dependency adding the permission
in future would have it stripped instead of granted. And CI inspects the **built APK** with
`aapt2` on every run, failing if the permission is ever present — checking the artifact that
actually reaches a device, rather than trusting what this project's own manifest says.

Beyond that:

- **Node text is never read.** Detecting a Reels feed is a structural question, so the app
  reads view identifiers and accessibility labels and stops there. Captions, usernames and
  message bodies are never loaded into the process. The boundary is a type: `NodeSnapshot`
  has no text field, so there is nowhere for that data to go.
- **Events are filtered by the system.** The manifest restricts accessibility events to the
  supported apps, so events from a banking app or a password manager never reach this process.
- **No `SYSTEM_ALERT_WINDOW`.** The overlay uses `TYPE_ACCESSIBILITY_OVERLAY`, which an
  accessibility service may use without the "draw over other apps" grant — the permission most
  often abused by overlay malware.
- **Nothing leaves the device.** Storage is a local DataStore, excluded from cloud backup and
  device-to-device transfer.
- **No analytics, no crash reporting, no third-party SDKs.**

The single permission the app asks for is accessibility, and `docs/DETECTION.md` explains
exactly what it does with it.

---

## Architecture

```
core/model     pure JVM   entities: the app catalog, detection signals, policy, state
core/domain    pure JVM   the interrupt engine and the signal matcher
app            Android    accessibility service, overlays, storage, settings UI
```

The two `core` modules cannot depend on the Android SDK — the build will not let them. That
is the point: every rule about *when* to interrupt lives in plain Kotlin and is covered by
unit tests that run in milliseconds with a fake clock, instead of needing an emulator and a
ten-minute wait.

The Android layer is deliberately thin. It turns an `AccessibilityNodeInfo` tree into an inert
`ScreenSnapshot`, hands it to the engine, and makes the screen match whatever
`EnforcementState` comes back. It decides nothing.

**Adding an app** means adding one `TargetApp` entry to `TargetAppCatalog` — a package set and
a `Signal` describing its feed. No matcher code changes.

### How work is divided at runtime

- **Events** drive the accurate path: a window change triggers a bounded scan of the element
  tree. Scans are throttled to 250 ms and coalesced, because a playing feed emits content
  changes continuously.
- **A timer** drives the cheap path: it advances the clock and checks only the foreground
  package. This matters most while a block is up — accessibility events are restricted to the
  supported apps, so leaving for the launcher produces no event at all, and this poll is what
  takes the overlay down.

### Resisting the obvious workarounds

- Enforcement runs on `SystemClock.elapsedRealtime`, not wall-clock time, so a block cannot be
  ended by changing the date.
- Blocks survive the service restarting and the device rebooting. A reboot is handled by
  converting a stored wall-clock deadline back into a monotonic one, clamped so that a clock
  moved backwards cannot manufacture an endless block.
- Time credited is computed from the scrolling window rather than from tick count, so a
  throttled or missed timer cannot shorten or lengthen a session.

---

## Building

Requires JDK 17 and the Android SDK (compileSdk 35, minSdk 26).

```bash
./gradlew assembleDebug     # APK at app/build/outputs/apk/debug/
./gradlew test              # unit tests
./gradlew lintDebug
```

CI builds debug and release APKs and runs the test suite on every push.

Release signing activates only when a git-ignored `keystore.properties` is present:

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=…
keyAlias=…
keyPassword=…
```

Without it the build still succeeds, producing `app-release-unsigned.apk`, so a fresh clone
compiles without needing secrets.

Releases are cut by pushing a `v*` tag: CI builds, signs, verifies and publishes the APK.
**[docs/RELEASING.md](docs/RELEASING.md)** has the full procedure and the one-time secret setup.

### Running the core tests without the Android SDK

The `core` modules are plain JVM projects, so `./gradlew :core:domain:test` needs no SDK — but
Gradle still configures `:app`, which does. To run them on a machine with no Android SDK at
all, point Gradle at a settings file that includes only the core modules.

---

## Installing

> **Tapping the APK in a browser or file manager will not work.** Google Play Protect blocks
> *any* sideloaded app that declares an accessibility service. Use `adb`, below. This is not
> a bug in this app and there is no build setting that avoids it — see
> [Why Play Protect blocks this](#why-play-protect-blocks-this).

```bash
adb install interruptor-1.1.0.apk
```

Then:

1. Open the app and grant the accessibility permission.
2. Choose which apps to watch.

To verify what you downloaded before installing it:

```bash
sha256sum -c interruptor-1.1.0.apk.sha256
apksigner verify --print-certs interruptor-1.1.0.apk
```

The signing certificate must be
`23:FF:FA:C9:36:6D:22:D6:CF:33:DA:88:62:FD:3E:6B:04:6A:86:09:B1:50:8E:CE:2A:0D:EA:80:B0:DD:B8:4F`.
Every release is built and signed by CI from a tag, and the build refuses to publish an APK
signed by any other key or carrying a network permission.

Android will separately warn that the service can "observe your actions". That warning is the
same for every accessibility service and it is not wrong — which is precisely why this project
is open source, why the detection logic is documented, and why the app cannot reach the network.

### Why Play Protect blocks this

Google blocks sideloaded installs of apps declaring `ACCESSIBILITY`, `READ_SMS`, `RECEIVE_SMS`
or notification-listener access, because those permissions are the ones banking-fraud malware
reaches for. The block applies to apps arriving from a **browser, messaging app or file
manager**. It does not apply to `adb`.

This app needs accessibility for the reason the whole project exists: it is the only API that
reports *which screen of an app* is showing, so it can tell a Reels feed from a DM thread.
Usage-stats APIs only report that Instagram is open, which would limit the whole app.

Google's stated remedy for developers is to minimise permissions, and there is nothing left
here to minimise. `aapt2 dump permissions` on the release APK reports exactly one entry:

```
io.github.maztergd.interruptor.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
```

That is not a device capability. It is a signature-level permission AndroidX declares over the
app's *own* package so that broadcast receivers it registers at runtime are not exported to
other apps — it grants nothing, to anyone, and cannot be held by another app. No system
permission of any kind is requested, and CI fails the build if `INTERNET` ever appears.

So the warning cannot be engineered away by dropping permissions; it is triggered by the
accessibility service declaration alone.

If `adb` is not an option, the alternatives are, in descending order of preference:

| | |
|---|---|
| **Build it yourself** | `./gradlew assembleDebug` and install over USB. Trust nothing. |
| **Temporarily disable the scan** | Play Store → profile → Play Protect → ⚙ → turn off "Scan apps with Play Protect", install, **turn it back on**. |
| **"Install anyway"** | Some regions still offer this under "More details" on the warning. Many now hard-block with no such option. |

Disabling Play Protect lowers your defences against exactly the class of malware this warning
exists to catch. Prefer `adb`, and turn the scan back on immediately if you do disable it.

### Known limitations

- **Detection is tied to third-party UIs.** The view identifiers this relies on are internal
  details of Instagram, YouTube and the rest. A redesign can retire one without notice, and
  detection for that app will silently stop until the catalog is updated. Each entry lists
  several alternatives to make that less likely. See `docs/DETECTION.md`.
- **Android 17's Advanced Protection Mode blocks this app.** That mode restricts the
  accessibility API to apps declaring `isAccessibilityTool="true"`. This app does not make
  that declaration, because it is a wellbeing tool rather than an assistive technology and
  claiming otherwise would be dishonest. With Advanced Protection on, the permission is
  revoked and the app cannot function.
- **Not distributed on Google Play**, by design. Play's policy would require a user-accessible
  way to disable the block, which is the one thing a commitment device must not have.
- **Uninstalling ends it.** There is no anti-uninstall guard. This is a tool for someone who
  wants the friction, not a parental control.

---

## Acknowledgements

The detection signatures were derived with reference to prior open-source work in this space,
whose authors did the hard part of finding these identifiers:

- [Scrolless](https://github.com/duartebarbosadev/Scrolless) (GPL-3.0)
- [Shorts-Blocker](https://github.com/atick-faisal/Shorts-Blocker) (Apache-2.0)
- [Curbox](https://github.com/curbox-app/curbox-android), successor to DigiPaws (GPL-3.0)

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
