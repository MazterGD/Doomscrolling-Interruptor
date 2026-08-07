/*
 * Doomscrolling-Interruptor
 * Copyright (C) 2026 the Doomscrolling-Interruptor contributors
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <https://www.gnu.org/licenses/>.
 */
package io.github.maztergd.interruptor.core.model

/**
 * How to move the user off a short-video surface once a block is in force.
 *
 * Kept as an enum rather than the platform's `GLOBAL_ACTION_*` integers so this module
 * stays free of the Android SDK; the service maps it at the edge.
 */
public enum class ExitAction {
    /** Pop the short-video screen off the app's back stack, leaving the rest of the app usable. */
    BACK,

    /** Leave the app entirely. Used where the short-video feed *is* the app's home screen. */
    HOME,
}

/**
 * One supported application, its package variants, and how to recognise its short-video surface.
 *
 * @param id stable identifier persisted in settings. Never derive it from a package name,
 *   because packages change and stored preferences must survive that.
 */
public data class TargetApp(
    val id: String,
    val displayName: String,
    val packageNames: Set<String>,
    val reelsSignal: Signal,
    val exitAction: ExitAction,
) {
    init {
        require(id.isNotBlank()) { "TargetApp id must not be blank" }
        require(packageNames.isNotEmpty()) { "TargetApp $id must declare at least one package" }
    }
}

/**
 * The built-in registry of supported apps.
 *
 * The view ids and accessibility labels below are observed facts about third-party user
 * interfaces, not configuration. They are the single most fragile part of the project:
 * a vendor redesign can retire an identifier at any time, so each entry prefers a set of
 * alternatives over one exact match, and `docs/DETECTION.md` explains how to re-derive them.
 */
public object TargetAppCatalog {

    /**
     * Surfaces that must never be treated as doomscrolling even when a video player is on
     * screen. Direct messages are the important case: a Reel forwarded into a chat renders
     * with the same player as the feed, and blocking conversations was explicitly out of scope.
     */
    private val instagramMessagingSurfaces: Signal = anyOf(
        viewId("direct_thread_toolbar"),
        viewId("direct_inbox_recycler_view"),
        viewId("row_thread_composer_edittext"),
        viewId("thread_message_list"),
    )

    public val instagram: TargetApp = TargetApp(
        id = "instagram",
        displayName = "Instagram",
        packageNames = setOf("com.instagram.android"),
        // The Reels pager is the reliable marker. The exclusion keeps DMs reachable even
        // when a shared Reel is playing inside a conversation.
        reelsSignal = allOf(
            viewId("clips_viewer_view_pager"),
            noneOf(instagramMessagingSurfaces),
        ),
        exitAction = ExitAction.BACK,
    )

    public val youtube: TargetApp = TargetApp(
        id = "youtube",
        displayName = "YouTube Shorts",
        packageNames = setOf(
            "com.google.android.youtube",
            "com.google.android.apps.youtube.kids",
            "app.revanced.android.youtube",
        ),
        // Three independent markers of the Shorts player. Any one is sufficient, which keeps
        // detection alive when a YouTube release renames one of them.
        reelsSignal = anyOf(
            viewId("reel_player_page_container"),
            viewId("reel_progress_bar"),
            viewId("reel_recycler"),
        ),
        exitAction = ExitAction.BACK,
    )

    public val tiktok: TargetApp = TargetApp(
        id = "tiktok",
        displayName = "TikTok",
        packageNames = setOf(
            "com.zhiliaoapp.musically",
            "com.zhiliaoapp.musically.go",
            "com.ss.android.ugc.trill",
            "com.ss.android.ugc.aweme",
        ),
        // TikTok's feed is its home screen, so the player being present is the signal.
        // BACK would simply close the app, so the exit action is HOME for symmetry with
        // how the user would normally leave.
        reelsSignal = viewId("player_view"),
        exitAction = ExitAction.HOME,
    )

    public val facebook: TargetApp = TargetApp(
        id = "facebook",
        displayName = "Facebook Reels",
        packageNames = setOf("com.facebook.katana"),
        // Facebook exposes almost no stable view ids, so it is identified by accessibility
        // labels instead. Two entry points must be covered:
        //  1. tapping a Reel in the main feed — the composer attachment labels appear;
        //  2. opening the Reels tab — the selected tab is labelled "Reels, tab N of M".
        // The label "Reels" alone also titles ordinary feed shelves, so the tab match is
        // constrained to a selected node in the top fifth of the screen.
        reelsSignal = anyOf(
            Signal.ContentDescriptionExact(
                setOf(
                    "FbShortsComposerAttachmentComponentSpec_STICKER",
                    "FbShortsComposerAttachmentComponentSpec_GIF",
                ),
            ),
            Signal.ContentDescriptionPrefix(
                prefixes = setOf("Reels, tab"),
                requireSelected = true,
                maxTopScreenFraction = 0.2f,
            ),
        ),
        exitAction = ExitAction.BACK,
    )

    public val facebookLite: TargetApp = TargetApp(
        id = "facebook_lite",
        displayName = "Facebook Lite",
        packageNames = setOf("com.facebook.lite"),
        reelsSignal = viewId("video_view"),
        exitAction = ExitAction.BACK,
    )

    public val snapchat: TargetApp = TargetApp(
        id = "snapchat",
        displayName = "Snapchat Spotlight",
        packageNames = setOf("com.snapchat.android"),
        reelsSignal = viewId("spotlight_container"),
        exitAction = ExitAction.BACK,
    )

    /** Every app the build knows how to recognise. */
    public val all: List<TargetApp> = listOf(
        instagram,
        youtube,
        tiktok,
        facebook,
        facebookLite,
        snapchat,
    )

    /** Enabled on a fresh install: the four the project explicitly targets. */
    public val defaultEnabledIds: Set<String> =
        setOf(instagram.id, youtube.id, tiktok.id, facebook.id)

    private val byPackage: Map<String, TargetApp> =
        all.flatMap { app -> app.packageNames.map { it to app } }.toMap()

    private val byId: Map<String, TargetApp> = all.associateBy { it.id }

    init {
        // A package mapping to two apps would make detection order-dependent, so fail loudly
        // at class-load time rather than behaving unpredictably on a user's device.
        val declared = all.sumOf { it.packageNames.size }
        check(declared == byPackage.size) { "Duplicate package name across TargetApp entries" }
        check(all.size == byId.size) { "Duplicate TargetApp id" }
    }

    /** The app owning [packageName], or `null` when the foreground app is not tracked. */
    public fun forPackage(packageName: String): TargetApp? = byPackage[packageName]

    /** The app with [id], or `null` when settings reference an app this build dropped. */
    public fun forId(id: String): TargetApp? = byId[id]
}
