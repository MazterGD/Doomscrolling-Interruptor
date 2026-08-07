package io.github.maztergd.interruptor.core.domain

import io.github.maztergd.interruptor.core.model.Signal
import io.github.maztergd.interruptor.core.model.TargetAppCatalog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies the detection rules for each supported app against representative screens. */
class SignalEvaluatorTest {

    private val screenHeight = 2400

    private fun screenOf(vararg nodes: NodeSnapshot) =
        ScreenSnapshot(nodes = nodes.toList(), screenHeightPx = screenHeight)

    private fun ids(vararg viewIds: String) =
        ScreenSnapshot(viewIds.map { NodeSnapshot(viewId = it) }, screenHeight)

    // -- Instagram ---------------------------------------------------------------------

    @Test
    fun `instagram reels feed is detected`() {
        val screen = ids(
            "com.instagram.android:id/root",
            "com.instagram.android:id/clips_viewer_view_pager",
        )
        assertTrue(TargetAppCatalog.instagram.reelsSignal.matches(screen))
    }

    @Test
    fun `instagram home feed is not detected`() {
        val screen = ids(
            "com.instagram.android:id/root",
            "com.instagram.android:id/feed_recycler_view",
        )
        assertFalse(TargetAppCatalog.instagram.reelsSignal.matches(screen))
    }

    @Test
    fun `a reel playing inside a direct message thread is not blocked`() {
        // Regression guard for the explicit requirement that messaging stays available:
        // a forwarded Reel renders with the same pager as the feed.
        val screen = ids(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/direct_thread_toolbar",
        )
        assertFalse(
            "Reels shared into a DM thread must not trigger a block",
            TargetAppCatalog.instagram.reelsSignal.matches(screen),
        )
    }

    @Test
    fun `the direct message inbox is not blocked`() {
        val screen = ids(
            "com.instagram.android:id/clips_viewer_view_pager",
            "com.instagram.android:id/direct_inbox_recycler_view",
        )
        assertFalse(TargetAppCatalog.instagram.reelsSignal.matches(screen))
    }

    // -- YouTube -----------------------------------------------------------------------

    @Test
    fun `any of the youtube shorts markers is sufficient`() {
        val markers = listOf(
            "com.google.android.youtube:id/reel_player_page_container",
            "com.google.android.youtube:id/reel_progress_bar",
            "com.google.android.youtube:id/reel_recycler",
        )
        markers.forEach { marker ->
            assertTrue("expected $marker to match", TargetAppCatalog.youtube.reelsSignal.matches(ids(marker)))
        }
    }

    @Test
    fun `the ordinary youtube watch page is not detected`() {
        val screen = ids(
            "com.google.android.youtube:id/watch_player",
            "com.google.android.youtube:id/player_control_play_pause_replay_button",
        )
        assertFalse(TargetAppCatalog.youtube.reelsSignal.matches(screen))
    }

    @Test
    fun `youtube detection is package agnostic`() {
        // Suffix matching means the ReVanced and Kids builds work off the same declaration.
        val screen = ids("app.revanced.android.youtube:id/reel_player_page_container")
        assertTrue(TargetAppCatalog.youtube.reelsSignal.matches(screen))
    }

    // -- TikTok ------------------------------------------------------------------------

    @Test
    fun `tiktok feed is detected`() {
        assertTrue(TargetAppCatalog.tiktok.reelsSignal.matches(ids("com.zhiliaoapp.musically:id/player_view")))
    }

    @Test
    fun `tiktok inbox is not detected`() {
        assertFalse(TargetAppCatalog.tiktok.reelsSignal.matches(ids("com.zhiliaoapp.musically:id/inbox_list")))
    }

    // -- Facebook ----------------------------------------------------------------------

    @Test
    fun `facebook reel opened from the feed is detected`() {
        val screen = screenOf(
            NodeSnapshot(contentDescription = "FbShortsComposerAttachmentComponentSpec_STICKER"),
        )
        assertTrue(TargetAppCatalog.facebook.reelsSignal.matches(screen))
    }

    @Test
    fun `facebook reels tab is detected when selected and in the navigation bar`() {
        val screen = screenOf(
            NodeSnapshot(contentDescription = "Reels, tab 3 of 6", isSelected = true, topPx = 60),
        )
        assertTrue(TargetAppCatalog.facebook.reelsSignal.matches(screen))
    }

    @Test
    fun `an unselected facebook reels tab is not detected`() {
        val screen = screenOf(
            NodeSnapshot(contentDescription = "Reels, tab 3 of 6", isSelected = false, topPx = 60),
        )
        assertFalse(TargetAppCatalog.facebook.reelsSignal.matches(screen))
    }

    @Test
    fun `a reels shelf part way down the feed is not detected`() {
        // The label matches and the node is selected, but a shelf sits well below the
        // navigation bar. Without the position constraint this would be a false block.
        val screen = screenOf(
            NodeSnapshot(contentDescription = "Reels, tab 1 of 4", isSelected = true, topPx = 1500),
        )
        assertFalse(TargetAppCatalog.facebook.reelsSignal.matches(screen))
    }

    @Test
    fun `a plain Reels heading in the feed is not detected`() {
        val screen = screenOf(NodeSnapshot(contentDescription = "Reels", isSelected = true, topPx = 60))
        assertFalse(TargetAppCatalog.facebook.reelsSignal.matches(screen))
    }

    // -- algebra -----------------------------------------------------------------------

    @Test
    fun `an empty screen matches nothing`() {
        val empty = ScreenSnapshot.empty(screenHeight)
        TargetAppCatalog.all.forEach { app ->
            assertFalse("${app.id} matched an empty screen", app.reelsSignal.matches(empty))
        }
    }

    @Test
    fun `noneOf over an empty screen is vacuously true`() {
        assertTrue(Signal.NoneOf(listOf(Signal.ViewId("absent"))).matches(ScreenSnapshot.empty(screenHeight)))
    }

    @Test
    fun `allOf requires every branch`() {
        val signal = Signal.AllOf(listOf(Signal.ViewId("a"), Signal.ViewId("b")))
        assertTrue(signal.matches(ids("pkg:id/a", "pkg:id/b")))
        assertFalse(signal.matches(ids("pkg:id/a")))
    }

    @Test
    fun `catalog packages are unambiguous`() {
        // TargetAppCatalog fails fast on duplicates; this pins the guarantee callers rely on.
        TargetAppCatalog.all.forEach { app ->
            app.packageNames.forEach { pkg ->
                assertTrue("$pkg resolved to the wrong app", TargetAppCatalog.forPackage(pkg) === app)
            }
        }
    }
}
