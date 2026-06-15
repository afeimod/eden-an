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
 * A virtual pad that represents a [ComboPreset]. It is drawn as a single
 * panel split into 2 or 3 sub-key regions; the user must press all of them
 * simultaneously (with one finger per region) for the combo's target key
 * to fire.
 *
 * Drag-to-reposition and edit-mode clicks fall back to the same behaviour
 * as a regular overlay button via [onConfigureTouch].
 */
class InputOverlayDrawableCombo(
    private val res: Resources,
    val preset: ComboPreset,
) {
    val id: String get() = preset.id

    private val bounds = RectF()
    private var bitmapDefault: Bitmap? = null
    private var bitmapPressed: Bitmap? = null
    var controlPositionX = 0
    var controlPositionY = 0
    private var previousTouchX = 0
    private var previousTouchY = 0

    // Currently pressed sub-keys: pointerId (from MotionEvent) -> sub-index
    private val activePointers = HashMap<Int, Int>()
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
    private val subFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(80, 255, 255, 255)
    }
    private val subFillActive = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(180, 80, 200, 80)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 28f
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

    /** Draws the combo pad onto [canvas]. */
    fun draw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        // Background panel (rounded rect approximated by filled rect with alpha).
        val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = if (comboActive) Color.argb(120, 30, 30, 30) else Color.argb(90, 0, 0, 0)
        }
        val radius = (minOf(width, height) * 0.18f).coerceAtLeast(8f)
        canvas.drawRoundRect(bounds, radius, radius, panelPaint)
        val ringPaint = if (comboActive) outerRingActive else outerRing
        canvas.drawRoundRect(bounds, radius, radius, ringPaint)

        // Sub-key hit circles.
        val subCenters = computeSubCenters()
        subCenters.forEachIndexed { idx, c ->
            val fill = if (activePointers.values.contains(idx)) subFillActive else subFill
            canvas.drawCircle(c.x, c.y, bounds.width() * 0.14f, fill)
        }

        // Centre label = combo target abbreviation.
        val label = abbreviate(preset.target)
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        canvas.drawText(label, cx, cy + labelPaint.textSize / 3f, labelPaint)
    }

    private fun abbreviate(b: NativeButton): String = when (b) {
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
        NativeButton.Home -> "⌂"
        NativeButton.Capture -> "▣"
        NativeButton.DUp -> "↑"
        NativeButton.DDown -> "↓"
        NativeButton.DLeft -> "←"
        NativeButton.DRight -> "→"
        NativeButton.LStick -> "LS"
        NativeButton.RStick -> "RS"
        else -> "?"
    }

    private fun computeSubCenters(): List<PointF> {
        val n = preset.triggers.size
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        val r = bounds.width() * 0.22f
        return when (n) {
            2 -> listOf(
                PointF(cx - r, cy),
                PointF(cx + r, cy),
            )
            3 -> listOf(
                PointF(cx, cy - r),
                PointF(cx - r * 0.866f, cy + r * 0.5f),
                PointF(cx + r * 0.866f, cy + r * 0.5f),
            )
            else -> emptyList()
        }
    }

    /** True if [x,y] is inside one of the sub-key hit circles (within 1.4x radius). */
    private fun hitSubIndex(x: Float, y: Float): Int? {
        val centers = computeSubCenters()
        val r = bounds.width() * 0.14f * 1.4f
        centers.forEachIndexed { idx, c ->
            val dx = c.x - x
            val dy = c.y - y
            if (dx * dx + dy * dy <= r * r) return idx
        }
        return null
    }

    /** Whether (x,y) is anywhere inside the pad rect (used to claim pointers). */
    private fun containsPoint(x: Float, y: Float): Boolean = bounds.contains(x, y)

    /**
     * Feed a [MotionEvent] to the combo pad. The pad only reacts to
     * pointers that started inside its bounds.
     *
     * @return ComboPressState describing whether the combo fired / released
     *         this event, plus the relevant target key. Caller is
     *         responsible for forwarding the synthesized button event to
     *         native.
     */
    fun updateStatus(event: MotionEvent): ComboPressState {
        val motionEvent = event.actionMasked
        val pointerIndex = event.actionIndex
        val pointerId = event.getPointerId(pointerIndex)
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)

        when (motionEvent) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!containsPoint(x, y)) return ComboPressState.NONE
                val subIdx = hitSubIndex(x, y) ?: return ComboPressState.NONE
                activePointers[pointerId] = subIdx
                return evaluate()
            }

            MotionEvent.ACTION_MOVE -> {
                // Sync each still-down pointer to its current sub-key (a finger
                // may have drifted). Remove any pointer that left the pad.
                val toRemove = mutableListOf<Int>()
                for ((pid, _) in activePointers) {
                    val pi = event.findPointerIndex(pid)
                    if (pi < 0) { toRemove += pid; continue }
                    val mx = event.getX(pi)
                    val my = event.getY(pi)
                    if (!containsPoint(mx, my)) {
                        toRemove += pid
                    } else {
                        val newIdx = hitSubIndex(mx, my)
                        if (newIdx != null) activePointers[pid] = newIdx
                    }
                }
                toRemove.forEach { activePointers.remove(it) }
                return evaluate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_CANCEL -> {
                if (activePointers.remove(pointerId) != null) {
                    return evaluate()
                }
            }
        }
        return ComboPressState.NONE
    }

    /** Returns the combo's current state - PRESS if all triggers satisfied, RELEASE otherwise. */
    private fun evaluate(): ComboPressState {
        val activeSubs = activePointers.values.toSet()
        val need = preset.triggers.indices.toSet()
        val allSatisfied = need.all { it in activeSubs }
        return when {
            allSatisfied && !comboActive -> {
                comboActive = true
                ComboPressState.ACTIVATED
            }
            !allSatisfied && comboActive -> {
                comboActive = false
                ComboPressState.DEACTIVATED
            }
            else -> ComboPressState.NONE
        }
    }

    /** True while at least one pointer is inside the pad. */
    fun hasActivePointer(): Boolean = activePointers.isNotEmpty()

    /** Reset internal state. Call when the activity is destroyed. */
    fun reset() {
        activePointers.clear()
        comboActive = false
    }

    // ----- Edit-mode drag support, mirrors InputOverlayDrawableButton.onConfigureTouch -----

    fun onConfigureTouch(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val fingerPositionX = event.getX(pointerIndex).toInt()
        val fingerPositionY = event.getY(pointerIndex).toInt()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                previousTouchX = fingerPositionX
                previousTouchY = fingerPositionY
                controlPositionX = fingerPositionX - width / 2
                controlPositionY = fingerPositionY - height / 2
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

    val isPressed: Boolean get() = comboActive

    enum class ComboPressState { NONE, ACTIVATED, DEACTIVATED }
}
