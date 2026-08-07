# How detection works

This is the fragile part of the project and the part most worth auditing. It is also the part
most likely to need maintenance, so this document explains both what the app looks for and how
to work out a replacement when a vendor changes something.

## The idea

Android hands an accessibility service a tree of the elements on screen. Every element can
carry a **view id** — the identifier the app's own developers gave that widget, reported as
`com.instagram.android:id/clips_viewer_view_pager` — and an **accessibility label**.

Recognising a short-video feed is therefore a structural question: *is the Reels pager on
screen?* It never requires reading what the videos are, who posted them, or what any text
says. That distinction is what the whole privacy claim rests on.

## What is read, and what is not

`ScreenSnapshotFactory` is the only code in the app that touches `AccessibilityNodeInfo`. Per
element it copies exactly four things:

| Copied | Why |
|---|---|
| `viewIdResourceName` | the primary signal |
| `contentDescription` | Facebook exposes no usable view ids |
| `isSelected` | to tell a selected tab from a feed heading |
| bounds top edge | to tell a navigation bar from a feed shelf |

It does **not** read `AccessibilityNodeInfo.getText()`. That is where captions, usernames and
message bodies live. `NodeSnapshot` has no field to hold it, so this is enforced by the type
rather than by discipline.

Traversal is breadth-first and capped at 160 nodes. Feed containers sit near the root, so the
cap is generous for detection while bounding the cost of a scan on a tree with thousands of
elements — this runs on the main thread.

## The signal language

Signals form a small boolean algebra, so an app's rule is data rather than code:

| Signal | Matches when |
|---|---|
| `ViewId(suffix)` | some element's view id ends with `suffix` |
| `ContentDescriptionExact(values)` | some element's label is exactly one of `values` |
| `ContentDescriptionPrefix(prefixes, requireSelected, maxTopScreenFraction)` | a label starts with a prefix, optionally only when selected and near the top of the screen |
| `AnyOf` / `AllOf` / `NoneOf` | composition |

View ids are matched by **suffix** so one declaration covers every package an app ships under
— the four TikTok packages and the three YouTube builds share a rule each.

`NoneOf` exists for exclusions. The important one is Instagram: a Reel forwarded into a chat
renders with the same pager as the feed, so without an exclusion for direct-message
containers, blocking Reels would also block reading your messages.

## Current signatures

| App | Packages | Signal |
|---|---|---|
| Instagram | `com.instagram.android` | `clips_viewer_view_pager`, excluding DM containers |
| YouTube | `com.google.android.youtube`, `…youtube.kids`, `app.revanced.android.youtube` | any of `reel_player_page_container`, `reel_progress_bar`, `reel_recycler` |
| TikTok | `com.zhiliaoapp.musically`(`.go`), `com.ss.android.ugc.trill`, `…ugc.aweme` | `player_view` |
| Facebook | `com.facebook.katana` | `FbShortsComposerAttachmentComponentSpec_STICKER`/`_GIF`, or a selected label starting `"Reels, tab"` in the top 20% of the screen |
| Facebook Lite | `com.facebook.lite` | `video_view` |
| Snapchat | `com.snapchat.android` | `spotlight_container` |

Two of these deserve a note.

**Facebook** has no stable view ids, so it is identified by label. The label `"Reels"` also
titles ordinary feed shelves, which is why the tab rule requires the element to be *selected*
and to sit in the top fifth of the screen: that confines matches to the real navigation bar.
Without both constraints, scrolling past a Reels shelf in the main feed would trigger a block.

**TikTok's** feed is its home screen, so the player being present is the signal, and there is
no meaningful "rest of the app" to preserve.

## When an app updates and detection breaks

The symptom is that a feed no longer triggers a block, or that something innocent does.

To find the new identifier:

1. Enable developer options and connect the device over adb.
2. Open the screen in question.
3. Dump the tree: `adb shell uiautomator dump /sdcard/w.xml && adb pull /sdcard/w.xml`
4. Look for `resource-id` attributes on the containers wrapping the video pager. Prefer a
   container that exists *only* on that surface — a progress bar or pager, not a generic
   `root` or `content_frame`.
5. Add it to the relevant `TargetApp` in `TargetAppCatalog`, as an extra branch of an `AnyOf`
   rather than a replacement. Old identifiers cost nothing to keep and keep older app versions
   working.
6. Add a case to `SignalEvaluatorTest`. Both a screen that should match and one that should
   not — false positives are worse than false negatives here, because a false positive blocks
   someone's messages.

`Android Studio → Layout Inspector` works too, but only against debuggable apps, so `uiautomator`
is usually the practical route for production builds from the Play Store.

## Why not other approaches

**Usage-stats APIs** report that Instagram is in the foreground, not that Reels is. They can
only support whole-app time limits — the thing this project deliberately does not do.

**Screenshot or pixel analysis** would require reading the actual content of the screen,
which is far more invasive than reading widget identifiers, and would need either an
image model on device or a server. Both are worse on every axis that matters here.

**VPN-based filtering** would mean routing traffic through the app, which is exactly the
capability this project refuses to have.
