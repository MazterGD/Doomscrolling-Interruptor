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
 * Where active blocks are recorded, keyed by [io.github.maztergd.interruptor.core.model.TargetApp.id].
 *
 * Expressed as a port so the engine stays pure: tests use the in-memory implementation,
 * while the app backs it with storage that survives the service being restarted or the
 * device rebooting. Values are deadlines on the [TimeSource] timeline.
 *
 * Implementations are called from the accessibility service's main thread and must not block.
 */
public interface CooldownStore {

    /** Deadline for [appId], or `null` if that app is not blocked. May be in the past. */
    public fun expiryFor(appId: String): Long?

    /** Records a block on [appId] until [expiresAtElapsedMs]. */
    public fun put(appId: String, expiresAtElapsedMs: Long)

    /** Forgets any block on [appId]. */
    public fun remove(appId: String)

    /** All recorded deadlines. Used by the UI to show what is currently blocked. */
    public fun snapshot(): Map<String, Long>
}

/** A [CooldownStore] held only in memory. Sufficient for tests and for a default. */
public class InMemoryCooldownStore(
    initial: Map<String, Long> = emptyMap(),
) : CooldownStore {

    private val deadlines = LinkedHashMap<String, Long>(initial)

    override fun expiryFor(appId: String): Long? = deadlines[appId]

    override fun put(appId: String, expiresAtElapsedMs: Long) {
        deadlines[appId] = expiresAtElapsedMs
    }

    override fun remove(appId: String) {
        deadlines.remove(appId)
    }

    override fun snapshot(): Map<String, Long> = deadlines.toMap()
}
