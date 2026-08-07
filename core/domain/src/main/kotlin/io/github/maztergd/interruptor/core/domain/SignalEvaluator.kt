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

import io.github.maztergd.interruptor.core.model.Signal

/**
 * Decides whether [this] signal describes what is currently on screen.
 *
 * Splitting evaluation from traversal is what makes detection testable without a device:
 * the Android layer's only job is to turn an `AccessibilityNodeInfo` tree into a
 * [ScreenSnapshot], and every rule about what constitutes a short-video surface is decided
 * by this pure function.
 */
public fun Signal.matches(snapshot: ScreenSnapshot): Boolean = when (this) {
    is Signal.ViewId -> snapshot.nodes.any { node ->
        // Ids arrive fully qualified as "<package>:id/<name>". Matching the trailing segment
        // lets one declaration cover every package variant an app ships under.
        node.viewId?.endsWith(suffix) == true
    }

    is Signal.ContentDescriptionExact -> snapshot.nodes.any { node ->
        node.contentDescription in values
    }

    is Signal.ContentDescriptionPrefix -> snapshot.nodes.any { node ->
        val description = node.contentDescription ?: return@any false
        if (requireSelected && !node.isSelected) return@any false
        if (!prefixes.any(description::startsWith)) return@any false
        val limit = maxTopScreenFraction ?: return@any true
        node.topPx <= snapshot.screenHeightPx * limit
    }

    is Signal.AnyOf -> signals.any { it.matches(snapshot) }
    is Signal.AllOf -> signals.all { it.matches(snapshot) }
    is Signal.NoneOf -> signals.none { it.matches(snapshot) }
}
