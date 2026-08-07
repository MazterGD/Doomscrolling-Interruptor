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
package io.github.maztergd.interruptor.service.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import io.github.maztergd.interruptor.R
import java.util.Locale
import kotlin.math.ceil

/**
 * Owns the two windows this app can put on screen, and nothing else.
 *
 * Both use `TYPE_ACCESSIBILITY_OVERLAY`, which an accessibility service may add without the
 * "display over other apps" grant. That is not merely convenient: it means the app never asks
 * for `SYSTEM_ALERT_WINDOW`, the permission most often abused by overlay malware, so a user
 * auditing the manifest finds one capability to reason about instead of two.
 *
 * Every method is main-thread only, matching the accessibility service's callbacks.
 */
class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)

    private var blockView: BlockOverlayView? = null
    private var blockTimer: TextView? = null
    private var blockTitle: TextView? = null

    private var countdownView: View? = null
    private var countdownLabel: TextView? = null

    /**
     * Shows — or updates — the full-screen block.
     *
     * Re-showing while already visible only refreshes the timer, so the window is added once
     * per block rather than once per tick.
     */
    fun showBlock(appName: String, remainingMs: Long) {
        hideCountdown()
        val existing = blockView
        if (existing != null) {
            updateBlockText(appName, remainingMs)
            return
        }

        val view = buildBlockView()
        blockView = view
        updateBlockText(appName, remainingMs)
        windowManager.addView(view, blockLayoutParams())
    }

    /** Shows — or updates — the small warning shown in the final seconds. */
    fun showCountdown(appName: String, remainingMs: Long) {
        hideBlock()
        val seconds = ceil(remainingMs / 1000.0).toInt().coerceAtLeast(0)
        val existing = countdownView
        if (existing != null) {
            countdownLabel?.text = context.getString(R.string.countdown_message, appName, seconds)
            return
        }

        val view = buildCountdownView()
        countdownView = view
        countdownLabel?.text = context.getString(R.string.countdown_message, appName, seconds)
        windowManager.addView(view, countdownLayoutParams())
    }

    /** Removes anything currently displayed. */
    fun hideAll() {
        hideBlock()
        hideCountdown()
    }

    private fun hideBlock() {
        val view = blockView ?: return
        blockView = null
        blockTimer = null
        blockTitle = null
        removeSafely(view)
    }

    private fun hideCountdown() {
        val view = countdownView ?: return
        countdownView = null
        countdownLabel = null
        removeSafely(view)
    }

    /**
     * Removing a window that the system already tore down throws. That happens routinely
     * when the service is being destroyed, and it must not take the process down with it.
     */
    private fun removeSafely(view: View) {
        runCatching { windowManager.removeViewImmediate(view) }
    }

    private fun updateBlockText(appName: String, remainingMs: Long) {
        blockTitle?.text = context.getString(R.string.block_title, appName)
        blockTimer?.text = formatDuration(remainingMs)
    }

    // ---------------------------------------------------------------------------------
    // View construction
    // ---------------------------------------------------------------------------------

    private fun buildBlockView(): BlockOverlayView {
        val root = BlockOverlayView(context).apply {
            setBackgroundColor(BACKGROUND)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            val pad = dp(32)
            setPadding(pad, pad, pad, pad)
        }

        column.addView(
            textView(R.string.block_heading, size = 16f, color = ACCENT, bold = true).apply {
                letterSpacing = 0.15f
            },
        )
        blockTitle = textView(size = 26f, color = Color.WHITE, bold = true).apply {
            setPadding(0, dp(12), 0, 0)
        }
        column.addView(blockTitle)

        blockTimer = textView(size = 64f, color = Color.WHITE, bold = true).apply {
            setPadding(0, dp(28), 0, dp(8))
        }
        column.addView(blockTimer)

        column.addView(textView(R.string.block_timer_caption, size = 14f, color = MUTED))
        column.addView(
            textView(R.string.block_explainer, size = 15f, color = MUTED).apply {
                setPadding(0, dp(32), 0, 0)
                setLineSpacing(dp(4).toFloat(), 1f)
            },
        )

        root.addView(
            column,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )
        return root
    }

    private fun buildCountdownView(): View {
        val label = textView(size = 15f, color = Color.WHITE, bold = true).apply {
            val h = dp(18)
            val v = dp(10)
            setPadding(h, v, h, v)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(PILL_BACKGROUND)
                setStroke(dp(1), ACCENT)
            }
        }
        countdownLabel = label
        return label
    }

    private fun textView(
        @StringRes textRes: Int? = null,
        size: Float,
        color: Int,
        bold: Boolean = false,
    ): TextView = TextView(context).apply {
        textRes?.let { setText(it) }
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) {
            typeface = Typeface.create(typeface, Typeface.BOLD)
        }
    }

    // ---------------------------------------------------------------------------------
    // Window parameters
    // ---------------------------------------------------------------------------------

    /**
     * Full-screen, focusable and touch-modal.
     *
     * Focusable is what allows the window to receive the back key so it can be swallowed;
     * omitting `FLAG_NOT_TOUCH_MODAL` is what stops touches reaching the app underneath.
     * `LAYOUT_IN_SCREEN` and `LAYOUT_NO_LIMITS` extend it behind the status and navigation
     * bars, so no strip of the blocked feed remains visible.
     */
    private fun blockLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.OPAQUE,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
    }

    /**
     * A warning, not a barrier: not focusable and not touchable, so scrolling continues
     * uninterrupted while the last seconds count down.
     */
    private fun countdownLayoutParams() = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        y = dp(72)
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val BACKGROUND = 0xFF0B0B0F.toInt()
        const val PILL_BACKGROUND = 0xE6141419.toInt()
        const val ACCENT = 0xFFFF8A5B.toInt()
        const val MUTED = 0xFF9A9AA8.toInt()

        /** Renders a remaining duration as `m:ss`, rounding up so it never shows 0:00. */
        fun formatDuration(millis: Long): String {
            val totalSeconds = ceil(millis.coerceAtLeast(0) / 1000.0).toLong()
            return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60)
        }
    }
}
