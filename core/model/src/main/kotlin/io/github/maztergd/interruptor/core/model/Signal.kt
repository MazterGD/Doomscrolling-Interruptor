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
 * A predicate over the accessibility node tree of the foreground app.
 *
 * This is a deliberately small boolean algebra. Recognising a short-video surface is
 * a *structural* question ("is the Reels pager on screen?"), so every leaf inspects
 * view identifiers or accessibility labels only. No leaf can read user content such as
 * captions, usernames or messages, which is what makes the privacy claim in the README
 * a property of the type system rather than a promise.
 *
 * Adding support for a new app is a matter of declaring another [TargetApp] — the
 * matcher never needs to change (open/closed principle).
 */
public sealed interface Signal {

    /**
     * Matches when a node's view id ends with [suffix].
     *
     * Android reports fully qualified ids such as `com.instagram.android:id/clips_viewer_view_pager`.
     * Matching the suffix keeps a single declaration working across package variants
     * (for example the several package names TikTok ships under).
     */
    public data class ViewId(val suffix: String) : Signal {
        init {
            require(suffix.isNotBlank()) { "ViewId suffix must not be blank" }
        }
    }

    /** Matches when any node's content description equals one of [values] exactly. */
    public data class ContentDescriptionExact(val values: Set<String>) : Signal {
        init {
            require(values.isNotEmpty()) { "ContentDescriptionExact requires at least one value" }
        }
    }

    /**
     * Matches when a node's content description starts with one of [prefixes].
     *
     * Facebook does not expose usable view ids for Reels, so its tab must be identified by
     * label. Labels like "Reels" also appear on feed shelves, hence the extra constraints:
     *
     * @param requireSelected only match a node the platform reports as selected.
     * @param maxTopScreenFraction only match a node whose top edge sits within this fraction
     *   of the screen height, which confines matches to the real navigation bar.
     */
    public data class ContentDescriptionPrefix(
        val prefixes: Set<String>,
        val requireSelected: Boolean = false,
        val maxTopScreenFraction: Float? = null,
    ) : Signal {
        init {
            require(prefixes.isNotEmpty()) { "ContentDescriptionPrefix requires at least one prefix" }
            maxTopScreenFraction?.let {
                require(it > 0f && it <= 1f) { "maxTopScreenFraction must be in (0, 1], was $it" }
            }
        }
    }

    /** Matches when at least one of [signals] matches. */
    public data class AnyOf(val signals: List<Signal>) : Signal {
        init {
            require(signals.isNotEmpty()) { "AnyOf requires at least one signal" }
        }
    }

    /** Matches when every one of [signals] matches. */
    public data class AllOf(val signals: List<Signal>) : Signal {
        init {
            require(signals.isNotEmpty()) { "AllOf requires at least one signal" }
        }
    }

    /**
     * Matches when none of [signals] match.
     *
     * Used to carve out surfaces that must stay reachable — most importantly direct
     * messages, which can embed the very same video player as the Reels feed.
     */
    public data class NoneOf(val signals: List<Signal>) : Signal {
        init {
            require(signals.isNotEmpty()) { "NoneOf requires at least one signal" }
        }
    }
}

/** Convenience builder for [Signal.AnyOf]. */
public fun anyOf(vararg signals: Signal): Signal.AnyOf = Signal.AnyOf(signals.toList())

/** Convenience builder for [Signal.AllOf]. */
public fun allOf(vararg signals: Signal): Signal.AllOf = Signal.AllOf(signals.toList())

/** Convenience builder for [Signal.NoneOf]. */
public fun noneOf(vararg signals: Signal): Signal.NoneOf = Signal.NoneOf(signals.toList())

/** Convenience builder for [Signal.ViewId]. */
public fun viewId(suffix: String): Signal.ViewId = Signal.ViewId(suffix)
