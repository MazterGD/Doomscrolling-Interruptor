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
package io.github.maztergd.interruptor.core.domain

/**
 * The few properties of one on-screen element that signal matching needs.
 *
 * Note what is absent: [android.view.accessibility.AccessibilityNodeInfo.getText]. Node text
 * is where captions, usernames and message bodies live, and this type is the boundary that
 * keeps them out of the app entirely. A snapshot exists only for the duration of a single
 * match and is never persisted or logged.
 *
 * @param viewId the fully qualified view id, e.g. `com.instagram.android:id/clips_viewer_view_pager`.
 * @param contentDescription the element's accessibility label. Needed because Facebook exposes
 *   no usable view ids; only ever compared against fixed literals declared in the catalog.
 * @param isSelected whether the platform reports the element as selected.
 * @param topPx the element's top edge in screen pixels.
 */
public data class NodeSnapshot(
    val viewId: String? = null,
    val contentDescription: String? = null,
    val isSelected: Boolean = false,
    val topPx: Int = 0,
)

/**
 * A bounded sample of the foreground app's element tree.
 *
 * Traversal is capped by the caller, so this is a partial view by construction — signals must
 * therefore be written to match something near the root of a short-video surface.
 */
public data class ScreenSnapshot(
    val nodes: List<NodeSnapshot>,
    val screenHeightPx: Int,
) {
    init {
        require(screenHeightPx > 0) { "screenHeightPx must be positive, was $screenHeightPx" }
    }

    public companion object {
        /** An empty screen. Matches nothing. */
        public fun empty(screenHeightPx: Int): ScreenSnapshot =
            ScreenSnapshot(nodes = emptyList(), screenHeightPx = screenHeightPx)
    }
}
