// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.view.MotionEvent
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.overlay.model.ComboPreset

/**
 * A virtual pad that represents a [ComboPreset] (a.k.a. "chord" / "macro"
 * button).
 *
 * Pressing anywhere inside the pad sends [ComboPreset.buttons] to the
 * game as a list of *simultaneously held* buttons. Releasing the finger
 * sends RELEASED for every one of them. This lets a single tap emit a
 * macro like "Down + Forward + A" used for special moves.
 *
 * The pad is rendered as a single rounded panel showing the combo's
 * user-defined [ComboPreset.displayName] in the centre. Visual layout
 * is a single hit region, so the user only needs one finger to fire
 * the combo.
 */
class InputOverlayDrawableCombo(
    private val res: Resources,
    val preset: ComboPreset,
) {
    val id: String get() = preset.id

    private val bounds = RectF()
    var controlPositionX = 0
        private set
    var controlPositionY = 0
        private set
    private var previousTouchX = 0
    private var previousTouchY = 0

    // Pointer that currently holds the pad down (we only ever need one).
    private var activePointerId: Int = -1
    private var comboActive = false

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

    /** Draws the combo pad onto [canvas]. */
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

        // Main label: the user-defined combo name.
        labelPaint.textSize = (minOf(width, height) * 0.20f).coerceIn(20f, 64f)
        val name = preset.displayName.ifBlank { autoName() }
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        canvas.drawText(name, cx, cy + labelPaint.textSize / 3f, labelPaint)

        // Sub-label: "+ A" style short hint of the underlying buttons.
        if (preset.displayName.isNotBlank() && preset.displayName != autoName()) {
            subLabelPaint.textSize = labelPaint.textSize * 0.5f
            canvas.drawText(
                autoName(),
                cx,
                cy + labelPaint.textSize * 1.1f,
                subLabelPaint
            )
        }
    }

    /** "A + B + Capture" form derived from the current selection. */
    fun autoName(): String =
        preset.buttons.joinToString(" + ") { buttonLabel(it) }

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
     * Feed a [MotionEvent] to the combo pad. The pad reacts to a single
     * pointer that lands inside its bounds; additional pointers are
     * ignored (the combo is a one-finger gesture).
     *
     * @return A list of button actions the caller should forward to
     *         NativeInput.onOverlayButtonEvent, paired with the
     *         [NativeButton] to emit. Empty list means no change.
     */
    fun updateStatus(event: MotionEvent): List<Pair<NativeButton, Boolean>> {
        val motionEvent = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (motionEvent) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (comboActive) return emptyList()
                if (!containsPoint(x, y)) return emptyList()
                // If a different finger is already active, ignore this
                // pointer (one-finger gesture semantics).
                if (activePointerId != -1) return emptyList()
                activePointerId = pointerId
                comboActive = true
                return preset.buttons.map { it to true }
            }

            MotionEvent.ACTION_MOVE -> {
                // If the active finger left the pad, release the combo.
                if (activePointerId == -1) return emptyList()
                val pi = event.findPointerIndex(activePointerId)
                if (pi < 0) return emptyList()
                if (!containsPoint(event.getX(pi), event.getY(pi))) {
                    activePointerId = -1
                    if (comboActive) {
                        comboActive = false
                        return preset.buttons.map { it to false }
                    }
                }
                return emptyList()
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (activePointerId != pointerId) return emptyList()
                activePointerId = -1
                if (comboActive) {
                    comboActive = false
                    return preset.buttons.map { it to false }
                }
                return emptyList()
            }
        }
        return emptyList()
    }

    /** True while the combo is currently held (PRESSED). */
    val isPressed: Boolean get() = comboActive

    /** Reset internal state. Call when the activity is destroyed / reconfigured. */
    fun reset() {
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
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
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

    /** Public read-only bounds for hit-testing by the parent overlay. */
    fun boundsRect(): RectF = RectF(bounds)

    fun computeSubCenters(): List<PointF> = emptyList()

    fun hitSubIndex(x: Float, y: Float): Int? = null
}
