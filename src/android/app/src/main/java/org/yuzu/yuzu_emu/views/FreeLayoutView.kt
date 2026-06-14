// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * A FrameLayout that hosts the emulation surface and, when in edit mode, lets the user
 * freely drag the whole thing around and resize from any of the four corners with a handle.
 *
 * Edit mode visuals:
 *  - White semi-transparent bounding box around the child
 *  - Four corner circular handles (top-left / top-right / bottom-left / bottom-right)
 *  - Touching anywhere inside (but not on a handle) drags the whole view
 *
 * State is persisted externally by the caller via [saveLayout] / [restoreLayout].
 */
class FreeLayoutView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Whether edit mode is active. Toggled by EmulationFragment via shortcut menu. */
    var inEditMode: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            isClickable = value
            isFocusable = value
            invalidate()
        }

    /** Called when the user finishes editing with the new rect (in container coords). */
    var onLayoutChanged: ((left: Int, top: Int, right: Int, bottom: Int) -> Unit)? = null

    /** Called when the user wants to exit edit mode (e.g. tapped the done button). */
    var onDoneClicked: (() -> Unit)? = null

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.WHITE
        strokeWidth = dp(2f)
        alpha = 180
    }
    private val handleFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        alpha = 220
    }
    private val handleStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#1E88E5")
        strokeWidth = dp(2f)
    }

    private val handleRadiusPx = dp(14f)
    private val handleHitRadiusPx = dp(28f) // bigger touch target than visible

    private enum class DragMode { NONE, MOVE, RESIZE_TL, RESIZE_TR, RESIZE_BL, RESIZE_BR }

    private var dragMode = DragMode.NONE
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragStartLeft = 0
    private var dragStartTop = 0
    private var dragStartRight = 0
    private var dragStartBottom = 0

    /** Minimum width / height in pixels the surface can be resized to. */
    private val minWidthPx = dp(160f).toInt()
    private val minHeightPx = dp(90f).toInt()

    init {
        // We only paint chrome in edit mode; pass-through when not.
        setWillNotDraw(true)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!inEditMode) return

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Bounding rectangle
        val halfStroke = borderPaint.strokeWidth / 2f
        val rect = RectF(halfStroke, halfStroke, w - halfStroke, h - halfStroke)
        canvas.drawRect(rect, borderPaint)

        // Corner handles
        drawHandle(canvas, 0f, 0f)                          // TL
        drawHandle(canvas, w, 0f)                          // TR
        drawHandle(canvas, 0f, h)                          // BL
        drawHandle(canvas, w, h)                          // BR
    }

    private fun drawHandle(canvas: Canvas, cx: Float, cy: Float) {
        canvas.drawCircle(cx, cy, handleRadiusPx, handleFillPaint)
        canvas.drawCircle(cx, cy, handleRadiusPx, handleStrokePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inEditMode) return false

        val x = event.x
        val y = event.y
        val w = width.toFloat()
        val h = height.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartRawX = event.rawX
                dragStartRawY = event.rawY
                dragStartLeft = left
                dragStartTop = top
                dragStartRight = right
                dragStartBottom = bottom

                dragMode = when {
                    nearCorner(x, y, 0f, 0f) -> DragMode.RESIZE_TL
                    nearCorner(x, y, w, 0f) -> DragMode.RESIZE_TR
                    nearCorner(x, y, 0f, h) -> DragMode.RESIZE_BL
                    nearCorner(x, y, w, h) -> DragMode.RESIZE_BR
                    else -> DragMode.MOVE
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragMode != DragMode.NONE) {
                val dx = event.rawX - dragStartRawX
                val dy = event.rawY - dragStartRawY
                applyDrag(dx, dy)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragMode != DragMode.NONE) {
                    onLayoutChanged?.invoke(left, top, right, bottom)
                }
                dragMode = DragMode.NONE
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun nearCorner(x: Float, y: Float, cx: Float, cy: Float): Boolean {
        val dx = x - cx
        val dy = y - cy
        return dx * dx + dy * dy <= handleHitRadiusPx * handleHitRadiusPx
    }

    private fun applyDrag(dx: Float, dy: Float) {
        val parentW = (parent as? View)?.width ?: return
        val parentH = (parent as? View)?.height ?: return

        var newLeft = dragStartLeft
        var newTop = dragStartTop
        var newRight = dragStartRight
        var newBottom = dragStartBottom

        when (dragMode) {
            DragMode.MOVE -> {
                newLeft = clamp(dragStartLeft + dx.toInt(), 0, parentW - width)
                newTop = clamp(dragStartTop + dy.toInt(), 0, parentH - height)
                newRight = newLeft + width
                newBottom = newTop + height
            }
            DragMode.RESIZE_TL -> {
                newLeft = clamp(dragStartLeft + dx.toInt(), 0, dragStartRight - minWidthPx)
                newTop = clamp(dragStartTop + dy.toInt(), 0, dragStartBottom - minHeightPx)
            }
            DragMode.RESIZE_TR -> {
                newRight = clamp(dragStartRight + dx.toInt(), dragStartLeft + minWidthPx, parentW)
                newTop = clamp(dragStartTop + dy.toInt(), 0, dragStartBottom - minHeightPx)
            }
            DragMode.RESIZE_BL -> {
                newLeft = clamp(dragStartLeft + dx.toInt(), 0, dragStartRight - minWidthPx)
                newBottom = clamp(dragStartBottom + dy.toInt(), dragStartTop + minHeightPx, parentH)
            }
            DragMode.RESIZE_BR -> {
                newRight = clamp(dragStartRight + dx.toInt(), dragStartLeft + minWidthPx, parentW)
                newBottom = clamp(dragStartBottom + dy.toInt(), dragStartTop + minHeightPx, parentH)
            }
            else -> return
        }

        layout(newLeft, newTop, newRight, newBottom)
    }

    private fun clamp(v: Int, lo: Int, hi: Int): Int = max(lo, min(hi, v))

    private fun dp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)

    /**
     * Snap back to centered, fullscreen layout. Used by reset button.
     */
    fun resetToFullscreen() {
        val parentW = (parent as? View)?.width ?: return
        val parentH = (parent as? View)?.height ?: return
        layout(0, 0, parentW, parentH)
        onLayoutChanged?.invoke(left, top, right, bottom)
    }
}
