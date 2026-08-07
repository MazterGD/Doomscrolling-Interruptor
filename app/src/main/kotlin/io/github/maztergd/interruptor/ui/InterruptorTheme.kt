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
package io.github.maztergd.interruptor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * A single dark palette, matching the block overlay.
 *
 * Deliberately not following the system light/dark setting: the app and the screen that
 * interrupts you should look like the same thing, and the overlay is always dark.
 */
private val InterruptorColors = darkColorScheme(
    primary = Color(0xFFFF8A5B),
    onPrimary = Color(0xFF2A1000),
    secondary = Color(0xFF8FB3FF),
    background = Color(0xFF0B0B0F),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF141419),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF1D1D24),
    onSurfaceVariant = Color(0xFF9A9AA8),
    outline = Color(0xFF34343E),
    error = Color(0xFFFF6B6B),
)

@Composable
fun InterruptorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = InterruptorColors, content = content)
}
