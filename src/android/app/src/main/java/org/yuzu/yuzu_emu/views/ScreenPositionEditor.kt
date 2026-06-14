// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later
//
// Adapted from EKA2L1's ScreenPositionEditor.java (GPL-3.0).
// Reference: src/emu/android/app/src/main/java/com/github/eka2l1/config/ScreenPositionEditor.java

package org.yuzu.yuzu_emu.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Overlay View that lets the user drag the four corners or the whole body of a
 * rectangle to reposition / resize the emulated screen. The rectangle lives in
 * normalised 0..1 coordinates relative to this view's bounds, so a layout
 * change of the parent does not break the saved position.
 *
 * The view itself is purely visual + touch handling. The caller wires
 * [listener] to push new rectangles to the native side (or persist them).
 */
class ScreenPositionEditor @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        /**
         * Called whenever the rectangle changes.
         * @param confirm true on touch-up, false during dragging.
         */
        fun onRectChanged(x1: Float, y1: Float, x2: Float, y2: Float, confirm: Boolean)
    }

    var listener: Listener? = null

    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#88E91E63")
        style = Paint.Style.FILL
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E91E63")
        style = Paint.Style.FILL
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 1f
    private var y2 = 1f
    private var activeHandle = HANDLE_NONE

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val l = x1 * w
        val t = y1 * h
        val r = x2 * w
        val b = y2 * h

        canvas.drawRect(RectF(l, t, r, b), rectPaint)

        val radius = HANDLE_RADIUS_PX.toFloat()
        canvas.drawCircle(l, t, radius, handlePaint)
        canvas.drawCircle(r, t, radius, handlePaint)
        canvas.drawCircle(l, b, radius, handlePaint)
        canvas.drawCircle(r, b, radius, handlePaint)
        canvas.drawCircle(l, t, radius, handleStroke)
        canvas.drawCircle(r, t, radius, handleStroke)
        canvas.drawCircle(l, b, radius, handleStroke)
        canvas.drawCircle(r, b, radius, handleStroke)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return false

        val nx = event.x / w
        val ny = event.y / h

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = pickHandle(nx, ny)
                if (activeHandle == HANDLE_NONE) {
                    return false
                }
                applyDrag(nx, ny)
                listener?.onRectChanged(x1, y1, x2, y2, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (activeHandle == HANDLE_NONE) return false
                applyDrag(nx, ny)
                listener?.onRectChanged(x1, y1, x2, y2, false)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = activeHandle != HANDLE_NONE
                activeHandle = HANDLE_NONE
                if (wasDragging) {
                    listener?.onRectChanged(x1, y1, x2, y2, true)
                }
                return wasDragging
            }
            else -> return false
        }
    }

    fun setRect(nx1: Float, ny1: Float, nx2: Float, ny2: Float) {
        x1 = clamp01(nx1)
        y1 = clamp01(ny1)
        x2 = clamp01(nx2)
        y2 = clamp01(ny2)
        if (x2 - x1 < MIN_SIZE) x2 = clamp01(x1 + MIN_SIZE)
        if (y2 - y1 < MIN_SIZE) y2 = clamp01(y1 + MIN_SIZE)
        invalidate()
    }

    fun resetToDefault() {
        setRect(0.15f, 0.15f, 0.85f, 0.85f)
    }

    fun getX1(): Float = x1
    fun getY1(): Float = y1
    fun getX2(): Float = x2
    fun getY2(): Float = y2

    private fun pickHandle(nx: Float, ny: Float): Int {
        val dx = kotlin.math.abs(nx - x1)
        val dy = kotlin.math.abs(ny - y1)
        val dRight = kotlin.math.abs(nx - x2)
        val dBottom = kotlin.math.abs(ny - y2)

        val tol = 0.06f
        if (dx < tol && dy < tol) return HANDLE_TL
        if (dRight < tol && dy < tol) return HANDLE_TR
        if (dx < tol && dBottom < tol) return HANDLE_BL
        if (dRight < tol && dBottom < tol) return HANDLE_BR

        if (nx > x1 && nx < x2 && ny > y1 && ny < y2) {
            return HANDLE_BODY
        }
        return HANDLE_NONE
    }

    private fun applyDrag(nx: Float, ny: Float) {
        val cnx = clamp01(nx)
        val cny = clamp01(ny)
        when (activeHandle) {
            HANDLE_TL -> {
                x1 = kotlin.math.min(cnx, x2 - MIN_SIZE)
                y1 = kotlin.math.min(cny, y2 - MIN_SIZE)
            }
            HANDLE_TR -> {
                x2 = kotlin.math.max(cnx, x1 + MIN_SIZE)
                y1 = kotlin.math.min(cny, y2 - MIN_SIZE)
            }
            HANDLE_BL -> {
                x1 = kotlin.math.min(cnx, x2 - MIN_SIZE)
                y2 = kotlin.math.max(cny, y1 + MIN_SIZE)
            }
            HANDLE_BR -> {
                x2 = kotlin.math.max(cnx, x1 + MIN_SIZE)
                y2 = kotlin.math.max(cny, y1 + MIN_SIZE)
            }
            HANDLE_BODY -> {
                val rw = x2 - x1
                val rh = y2 - y1
                var nx1 = cnx - rw / 2f
                var ny1 = cny - rh / 2f
                if (nx1 < 0f) nx1 = 0f
                if (ny1 < 0f) ny1 = 0f
                if (nx1 + rw > 1f) nx1 = 1f - rw
                if (ny1 + rh > 1f) ny1 = 1f - rh
                x1 = nx1
                y1 = ny1
                x2 = x1 + rw
                y2 = y1 + rh
            }
        }
    }

    private fun clamp01(v: Float): Float = when {
        v < 0f -> 0f
        v > 1f -> 1f
        else -> v
    }

    companion object {
        private const val MIN_SIZE = 0.05f
        private const val HANDLE_NONE = -1
        private const val HANDLE_BODY = 0
        private const val HANDLE_TL = 1
        private const val HANDLE_TR = 2
        private const val HANDLE_BL = 3
        private const val HANDLE_BR = 4

        /** Pixel radius of the corner hit-target (drawn circle). */
        private const val HANDLE_RADIUS_PX = 80
    }
}
