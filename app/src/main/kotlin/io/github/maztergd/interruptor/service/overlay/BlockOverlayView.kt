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

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OnBackInvokedCallback
import android.view.OnBackInvokedDispatcher
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * The root of the blocking overlay. Its entire purpose is to refuse to go away.
 *
 * Back is suppressed through both mechanisms Android has used, because which one applies
 * depends on the platform version and on whether the process opted into predictive back:
 *
 * - up to Android 12, and for any window that has not opted in, the framework dispatches
 *   `KEYCODE_BACK` as an ordinary key event, handled in [dispatchKeyEvent];
 * - from Android 13 the gesture is delivered through `OnBackInvokedDispatcher`, so a
 *   do-nothing callback is registered at overlay priority.
 *
 * Home and recents are intentionally *not* interfered with. Suppressing them requires
 * device-owner privileges, and an app that could trap a user on one screen would be a far
 * worse thing to install than the habit it set out to fix. Leaving via home is the
 * intended and only exit.
 */
@SuppressLint("ViewConstructor")
class BlockOverlayView(context: Context) : FrameLayout(context) {

    private var backCallback: Any? = null

    init {
        // Focusable so the window receives key events at all; clickable so no touch reaches
        // the application underneath.
        isFocusable = true
        isFocusableInTouchMode = true
        isClickable = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) registerBackCallback()
    }

    override fun onDetachedFromWindow() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) unregisterBackCallback()
        super.onDetachedFromWindow()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
        return super.dispatchKeyEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Consume everything: the surface below must be unreachable, not merely hidden.
        return true
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun registerBackCallback() {
        if (backCallback != null) return
        val callback = OnBackInvokedCallback { /* deliberately does nothing */ }
        findOnBackInvokedDispatcher()?.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_OVERLAY,
            callback,
        ) ?: return
        backCallback = callback
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun unregisterBackCallback() {
        val callback = backCallback as? OnBackInvokedCallback ?: return
        findOnBackInvokedDispatcher()?.unregisterOnBackInvokedCallback(callback)
        backCallback = null
    }
}
