// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.content.res.Resources
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.overlay.model.ComboPreset

/**
 * A virtual pad that represents a [ComboPreset].
 *
 * Two emission modes are supported:
 *
 * - [ComboPreset.Kind.CHORD] (default): all [ComboPreset.buttons] are
 *   sent PRESSED in the same frame, all RELEASED in the same frame when
 *   the user lifts off. Suitable for face / shoulder buttons
 *   (e.g. A+B).
 *
 * - [ComboPreset.Kind.MACRO]: buttons are sent PRESSED one by one in
 *   array order with a small delay between them, then all RELEASED
 *   together after a short hold. Used to emulate special-move style
 *   input (e.g. "Down + Forward + A" → ↓ → → + A).
 *
 * The pad is rendered as a single rounded panel showing the combo's
 * user-defined [ComboPreset.displayName] in the centre. Only one
 * finger is needed to fire the combo.
 */
class InputOverlayDrawableCombo(
    private val res: Resources,
    val preset: ComboPreset,
    private val onButtonEvent: (NativeButton, Boolean) -> Unit,
) {
    val id: String get() = preset.id

    private val bounds = RectF()
    var controlPositionX = 0
        private set
    var controlPositionY = 0
        private set
    private var previousTouchX = 0
    private var previousTouchY = 0

    private var activePointerId: Int = -1
    private var comboActive = false

    // Pending macro runnables; we keep press and release queues
    // separate so that lifting off the pad cancels only the *presses*
    // that haven't fired yet, while the queued *releases* continue to
    // run on schedule (this is what lets a short tap on the pad still
    // complete a "down -> down-forward -> A" macro).
    private val handler = Handler(Looper.getMainLooper())
    private val pendingPresses = ArrayList<Runnable>()
    private val pendingReleases = ArrayList<Runnable>()

    // Tracks which keys are currently held (PRESSED) for an active macro
    // so fireRelease() only sends RELEASED for those, not for keys whose
    // press step was cancelled by an early lift.
    private val currentlyPressed = LinkedHashSet<NativeButton>()

    // --- Visual paints ---
    private val outerRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.argb(180, 200, 200, 200)
    }
    private val outerRingActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 6f
        color = Color.argb(255, 80, 200, 80)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val subLabelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 220, 220, 220)
        textAlign = Paint.Align.CENTER
    }
    private val macroBadgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 200, 80)
        textSize = 28f
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    var width: Int = 0
        private set
    var height: Int = 0
        private set

    fun configureLayout(x: Int, y: Int, w: Int, h: Int) {
        width = w
        height = h
        bounds.set(x.toFloat(), y.toFloat(), (x + w).toFloat(), (y + h).toFloat())
        controlPositionX = x
        controlPositionY = y
    }

    fun draw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (comboActive) Color.argb(160, 30, 60, 30) else Color.argb(110, 0, 0, 0)
        }
        val radius = (minOf(width, height) * 0.18f).coerceAtLeast(8f)
        canvas.drawRoundRect(bounds, radius, radius, panelPaint)
        val ringPaint = if (comboActive) outerRingActive else outerRing
        canvas.drawRoundRect(bounds, radius, radius, ringPaint)

        labelPaint.textSize = (minOf(width, height) * 0.20f).coerceIn(20f, 64f)
        val name = preset.displayName.ifBlank { autoName() }
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        canvas.drawText(name, cx, cy + labelPaint.textSize / 3f, labelPaint)

        if (preset.kind == ComboPreset.Kind.MACRO) {
            // Small "⟳" marker so the user can tell at a glance that
            // this combo is a sequential macro.
            val badge = "⟳"
            val pad = (minOf(width, height) * 0.08f).coerceAtLeast(8f)
            canvas.drawText(badge, bounds.left + pad, bounds.top + pad + macroBadgePaint.textSize,
                macroBadgePaint)
        }
    }

    fun autoName(): String = preset.buttons.joinToString(" + ") { buttonLabel(it) }

    private fun buttonLabel(b: NativeButton): String = when (b) {
        NativeButton.A -> "A"
        NativeButton.B -> "B"
        NativeButton.X -> "X"
        NativeButton.Y -> "Y"
        NativeButton.L -> "L"
        NativeButton.R -> "R"
        NativeButton.ZL -> "ZL"
        NativeButton.ZR -> "ZR"
        NativeButton.Plus -> "+"
        NativeButton.Minus -> "-"
        NativeButton.Home -> "Home"
        NativeButton.Capture -> "Capture"
        NativeButton.DUp -> "↑"
        NativeButton.DDown -> "↓"
        NativeButton.DLeft -> "←"
        NativeButton.DRight -> "→"
        NativeButton.LStick -> "L Stick"
        NativeButton.RStick -> "R Stick"
        else -> b.name
    }

    private fun containsPoint(x: Float, y: Float): Boolean = bounds.contains(x, y)

    /**
     * Feed a [MotionEvent]. Returns the list of button events to emit
     * **in this same frame** (typically empty for MACRO: only the first
     * key arrives here; the rest are scheduled internally). The pair is
     * (button, pressed).
     */
    fun updateStatus(event: MotionEvent): List<Pair<NativeButton, Boolean>> {
        val motionEvent = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        return when (motionEvent) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (comboActive) return emptyList()
                if (!containsPoint(x, y)) return emptyList()
                if (activePointerId != -1) return emptyList()
                activePointerId = pointerId
                comboActive = true
                firePress()
            }

            MotionEvent.ACTION_MOVE -> {
                if (activePointerId == -1) return emptyList()
                val pi = event.findPointerIndex(activePointerId)
                if (pi < 0) return emptyList()
                if (!containsPoint(event.getX(pi), event.getY(pi))) {
                    // Finger left the pad - treat as release.
                    val out = fireRelease()
                    activePointerId = -1
                    out
                } else emptyList()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                if (activePointerId != pointerId) return emptyList()
                val out = fireRelease()
                activePointerId = -1
                out
            }

            else -> emptyList()
        }
    }

    /**
     * Press the combo per its [ComboPreset.Kind]:
     * - CHORD: emit PRESSED for every button this frame.
     * - MACRO: queue a "press -> hold -> release" sequence where each
     *   key is held for [MACRO_HOLD_MS] and presses are spaced
     *   [MACRO_STEP_DELAY_MS] apart, so consecutive keys overlap
     *   (e.g. "Down + Forward + A" presses ↓, then → with ↓ still held,
     *   then A with → still held, then releases them in the same order).
     */
    private fun firePress(): List<Pair<NativeButton, Boolean>> {
        val buttons = preset.buttons
        if (buttons.isEmpty()) return emptyList()
        return when (preset.kind) {
            ComboPreset.Kind.CHORD -> {
                buttons.forEach { onButtonEvent(it, true) }
                currentlyPressed.clear()
                currentlyPressed.addAll(buttons)
                buttons.map { it to true }
            }
            ComboPreset.Kind.MACRO -> {
                currentlyPressed.clear()
                val step = MACRO_STEP_DELAY_MS
                val hold = MACRO_HOLD_MS
                // Schedule each key's PRESS at i*step. Each press runnable
                // is paired with its matching release via a shared "fired"
                // flag so a release that fires after a cancelled press
                // doesn't end up sending RELEASED for a key we never
                // actually pressed (this can happen if the user lifts off
                // very early, before the early press runnables have run).
                for (i in buttons.indices) {
                    val btn = buttons[i]
                    val pressed = booleanArrayOf(false)
                    val r = Runnable {
                        onButtonEvent(btn, true)
                        currentlyPressed.add(btn)
                        pressed[0] = true
                    }
                    pendingPresses += r
                    handler.postDelayed(r, step * i)
                    val rr = Runnable {
                        if (pressed[0]) {
                            onButtonEvent(btn, false)
                            currentlyPressed.remove(btn)
                        }
                    }
                    pendingReleases += rr
                    handler.postDelayed(rr, hold + step * i)
                }
                // We only report the first key in this synchronous
                // frame so the caller can play a haptic; the rest of
                // the macro is dispatched internally.
                listOf(buttons[0] to true)
            }
        }
    }

    /**
     * Release the combo:
     * - CHORD: release all preset buttons immediately.
     * - MACRO: cancel any *presses* that haven't fired yet (so we
     *   don't end up pressing things the user didn't really mean to
     *   press), but let the already-scheduled *releases* continue to
     *   run on their normal timeline. If the user holds long enough
     *   for the whole macro to play out, this is a no-op.
     */
    private fun fireRelease(): List<Pair<NativeButton, Boolean>> {
        if (!comboActive) return emptyList()
        comboActive = false
        val out = when (preset.kind) {
            ComboPreset.Kind.CHORD -> {
                // CHORD: cancel everything in-flight and release now.
                for (r in pendingPresses) handler.removeCallbacks(r)
                for (r in pendingReleases) handler.removeCallbacks(r)
                pendingPresses.clear()
                pendingReleases.clear()
                val toRelease = preset.buttons.toList()
                for (btn in toRelease) {
                    onButtonEvent(btn, false)
                    currentlyPressed.remove(btn)
                }
                toRelease.map { it to false }
            }
            ComboPreset.Kind.MACRO -> {
                // For MACRO: we keep the queued presses and releases
                // running so a quick tap still completes the full
                // sequence (e.g. "down -> forward -> A" still plays
                // out even if the user only tapped for 30ms). The
                // individual key releases are still tied to their
                // own hold timers, so the combo's total length is
                // bounded regardless of how long the user actually
                // holds the pad.
                val toRelease = currentlyPressed.toList()
                currentlyPressed.clear()
                for (btn in toRelease) onButtonEvent(btn, false)
                toRelease.map { it to false }
            }
        }
        return out
    }

    val isPressed: Boolean get() = comboActive

    fun reset() {
        for (r in pendingPresses) handler.removeCallbacks(r)
        for (r in pendingReleases) handler.removeCallbacks(r)
        pendingPresses.clear()
        pendingReleases.clear()
        currentlyPressed.clear()
        activePointerId = -1
        comboActive = false
    }

    // ----- Edit-mode drag support, mirrors InputOverlayDrawableButton.onConfigureTouch -----

    fun onConfigureTouch(event: MotionEvent): Boolean {
        val pointerIndex = when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> 0
            else -> event.actionIndex
        }
        val fingerPositionX = event.getX(pointerIndex).toInt()
        val fingerPositionY = event.getY(pointerIndex).toInt()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                previousTouchX = fingerPositionX
                previousTouchY = fingerPositionY
                controlPositionX = fingerPositionX - width / 2
                controlPositionY = fingerPositionY - height / 2
                configureLayout(controlPositionX, controlPositionY, width, height)
            }
            MotionEvent.ACTION_MOVE -> {
                controlPositionX += fingerPositionX - previousTouchX
                controlPositionY += fingerPositionY - previousTouchY
                configureLayout(controlPositionX, controlPositionY, width, height)
                previousTouchX = fingerPositionX
                previousTouchY = fingerPositionY
            }
        }
        return true
    }

    fun setBounds(left: Int, top: Int, right: Int, bottom: Int) =
        configureLayout(left, top, right - left, bottom - top)

    fun boundsRect(): RectF = RectF(bounds)

    companion object {
        /**
         * Delay between sequential macro key presses, in ms. The gap
         * between consecutive keys is also the "overlap" window where
         * the previous key is still held - this is what makes the
         * game register e.g. "down -> down-forward" as a single
         * direction segment. 100ms comfortably covers a fighting
         * game's input buffer.
         */
        const val MACRO_STEP_DELAY_MS: Long = 100L

        /**
         * How long each macro key is held (from its own press to its
         * own release), in ms. Must be greater than
         * [MACRO_STEP_DELAY_MS] so adjacent keys overlap.
         */
        const val MACRO_HOLD_MS: Long = 200L
    }
}
