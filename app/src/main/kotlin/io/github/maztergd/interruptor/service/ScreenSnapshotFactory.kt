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
package io.github.maztergd.interruptor.service

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import io.github.maztergd.interruptor.core.domain.NodeSnapshot
import io.github.maztergd.interruptor.core.domain.ScreenSnapshot
import java.util.ArrayDeque

/**
 * Converts a live accessibility tree into the inert [ScreenSnapshot] the matcher understands.
 *
 * This is the only place in the app that touches `AccessibilityNodeInfo`, and it is
 * deliberately thin: it copies three structural properties per node and stops. In particular
 * it never reads [AccessibilityNodeInfo.getText], so captions, usernames and message bodies
 * are not merely ignored downstream — they are never loaded into this process at all.
 */
object ScreenSnapshotFactory {

    /**
     * Traversal budget.
     *
     * Short-video surfaces are identified by containers close to the root, so a breadth-first
     * walk finds them early. The cap bounds the cost of the scan on a feed whose tree contains
     * thousands of nodes, which matters because this runs on the main thread.
     */
    const val MAX_NODES: Int = 160

    fun capture(
        root: AccessibilityNodeInfo,
        screenHeightPx: Int,
        maxNodes: Int = MAX_NODES,
    ): ScreenSnapshot {
        val nodes = ArrayList<NodeSnapshot>(maxNodes)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val bounds = Rect()
        queue.add(root)

        while (queue.isNotEmpty() && nodes.size < maxNodes) {
            val node = queue.removeFirst()

            node.getBoundsInScreen(bounds)
            nodes += NodeSnapshot(
                viewId = node.viewIdResourceName,
                contentDescription = node.contentDescription?.toString(),
                isSelected = node.isSelected,
                topPx = bounds.top,
            )

            for (i in 0 until node.childCount) {
                queue.addLast(node.getChild(i) ?: continue)
            }
        }

        return ScreenSnapshot(
            nodes = nodes,
            screenHeightPx = screenHeightPx.coerceAtLeast(1),
        )
    }
}
