// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.View.OnTouchListener
import android.view.WindowInsets
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.ContextCompat
import androidx.window.layout.WindowMetricsCalculator
import kotlin.math.max
import kotlin.math.min
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.features.input.NativeInput
import org.yuzu.yuzu_emu.features.input.NativeInput.ButtonState
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.features.input.model.NativeAnalog
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.features.input.model.NpadStyleIndex
import org.yuzu.yuzu_emu.features.settings.model.BooleanSetting
import org.yuzu.yuzu_emu.features.settings.model.IntSetting
import org.yuzu.yuzu_emu.overlay.model.ComboPreset
import org.yuzu.yuzu_emu.overlay.model.ComboStore
import org.yuzu.yuzu_emu.overlay.model.OverlayControl
import org.yuzu.yuzu_emu.overlay.model.OverlayControlData
import org.yuzu.yuzu_emu.overlay.model.OverlayLayout
import org.yuzu.yuzu_emu.utils.NativeConfig

/**
 * Draws the interactive input overlay on top of the
 * emulation rendering surface.
 */
class InputOverlay(context: Context, attrs: AttributeSet?) :
    View(context, attrs),
    OnTouchListener {
    private val overlayButtons: MutableSet<InputOverlayDrawableButton> = HashSet()
    private val overlayDpads: MutableSet<InputOverlayDrawableDpad> = HashSet()
    private val overlayJoysticks: MutableSet<InputOverlayDrawableJoystick> = HashSet()
    private val overlayCombos: MutableList<InputOverlayDrawableCombo> = mutableListOf()
    private val imeEditable = Editable.Factory.getInstance().newEditable("")

    private var inEditMode = false
    private var gamelessMode = false
    private var buttonBeingConfigured: InputOverlayDrawableButton? = null
    private var dpadBeingConfigured: InputOverlayDrawableDpad? = null
    private var joystickBeingConfigured: InputOverlayDrawableJoystick? = null
    private var comboBeingConfigured: InputOverlayDrawableCombo? = null

    private var scaleDialog: OverlayScaleDialog? = null
    private var touchStartX = 0f
    private var touchStartY = 0f
    private var hasMoved = false
    private val moveThreshold = 20f

    // Long-press state for combo pads in edit mode.
    private var comboLongPressFired = false
    private val comboLongPressTimeoutMs = 500L
    private val comboLongPressRunnable = Runnable {
        val target = comboBeingConfigured ?: return@Runnable
        comboLongPressFired = true
        // Release the gesture so the subsequent ACTION_UP doesn't save
        // the position or fire the tap listener.
        comboBeingConfigured = null
        hasMoved = true
        comboEditLongPressListener?.invoke(target.preset.id)
    }

    private val gridPaint = Paint().apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }

    private lateinit var windowInsets: WindowInsets

    var layout = OverlayLayout.Landscape

    // External listener for EmulationFragment joypad overlay auto-hide
    var touchEventListener: ((MotionEvent) -> Unit)? = null

    /**
     * Fired when the user taps (no drag) a combo pad while in edit mode.
     * The argument is the combo preset id. The host activity can use this
     * to open the combo editor focused on that combo.
     */
    var comboEditTapListener: ((String) -> Unit)? = null

    /**
     * Fired when the user long-presses a combo pad while in edit mode.
     * The host activity can show a delete / edit menu.
     */
    var comboEditLongPressListener: ((String) -> Unit)? = null

    /**
     * Fired when the user taps a free area of the overlay in edit mode
     * (no combo or button was hit). The host can use this to open the
     * combo manager for adding a new combo.
     */
    var overlayEditEmptyTapListener: (() -> Unit)? = null

    /** Returns the active combo pads (for the editor to inspect). */
    fun comboDrawables(): List<InputOverlayDrawableCombo> = overlayCombos.toList()

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        imeEditable.clear()
        outAttrs.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_DONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        return object : BaseInputConnection(this, true) {
            override fun getEditable(): Editable = imeEditable

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!text.isNullOrEmpty()) {
                    forwardCommittedText(text)
                }
                return super.commitText(text, newCursorPosition)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength.coerceAtLeast(0)) {
                    NativeLibrary.submitInlineKeyboardInput(KeyEvent.KEYCODE_DEL)
                }
                return super.deleteSurroundingText(beforeLength, afterLength)
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) {
                    return true
                }

                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_DEL,
                    KeyEvent.KEYCODE_ENTER -> {
                        NativeLibrary.submitInlineKeyboardInput(event.keyCode)
                    }
                    else -> {
                        val textChar = event.unicodeChar
                        if (textChar != 0) {
                            NativeLibrary.submitInlineKeyboardText(textChar.toChar().toString())
                        }
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean {
                NativeLibrary.submitInlineKeyboardInput(KeyEvent.KEYCODE_ENTER)
                return true
            }
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)

        windowInsets = rootWindowInsets

        val overlayControlData = NativeConfig.getOverlayControlData()
        if (overlayControlData.isEmpty()) {
            populateDefaultConfig()
        } else {
            checkForNewControls(overlayControlData)
        }

        // Load the controls.
        refreshControls()

        // Set the on touch listener.
        setOnTouchListener(this)

        // Force draw
        setWillNotDraw(false)

        // Request focus for the overlay so it has priority on presses.
        requestFocus()
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw grid when in edit mode and snap-to-grid is enabled
        if (inEditMode && BooleanSetting.OVERLAY_SNAP_TO_GRID.getBoolean()) {
            drawGrid(canvas)
        }

        for (button in overlayButtons) {
            button.draw(canvas)
        }
        for (dpad in overlayDpads) {
            dpad.draw(canvas)
        }
        for (joystick in overlayJoysticks) {
            joystick.draw(canvas)
        }
        for (combo in overlayCombos) {
            combo.draw(canvas)
        }
    }

    private fun forwardCommittedText(text: CharSequence) {
        val builder = StringBuilder()
        text.forEach { character ->
            when (character) {
                '\n' -> {
                    if (builder.isNotEmpty()) {
                        NativeLibrary.submitInlineKeyboardText(builder.toString())
                        builder.clear()
                    }
                    NativeLibrary.submitInlineKeyboardInput(KeyEvent.KEYCODE_ENTER)
                }
                else -> builder.append(character)
            }
        }
        if (builder.isNotEmpty()) {
            NativeLibrary.submitInlineKeyboardText(builder.toString())
        }
    }

    private fun drawGrid(canvas: Canvas) {
        val gridSize = IntSetting.OVERLAY_GRID_SIZE.getInt()
        val width = canvas.width
        val height = canvas.height

        // Draw vertical lines
        var x = 0
        while (x <= width) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
            x += gridSize
        }

        // Draw horizontal lines
        var y = 0
        while (y <= height) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
            y += gridSize
        }
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        try {
            touchEventListener?.invoke(event)
        } catch (e: Exception) {}

        if (inEditMode) {
            return onTouchWhileEditing(event)
        }

        var shouldUpdateView = false
        val playerIndex = when (NativeInput.getStyleIndex(0)) {
            NpadStyleIndex.Handheld -> 8
            else -> 0
        }

        for (button in overlayButtons) {
            if (!button.updateStatus(event)) {
                continue
            }
            NativeInput.onOverlayButtonEvent(
                playerIndex,
                button.button,
                button.status
            )
            playHaptics(event)
            shouldUpdateView = true
        }

        for (dpad in overlayDpads) {
            if (!dpad.updateStatus(event, BooleanSetting.DPAD_SLIDE.getBoolean())) {
                continue
            }
            NativeInput.onOverlayButtonEvent(
                playerIndex,
                dpad.up,
                dpad.upStatus
            )
            NativeInput.onOverlayButtonEvent(
                playerIndex,
                dpad.down,
                dpad.downStatus
            )
            NativeInput.onOverlayButtonEvent(
                playerIndex,
                dpad.left,
                dpad.leftStatus
            )
            NativeInput.onOverlayButtonEvent(
                playerIndex,
                dpad.right,
                dpad.rightStatus
            )
            playHaptics(event)
            shouldUpdateView = true
        }

        for (joystick in overlayJoysticks) {
            if (!joystick.updateStatus(event)) {
                continue
            }
            NativeInput.onOverlayJoystickEvent(
                playerIndex,
                joystick.joystick,
                joystick.xAxis,
                joystick.realYAxis
            )
            NativeInput.onOverlayButtonEvent(
                playerIndex,
                joystick.button,
                joystick.buttonStatus
            )
            playHaptics(event)
            shouldUpdateView = true
        }

        for (combo in overlayCombos) {
            val actions = combo.updateStatus(event)
            if (actions.isEmpty()) continue
            // Haptic feedback on the leading edge of the press. The
            // drawable itself forwards individual button events to
            // native via the onButtonEvent callback supplied at
            // construction time, so we don't have to do that here.
            if (actions.any { it.second }) playHaptics(event)
            shouldUpdateView = true
        }

        if (shouldUpdateView) {
            invalidate()
        }

        if (!BooleanSetting.TOUCHSCREEN.getBoolean()) {
            return true
        }

        val pointerIndex = event.actionIndex
        val xPosition = event.getX(pointerIndex).toInt()
        val yPosition = event.getY(pointerIndex).toInt()
        val pointerId = event.getPointerId(pointerIndex)
        val motionEvent = event.action and MotionEvent.ACTION_MASK
        val isActionDown =
            motionEvent == MotionEvent.ACTION_DOWN || motionEvent == MotionEvent.ACTION_POINTER_DOWN
        val isActionMove = motionEvent == MotionEvent.ACTION_MOVE
        val isActionUp =
            motionEvent == MotionEvent.ACTION_UP || motionEvent == MotionEvent.ACTION_POINTER_UP

        if (isActionDown && !isTouchInputConsumed(pointerId) && !isTouchConsumedByCombo(event)) {
            NativeInput.onTouchPressed(pointerId, xPosition.toFloat(), yPosition.toFloat())
        }

        if (isActionMove) {
            for (i in 0 until event.pointerCount) {
                val fingerId = event.getPointerId(i)
                if (isTouchInputConsumed(fingerId)) {
                    continue
                }
                if (isTouchConsumedByCombo(event)) {
                    continue
                }
                NativeInput.onTouchMoved(fingerId, event.getX(i), event.getY(i))
            }
        }

        if (isActionUp && !isTouchInputConsumed(pointerId) && !isTouchConsumedByCombo(event)) {
            NativeInput.onTouchReleased(pointerId)
        }

        return true
    }

    private fun playHaptics(event: MotionEvent) {
        if (BooleanSetting.HAPTIC_FEEDBACK.getBoolean()) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN ->
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP ->
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY_RELEASE)
            }
        }
    }

    private fun isTouchInputConsumed(track_id: Int): Boolean {
        for (button in overlayButtons) {
            if (button.trackId == track_id) {
                return true
            }
        }
        for (dpad in overlayDpads) {
            if (dpad.trackId == track_id) {
                return true
            }
        }
        for (joystick in overlayJoysticks) {
            if (joystick.trackId == track_id) {
                return true
            }
        }
        return false
    }

    /** Whether (x,y) is inside the bounds of any visible control. */
    private fun isTouchOnAnyControl(x: Float, y: Float): Boolean {
        for (button in overlayButtons) {
            if (button.bounds.contains(x.toInt(), y.toInt())) return true
        }
        for (dpad in overlayDpads) {
            if (dpad.bounds.contains(x.toInt(), y.toInt())) return true
        }
        for (joystick in overlayJoysticks) {
            if (joystick.bounds.contains(x.toInt(), y.toInt())) return true
        }
        for (combo in overlayCombos) {
            if (combo.boundsRect().contains(x, y)) return true
        }
        return false
    }

    /**
     * True if [track_id] is currently captured by any combo pad (so the
     * screen-touch passthrough should ignore this finger). The combo pad
     * does not expose its pointer table publicly; we treat any pointer
     * landing inside a combo pad's bounds as consumed for the purposes
     * of forwarding to the on-screen touch driver.
     */
    private fun isTouchConsumedByCombo(event: MotionEvent): Boolean {
        if (overlayCombos.isEmpty()) return false
        val motionEvent = event.actionMasked
        val isMove = motionEvent == MotionEvent.ACTION_MOVE
        val start = if (isMove) 0 else event.actionIndex
        val end = if (isMove) event.pointerCount else start + 1
        for (i in start until end) {
            val x = event.getX(i)
            val y = event.getY(i)
            for (combo in overlayCombos) {
                if (combo.boundsRect().contains(x, y)) return true
            }
        }
        return false
    }

    private fun onTouchWhileEditing(event: MotionEvent): Boolean {
        val pointerIndex = event.actionIndex
        val fingerPositionX = event.getX(pointerIndex).toInt()
        val fingerPositionY = event.getY(pointerIndex).toInt()

        for (button in overlayButtons) {
            // Determine the button state to apply based on the MotionEvent action flag.
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN ->
                    // If no button is being moved now, remember the currently touched button to move.
                    if (buttonBeingConfigured == null &&
                        button.bounds.contains(fingerPositionX, fingerPositionY)
                    ) {
                        buttonBeingConfigured = button
                        buttonBeingConfigured!!.onConfigureTouch(event)
                        touchStartX = event.getX(pointerIndex)
                        touchStartY = event.getY(pointerIndex)
                        hasMoved = false
                    }

                MotionEvent.ACTION_MOVE -> if (buttonBeingConfigured != null) {
                    val moveDistance = kotlin.math.sqrt(
                        (event.getX(pointerIndex) - touchStartX).let { it * it } +
                                (event.getY(pointerIndex) - touchStartY).let { it * it }
                    )

                    if (moveDistance > moveThreshold) {
                        hasMoved = true
                        buttonBeingConfigured!!.onConfigureTouch(event)
                        invalidate()
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (buttonBeingConfigured === button) {
                    if (!hasMoved) {
                        showScaleDialog(
                            buttonBeingConfigured,
                            null,
                            null,
                            fingerPositionX,
                            fingerPositionY
                        )
                    } else {
                        saveControlPosition(
                            buttonBeingConfigured!!.overlayControlData.id,
                            buttonBeingConfigured!!.bounds.centerX(),
                            buttonBeingConfigured!!.bounds.centerY(),
                            individuaScale = buttonBeingConfigured!!.overlayControlData.individualScale,
                            layout
                        )
                    }
                    buttonBeingConfigured = null
                }
            }
        }

        for (dpad in overlayDpads) {
            // Determine the button state to apply based on the MotionEvent action flag.
            when (event.action and MotionEvent.ACTION_MASK) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN ->
                    // If no button is being moved now, remember the currently touched button to move.
                    if (buttonBeingConfigured == null &&
                        dpad.bounds.contains(fingerPositionX, fingerPositionY)
                    ) {
                        dpadBeingConfigured = dpad
                        dpadBeingConfigured!!.onConfigureTouch(event)
                        touchStartX = event.getX(pointerIndex)
                        touchStartY = event.getY(pointerIndex)
                        hasMoved = false
                    }

                MotionEvent.ACTION_MOVE -> if (dpadBeingConfigured != null) {
                    val moveDistance = kotlin.math.sqrt(
                        (event.getX(pointerIndex) - touchStartX).let { it * it } +
                                (event.getY(pointerIndex) - touchStartY).let { it * it }
                    )

                    if (moveDistance > moveThreshold) {
                        hasMoved = true
                        dpadBeingConfigured!!.onConfigureTouch(event)
                        invalidate()
                        return true
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (dpadBeingConfigured === dpad) {
                    if (!hasMoved) {
                        // This was a click, show scale dialog for dpad
                        showScaleDialog(
                            null,
                            dpadBeingConfigured,
                            null,
                            fingerPositionX,
                            fingerPositionY
                        )
                    } else {
                        // This was a move, save position
                        saveControlPosition(
                            OverlayControl.COMBINED_DPAD.id,
                            dpadBeingConfigured!!.bounds.centerX(),
                            dpadBeingConfigured!!.bounds.centerY(),
                            individuaScale = dpadBeingConfigured!!.individualScale,
                            layout
                        )
                    }
                    dpadBeingConfigured = null
                }
            }
        }

        for (joystick in overlayJoysticks) {
            when (event.action) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> if (joystickBeingConfigured == null &&
                    joystick.bounds.contains(fingerPositionX, fingerPositionY)
                ) {
                    joystickBeingConfigured = joystick
                    joystickBeingConfigured!!.onConfigureTouch(event)
                    touchStartX = event.getX(pointerIndex)
                    touchStartY = event.getY(pointerIndex)
                    hasMoved = false
                }

                MotionEvent.ACTION_MOVE -> if (joystickBeingConfigured != null) {
                    val moveDistance = kotlin.math.sqrt(
                        (event.getX(pointerIndex) - touchStartX).let { it * it } +
                                (event.getY(pointerIndex) - touchStartY).let { it * it }
                    )

                    if (moveDistance > moveThreshold) {
                        hasMoved = true
                        joystickBeingConfigured!!.onConfigureTouch(event)
                        invalidate()
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (joystickBeingConfigured != null) {
                    if (!hasMoved) {
                        showScaleDialog(
                            null,
                            null,
                            joystickBeingConfigured,
                            fingerPositionX,
                            fingerPositionY
                        )
                    } else {
                        saveControlPosition(
                            joystickBeingConfigured!!.prefId,
                            joystickBeingConfigured!!.bounds.centerX(),
                            joystickBeingConfigured!!.bounds.centerY(),
                            individuaScale = joystickBeingConfigured!!.individualScale,
                            layout
                        )
                    }
                    joystickBeingConfigured = null
                }
            }
        }

        for (combo in overlayCombos) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN,
                MotionEvent.ACTION_POINTER_DOWN -> if (comboBeingConfigured == null &&
                    combo.boundsRect().contains(
                        event.getX(pointerIndex),
                        event.getY(pointerIndex)
                    )
                ) {
                    comboBeingConfigured = combo
                    comboBeingConfigured!!.onConfigureTouch(event)
                    touchStartX = event.getX(pointerIndex)
                    touchStartY = event.getY(pointerIndex)
                    hasMoved = false
                    comboLongPressFired = false
                    postDelayed(comboLongPressRunnable, comboLongPressTimeoutMs)
                }

                MotionEvent.ACTION_MOVE -> if (comboBeingConfigured != null) {
                    val moveDistance = kotlin.math.sqrt(
                        (event.getX(pointerIndex) - touchStartX).let { it * it } +
                                (event.getY(pointerIndex) - touchStartY).let { it * it }
                    )

                    if (moveDistance > moveThreshold) {
                        hasMoved = true
                        comboBeingConfigured!!.onConfigureTouch(event)
                        removeCallbacks(comboLongPressRunnable)
                        invalidate()
                    }
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_POINTER_UP -> if (comboBeingConfigured === combo) {
                    removeCallbacks(comboLongPressRunnable)
                    if (comboLongPressFired) {
                        // Long press already handled - just reset.
                    } else if (hasMoved) {
                        saveComboPosition(comboBeingConfigured!!, layout)
                    } else {
                        // Tap on a combo pad in edit mode - tell listener so the
                        // editor UI can focus / edit this combo.
                        // Post to avoid re-entering refreshControls() (which
                        // clears overlayCombos) while we are still iterating
                        // the list inside the current onTouch call.
                        val tappedId = comboBeingConfigured!!.preset.id
                        post { comboEditTapListener?.invoke(tappedId) }
                    }
                    comboBeingConfigured = null
                }
            }
        }

        // If no control claimed the touch and we're in edit mode, treat a
        // single tap on empty space as "add new combo".
        if (event.actionMasked == MotionEvent.ACTION_UP &&
            comboBeingConfigured == null &&
            buttonBeingConfigured == null &&
            dpadBeingConfigured == null &&
            joystickBeingConfigured == null &&
            !hasMoved
        ) {
            val onControl = isTouchOnAnyControl(event.getX(pointerIndex), event.getY(pointerIndex))
            if (!onControl) {
                post { overlayEditEmptyTapListener?.invoke() }
            }
        }

        return true
    }

    private fun addOverlayControls(layout: OverlayLayout) {
        val windowSize = getSafeScreenSize(context, Pair(measuredWidth, measuredHeight))
        val overlayControlData = NativeConfig.getOverlayControlData()
        for (data in overlayControlData) {
            if (!data.enabled) {
                continue
            }

            val position = data.positionFromLayout(layout)
            when (data.id) {
                OverlayControl.BUTTON_A.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_a,
                            R.drawable.facebutton_a_depressed,
                            NativeButton.A,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_B.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_b,
                            R.drawable.facebutton_b_depressed,
                            NativeButton.B,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_X.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_x,
                            R.drawable.facebutton_x_depressed,
                            NativeButton.X,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_Y.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_y,
                            R.drawable.facebutton_y_depressed,
                            NativeButton.Y,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_PLUS.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_plus,
                            R.drawable.facebutton_plus_depressed,
                            NativeButton.Plus,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_MINUS.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_minus,
                            R.drawable.facebutton_minus_depressed,
                            NativeButton.Minus,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_HOME.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_home,
                            R.drawable.facebutton_home_depressed,
                            NativeButton.Home,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_CAPTURE.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.facebutton_screenshot,
                            R.drawable.facebutton_screenshot_depressed,
                            NativeButton.Capture,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_L.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.l_shoulder,
                            R.drawable.l_shoulder_depressed,
                            NativeButton.L,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_R.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.r_shoulder,
                            R.drawable.r_shoulder_depressed,
                            NativeButton.R,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_ZL.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.zl_trigger,
                            R.drawable.zl_trigger_depressed,
                            NativeButton.ZL,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_ZR.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.zr_trigger,
                            R.drawable.zr_trigger_depressed,
                            NativeButton.ZR,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_STICK_L.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.button_l3,
                            R.drawable.button_l3_depressed,
                            NativeButton.LStick,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.BUTTON_STICK_R.id -> {
                    overlayButtons.add(
                        initializeOverlayButton(
                            context,
                            windowSize,
                            R.drawable.button_r3,
                            R.drawable.button_r3_depressed,
                            NativeButton.RStick,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.STICK_L.id -> {
                    overlayJoysticks.add(
                        initializeOverlayJoystick(
                            context,
                            windowSize,
                            R.drawable.joystick_range,
                            R.drawable.joystick,
                            R.drawable.joystick_depressed,
                            NativeAnalog.LStick,
                            NativeButton.LStick,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.STICK_R.id -> {
                    overlayJoysticks.add(
                        initializeOverlayJoystick(
                            context,
                            windowSize,
                            R.drawable.joystick_range,
                            R.drawable.joystick,
                            R.drawable.joystick_depressed,
                            NativeAnalog.RStick,
                            NativeButton.RStick,
                            data,
                            position
                        )
                    )
                }

                OverlayControl.COMBINED_DPAD.id -> {
                    overlayDpads.add(
                        initializeOverlayDpad(
                            context,
                            windowSize,
                            R.drawable.dpad_standard,
                            R.drawable.dpad_standard_cardinal_depressed,
                            R.drawable.dpad_standard_diagonal_depressed,
                            position
                        )
                    )
                }
            }
        }
    }

    fun refreshControls(gameless: Boolean = false) {
        // Store gameless mode if set to true
        if (gameless) {
            gamelessMode = true
        }

        // Remove all the overlay buttons from the HashSet.
        overlayButtons.clear()
        overlayDpads.clear()
        overlayJoysticks.clear()
        overlayCombos.clear()

        // Add all the enabled overlay items back to the HashSet.
        if (gamelessMode || BooleanSetting.SHOW_INPUT_OVERLAY.getBoolean()) {
            addOverlayControls(layout)
            addOverlayCombos(layout)
        }
        invalidate()
    }

    /**
     * Load combo presets from [ComboStore] and lay them out on the overlay.
     * Combos honour their own per-combo `enabled` flag independently of
     * [BooleanSetting.SHOW_INPUT_OVERLAY].
     */
    private fun addOverlayCombos(layout: OverlayLayout) {
        val windowSize = getSafeScreenSize(context, Pair(measuredWidth, measuredHeight))
        val min = windowSize.first
        val max = windowSize.second
        val presets = ComboStore.load(context)
        val baseSize = minOf(max.x - min.x, max.y - min.y)
        val playerIndex = when (NativeInput.getStyleIndex(0)) {
            NpadStyleIndex.Handheld -> 8
            else -> 0
        }

        for (preset in presets) {
            if (!preset.enabled) continue
            val drawable = InputOverlayDrawableCombo(
                resources,
                preset,
                onButtonEvent = { button, pressed ->
                    // Forward macro / chord steps as they happen. The
                    // sender is the drawable itself; we just route to
                    // native here.
                    NativeInput.onOverlayButtonEvent(
                        playerIndex,
                        button,
                        if (pressed) ButtonState.PRESSED else ButtonState.RELEASED,
                    )
                }
            )
            val position = preset.positionFromLayout(layout)
            val scale = (IntSetting.OVERLAY_SCALE.getInt() + 50).toFloat() / 100f
            // Pad needs to fit the user-defined label; make it wider if
            // the label is long. Base 14% of the smaller screen side;
            // grow ~1% per extra character past a baseline of 6.
            val nameLen = preset.displayName.length.coerceAtLeast(1)
            val pct = (0.12f + (nameLen - 2).coerceAtLeast(0) * 0.012f)
                .coerceIn(0.12f, 0.30f)
            val size = (baseSize * pct * scale * preset.individualScale).toInt()
                .coerceAtLeast(120)
            val drawableX = (position.first * max.x + min.x).toInt() - size / 2
            val drawableY = (position.second * max.y + min.y).toInt() - size / 2
            drawable.setBounds(drawableX, drawableY, drawableX + size, drawableY + size)
            overlayCombos += drawable
        }
    }

    private fun saveControlPosition(
        id: String,
        x: Int,
        y: Int,
        individuaScale: Float,
        layout: OverlayLayout
    ) {
        val windowSize = getSafeScreenSize(context, Pair(measuredWidth, measuredHeight))
        val min = windowSize.first
        val max = windowSize.second
        val overlayControlData = NativeConfig.getOverlayControlData()
        val data = overlayControlData.firstOrNull { it.id == id }
        val newPosition = Pair((x - min.x).toDouble() / max.x, (y - min.y).toDouble() / max.y)

        when (layout) {
            OverlayLayout.Landscape -> data?.landscapePosition = newPosition
            OverlayLayout.Portrait -> data?.portraitPosition = newPosition
            OverlayLayout.Foldable -> data?.foldablePosition = newPosition

        }

        data?.individualScale = individuaScale

        NativeConfig.setOverlayControlData(overlayControlData)
    }

    /** Persist a combo pad's new position back to [ComboStore]. */
    private fun saveComboPosition(combo: InputOverlayDrawableCombo, layout: OverlayLayout) {
        val windowSize = getSafeScreenSize(context, Pair(measuredWidth, measuredHeight))
        val min = windowSize.first
        val max = windowSize.second
        val bounds = combo.boundsRect()
        val newPosition = Pair(
            ((bounds.centerX() - min.x).toDouble() / max.x).coerceIn(0.0, 1.0),
            ((bounds.centerY() - min.y).toDouble() / max.y).coerceIn(0.0, 1.0)
        )
        val presets = ComboStore.load(context)
        val target = presets.firstOrNull { it.id == combo.preset.id } ?: return
        when (layout) {
            OverlayLayout.Landscape -> target.landscapePosition = newPosition
            OverlayLayout.Portrait -> target.portraitPosition = newPosition
            OverlayLayout.Foldable -> target.foldablePosition = newPosition
        }
        ComboStore.save(context, presets)
        // Defer refresh so we don't mutate overlayCombos while still inside
        // the onTouch iteration that called us.
        post { refreshControls() }
    }

    fun setIsInEditMode(editMode: Boolean) {
        inEditMode = editMode
        if (!editMode) {
            scaleDialog?.dismiss()
            scaleDialog = null
            gamelessMode = false
            comboBeingConfigured = null
            removeCallbacks(comboLongPressRunnable)
            overlayCombos.forEach { it.reset() }
        }

        invalidate()
    }

    fun isGamelessMode(): Boolean = gamelessMode

    private fun showScaleDialog(
        button: InputOverlayDrawableButton?,
        dpad: InputOverlayDrawableDpad?,
        joystick: InputOverlayDrawableJoystick?,
        x: Int, y: Int
    ) {
        val overlayControlData = NativeConfig.getOverlayControlData()
        // prevent dialog from being spam opened
        scaleDialog?.dismiss()


        when {
            button != null -> {
                val buttonData =
                    overlayControlData.firstOrNull { it.id == button.overlayControlData.id }
                if (buttonData != null) {
                    scaleDialog =
                        OverlayScaleDialog(context, button.overlayControlData) { newScale ->
                            saveControlPosition(
                                button.overlayControlData.id,
                                button.bounds.centerX(),
                                button.bounds.centerY(),
                                individuaScale = newScale,
                                layout
                            )
                            refreshControls()
                        }

                    scaleDialog?.showDialog(x,y, button.bounds.width(), button.bounds.height())

                }
            }

            dpad != null -> {
                val dpadData =
                    overlayControlData.firstOrNull { it.id == OverlayControl.COMBINED_DPAD.id }
                if (dpadData != null) {
                    scaleDialog = OverlayScaleDialog(context, dpadData) { newScale ->
                        saveControlPosition(
                            OverlayControl.COMBINED_DPAD.id,
                            dpad.bounds.centerX(),
                            dpad.bounds.centerY(),
                            newScale,
                            layout
                        )

                        refreshControls()
                    }

                    scaleDialog?.showDialog(x,y, dpad.bounds.width(), dpad.bounds.height())

                }
            }

            joystick != null -> {
                val joystickData = overlayControlData.firstOrNull { it.id == joystick.prefId }
                if (joystickData != null) {
                    scaleDialog = OverlayScaleDialog(context, joystickData) { newScale ->
                        saveControlPosition(
                            joystick.prefId,
                            joystick.bounds.centerX(),
                            joystick.bounds.centerY(),
                            individuaScale = newScale,
                            layout
                        )

                        refreshControls()
                    }

                    scaleDialog?.showDialog(x,y, joystick.bounds.width(), joystick.bounds.height())

                }
            }
        }
    }


    /**
     * Applies and saves all default values for the overlay
     */
    private fun populateDefaultConfig() {
        val newConfig = OverlayControl.entries.map { it.toOverlayControlData() }
        NativeConfig.setOverlayControlData(newConfig.toTypedArray())
        NativeConfig.saveGlobalConfig()
    }

    /**
     * Checks if any new controls were added to OverlayControl that do not exist within deserialized
     * config and adds / saves them if necessary
     *
     * @param overlayControlData Overlay control data from [NativeConfig.getOverlayControlData]
     */
    private fun checkForNewControls(overlayControlData: Array<OverlayControlData>) {
        val missingControls = mutableListOf<OverlayControlData>()
        OverlayControl.entries.forEach { defaultControl ->
            val controlData = overlayControlData.firstOrNull { it.id == defaultControl.id }
            if (controlData == null) {
                missingControls.add(defaultControl.toOverlayControlData())
            }
        }

        if (missingControls.isNotEmpty()) {
            NativeConfig.setOverlayControlData(
                arrayOf(*overlayControlData, *(missingControls.toTypedArray()))
            )
            NativeConfig.saveGlobalConfig()
        }
    }

    fun resetLayoutVisibilityAndPlacement() {
        defaultOverlayPositionByLayout(layout)

        val overlayControlData = NativeConfig.getOverlayControlData()
        overlayControlData.forEach {
            it.enabled = OverlayControl.from(it.id)?.defaultVisibility == true
            it.individualScale = OverlayControl.from(it.id)?.defaultIndividualScaleResource!!
        }
        NativeConfig.setOverlayControlData(overlayControlData)

        // Reset combos: keep current presets but reset their layout positions.
        val combos = ComboStore.load(context)
        combos.forEach {
            it.landscapePosition = ComboPreset.BUILT_IN_PRESETS.firstOrNull { p -> p.id == it.id }
                ?.landscapePosition ?: it.landscapePosition
            it.portraitPosition = ComboPreset.BUILT_IN_PRESETS.firstOrNull { p -> p.id == it.id }
                ?.portraitPosition ?: it.portraitPosition
            it.foldablePosition = ComboPreset.BUILT_IN_PRESETS.firstOrNull { p -> p.id == it.id }
                ?.foldablePosition ?: it.foldablePosition
            it.individualScale = 1.0f
        }
        ComboStore.save(context, combos)

        refreshControls()
    }

    fun resetIndividualControlScale() {
        val overlayControlData = NativeConfig.getOverlayControlData()
        overlayControlData.forEach { data ->
            val defaultControlData = OverlayControl.from(data.id) ?: return@forEach
            data.individualScale = defaultControlData.defaultIndividualScaleResource
        }
        NativeConfig.setOverlayControlData(overlayControlData)
        NativeConfig.saveGlobalConfig()
        refreshControls()
    }

    private fun defaultOverlayPositionByLayout(layout: OverlayLayout) {
        val overlayControlData = NativeConfig.getOverlayControlData()
        for (data in overlayControlData) {
            val defaultControlData = OverlayControl.from(data.id) ?: continue
            val position = defaultControlData.getDefaultPositionForLayout(layout)
            when (layout) {
                OverlayLayout.Landscape -> data.landscapePosition = position
                OverlayLayout.Portrait -> data.portraitPosition = position
                OverlayLayout.Foldable -> data.foldablePosition = position
            }
        }
        NativeConfig.setOverlayControlData(overlayControlData)
    }

    override fun isInEditMode(): Boolean {
        return inEditMode
    }

    companion object {

        // Increase this number every time there is a breaking change to every overlay layout
        const val OVERLAY_VERSION = 1

        // Increase the corresponding layout version number whenever that layout has a breaking change
        private const val LANDSCAPE_OVERLAY_VERSION = 1
        private const val PORTRAIT_OVERLAY_VERSION = 1
        private const val FOLDABLE_OVERLAY_VERSION = 1
        val overlayLayoutVersions = listOf(
            LANDSCAPE_OVERLAY_VERSION,
            PORTRAIT_OVERLAY_VERSION,
            FOLDABLE_OVERLAY_VERSION
        )

        /**
         * Map a drawable resource id to the filename used inside a theme zip
         * (and in res/drawable-nodpi). Returns null for resources that don't
         * have a theme equivalent (none right now, but the indirection keeps
         * the code flexible).
         */
        private fun drawableToAssetName(drawableId: Int): String? = when (drawableId) {
            R.drawable.facebutton_a -> "facebutton_a.png"
            R.drawable.facebutton_a_depressed -> "facebutton_a_depressed.png"
            R.drawable.facebutton_b -> "facebutton_b.png"
            R.drawable.facebutton_b_depressed -> "facebutton_b_depressed.png"
            R.drawable.facebutton_x -> "facebutton_x.png"
            R.drawable.facebutton_x_depressed -> "facebutton_x_depressed.png"
            R.drawable.facebutton_y -> "facebutton_y.png"
            R.drawable.facebutton_y_depressed -> "facebutton_y_depressed.png"
            R.drawable.facebutton_plus -> "facebutton_plus.png"
            R.drawable.facebutton_plus_depressed -> "facebutton_plus_depressed.png"
            R.drawable.facebutton_minus -> "facebutton_minus.png"
            R.drawable.facebutton_minus_depressed -> "facebutton_minus_depressed.png"
            R.drawable.facebutton_home -> "facebutton_home.png"
            R.drawable.facebutton_home_depressed -> "facebutton_home_depressed.png"
            R.drawable.facebutton_screenshot -> "facebutton_screenshot.png"
            R.drawable.facebutton_screenshot_depressed -> "facebutton_screenshot_depressed.png"
            R.drawable.l_shoulder -> "l_shoulder.png"
            R.drawable.l_shoulder_depressed -> "l_shoulder_depressed.png"
            R.drawable.r_shoulder -> "r_shoulder.png"
            R.drawable.r_shoulder_depressed -> "r_shoulder_depressed.png"
            R.drawable.zl_trigger -> "zl_trigger.png"
            R.drawable.zl_trigger_depressed -> "zl_trigger_depressed.png"
            R.drawable.zr_trigger -> "zr_trigger.png"
            R.drawable.zr_trigger_depressed -> "zr_trigger_depressed.png"
            R.drawable.button_l3 -> "button_l3.png"
            R.drawable.button_l3_depressed -> "button_l3_depressed.png"
            R.drawable.button_r3 -> "button_r3.png"
            R.drawable.button_r3_depressed -> "button_r3_depressed.png"
            R.drawable.joystick -> "joystick.png"
            R.drawable.joystick_depressed -> "joystick_depressed.png"
            R.drawable.joystick_range -> "joystick_range.png"
            R.drawable.dpad_standard -> "dpad_standard.png"
            R.drawable.dpad_standard_cardinal_depressed -> "dpad_standard_cardinal_depressed.png"
            R.drawable.dpad_standard_diagonal_depressed -> "dpad_standard_diagonal_depressed.png"
            else -> null
        }

        /**
         * Resizes a [Bitmap] by a given scale factor
         *
         * @param context       Context for getting the vector drawable
         * @param drawableId    The ID of the drawable to scale.
         * @param scale         The scale factor for the bitmap.
         * @return The scaled [Bitmap]
         */
        private fun getBitmap(context: Context, drawableId: Int, scale: Float): Bitmap {
            // If the user installed a custom theme, prefer the bundled PNG
            // from the theme directory. It carries the user's artwork but
            // the rest of the pipeline (sizing, scaling) is unchanged.
            val themeBitmap = drawableToAssetName(drawableId)?.let { name ->
                OverlayThemeManager.bitmapFor(context, name)
            }
            if (themeBitmap != null) {
                return scaleBitmap(context, themeBitmap, scale)
            }

            // Fall back to the bundled drawable resource.
            val drawable = ContextCompat.getDrawable(context, drawableId)
                ?: error("Overlay drawable not found for id $drawableId")
            // Keep BitmapDrawable's default gravity (CENTER) so the artwork is
            // scaled proportionally into the bounds — critical for non-square
            // resources like the shoulder / trigger pills and the L3/R3 icons.
            val intrinsicW = if (drawable.intrinsicWidth > 0) drawable.intrinsicWidth else 1
            val intrinsicH = if (drawable.intrinsicHeight > 0) drawable.intrinsicHeight else 1

            val bitmap = Bitmap.createBitmap(
                (intrinsicW * scale).toInt().coerceAtLeast(1),
                (intrinsicH * scale).toInt().coerceAtLeast(1),
                Bitmap.Config.ARGB_8888
            )

            val dm = context.resources.displayMetrics
            val minScreenDimension = min(dm.widthPixels, dm.heightPixels)

            val maxBitmapDimension = max(bitmap.width, bitmap.height)
            val bitmapScale = if (maxBitmapDimension == 0) 1f
                else scale * minScreenDimension / maxBitmapDimension

            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * bitmapScale).toInt().coerceAtLeast(1),
                (bitmap.height * bitmapScale).toInt().coerceAtLeast(1),
                true
            )

            val canvas = Canvas(scaledBitmap)
            drawable.setBounds(0, 0, canvas.width, canvas.height)
            drawable.draw(canvas)
            return scaledBitmap
        }

        /**
         * Apply the same screen-relative scaling that the bundled pipeline
         * uses, but starting from a pre-decoded bitmap (theme artwork).
         * The original aspect ratio is preserved.
         */
        private fun scaleBitmap(context: Context, source: Bitmap, scale: Float): Bitmap {
            val dm = context.resources.displayMetrics
            val minScreenDimension = min(dm.widthPixels, dm.heightPixels)
            val maxBitmapDimension = max(source.width, source.height)
            val bitmapScale = if (maxBitmapDimension == 0) 1f
                else scale * minScreenDimension / maxBitmapDimension

            return Bitmap.createScaledBitmap(
                source,
                (source.width * bitmapScale).toInt().coerceAtLeast(1),
                (source.height * bitmapScale).toInt().coerceAtLeast(1),
                true
            )
        }

        /**
         * Gets the safe screen size for drawing the overlay
         *
         * @param context   Context for getting the window metrics
         * @return A pair of points, the first being the top left corner of the safe area,
         *                  the second being the bottom right corner of the safe area
         */
        private fun getSafeScreenSize(
            context: Context,
            screenSize: Pair<Int, Int>
        ): Pair<Point, Point> {
            // Get screen size
            val windowMetrics = WindowMetricsCalculator.getOrCreate()
                .computeCurrentWindowMetrics(context as Activity)
            var maxX = screenSize.first.toFloat()
            var maxY = screenSize.second.toFloat()
            var minX = 0
            var minY = 0

            // If we have API access, calculate the safe area to draw the overlay
            var cutoutLeft = 0
            var cutoutBottom = 0
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val insets = context.windowManager.currentWindowMetrics.windowInsets.displayCutout
                if (insets != null) {
                    if (insets.boundingRectTop.bottom != 0 &&
                        insets.boundingRectTop.bottom > maxY / 2
                    ) {
                        maxY = insets.boundingRectTop.bottom.toFloat()
                    }
                    if (insets.boundingRectRight.left != 0 &&
                        insets.boundingRectRight.left > maxX / 2
                    ) {
                        maxX = insets.boundingRectRight.left.toFloat()
                    }

                    minX = insets.boundingRectLeft.right - insets.boundingRectLeft.left
                    minY = insets.boundingRectBottom.top - insets.boundingRectBottom.bottom

                    cutoutLeft = insets.boundingRectRight.right - insets.boundingRectRight.left
                    cutoutBottom = insets.boundingRectTop.top - insets.boundingRectTop.bottom
                }
            }

            // This makes sure that if we have an inset on one side of the screen, we mirror it on
            // the other side. Since removing space from one of the max values messes with the scale,
            // we also have to account for it using our min values.
            if (maxX.toInt() != windowMetrics.bounds.width()) minX += cutoutLeft
            if (maxY.toInt() != windowMetrics.bounds.height()) minY += cutoutBottom
            if (minX > 0 && maxX.toInt() == windowMetrics.bounds.width()) {
                maxX -= (minX * 2)
            } else if (minX > 0) {
                maxX -= minX
            }
            if (minY > 0 && maxY.toInt() == windowMetrics.bounds.height()) {
                maxY -= (minY * 2)
            } else if (minY > 0) {
                maxY -= minY
            }

            return Pair(Point(minX, minY), Point(maxX.toInt(), maxY.toInt()))
        }

        /**
         * Initializes an InputOverlayDrawableButton, given by resId, with all of the
         * parameters set for it to be properly shown on the InputOverlay.
         *
         *
         * This works due to the way the X and Y coordinates are stored within
         * the [SharedPreferences].
         *
         *
         * In the input overlay configuration menu,
         * once a touch event begins and then ends (ie. Organizing the buttons to one's own liking for the overlay).
         * the X and Y coordinates of the button at the END of its touch event
         * (when you remove your finger/stylus from the touchscreen) are then stored in a native .
         *
         * Technically no modifications should need to be performed on the returned
         * InputOverlayDrawableButton. Simply add it to the HashSet of overlay items and wait
         * for Android to call the onDraw method.
         *
         * @param context            The current [Context].
         * @param windowSize         The size of the window to draw the overlay on.
         * @param defaultResId       The resource ID of the [Drawable] to get the [Bitmap] of (Default State).
         * @param pressedResId       The resource ID of the [Drawable] to get the [Bitmap] of (Pressed State).
         * @param buttonId           Identifier for determining what type of button the initialized InputOverlayDrawableButton represents.
         * @param overlayControlData Identifier for determining where a button appears on screen.
         * @param position           The position on screen as represented by an x and y value between 0 and 1.
         * @return An [InputOverlayDrawableButton] with the correct drawing bounds set.
         */
        private fun initializeOverlayButton(
            context: Context,
            windowSize: Pair<Point, Point>,
            defaultResId: Int,
            pressedResId: Int,
            button: NativeButton,
            overlayControlData: OverlayControlData,
            position: Pair<Double, Double>
        ): InputOverlayDrawableButton {
            // Resources handle for fetching the initial Drawable resource.
            val res = context.resources

            // Decide scale based on button preference ID and user preference
            var scale: Float = when (overlayControlData.id) {
                OverlayControl.BUTTON_HOME.id,
                OverlayControl.BUTTON_CAPTURE.id,
                OverlayControl.BUTTON_PLUS.id,
                OverlayControl.BUTTON_MINUS.id -> 0.07f

                OverlayControl.BUTTON_L.id,
                OverlayControl.BUTTON_R.id,
                OverlayControl.BUTTON_ZL.id,
                OverlayControl.BUTTON_ZR.id -> 0.26f

                OverlayControl.BUTTON_STICK_L.id,
                OverlayControl.BUTTON_STICK_R.id -> 0.155f

                else -> 0.11f
            }
            scale *= (IntSetting.OVERLAY_SCALE.getInt() + 50).toFloat()
            scale /= 100f

            // Apply individual scale
            scale *= overlayControlData.individualScale.let { if (it > 0f) it else 1f }

            // Initialize the InputOverlayDrawableButton.
            val defaultStateBitmap = getBitmap(context, defaultResId, scale)
            val pressedStateBitmap = getBitmap(context, pressedResId, scale)
            val overlayDrawable = InputOverlayDrawableButton(
                res,
                defaultStateBitmap,
                pressedStateBitmap,
                button,
                overlayControlData
            )

            // Get the minimum and maximum coordinates of the screen where the button can be placed.
            val min = windowSize.first
            val max = windowSize.second

            // The X and Y coordinates of the InputOverlayDrawableButton on the InputOverlay.
            // These were set in the input overlay configuration menu.
            val drawableX = (position.first * max.x + min.x).toInt()
            val drawableY = (position.second * max.y + min.y).toInt()
            val width = overlayDrawable.width
            val height = overlayDrawable.height

            // Now set the bounds for the InputOverlayDrawableButton.
            // This will dictate where on the screen (and the what the size) the InputOverlayDrawableButton will be.
            overlayDrawable.setBounds(
                drawableX - (width / 2),
                drawableY - (height / 2),
                drawableX + (width / 2),
                drawableY + (height / 2)
            )

            // Need to set the image's position
            overlayDrawable.setPosition(
                drawableX - (width / 2),
                drawableY - (height / 2)
            )
            overlayDrawable.setOpacity(IntSetting.OVERLAY_OPACITY.getInt() * 255 / 100)
            return overlayDrawable
        }

        /**
         * Initializes an [InputOverlayDrawableDpad]
         *
         * @param context                   The current [Context].
         * @param windowSize                The size of the window to draw the overlay on.
         * @param defaultResId              The [Bitmap] resource ID of the default state.
         * @param pressedOneDirectionResId  The [Bitmap] resource ID of the pressed state in one direction.
         * @param pressedTwoDirectionsResId The [Bitmap] resource ID of the pressed state in two directions.
         * @param position                  The position on screen as represented by an x and y value between 0 and 1.
         * @return The initialized [InputOverlayDrawableDpad]
         */
        private fun initializeOverlayDpad(
            context: Context,
            windowSize: Pair<Point, Point>,
            defaultResId: Int,
            pressedOneDirectionResId: Int,
            pressedTwoDirectionsResId: Int,
            position: Pair<Double, Double>
        ): InputOverlayDrawableDpad {
            // Resources handle for fetching the initial Drawable resource.
            val res = context.resources

            // Get the dpad control data for individual scale
            val overlayControlData = NativeConfig.getOverlayControlData()
            val dpadData = overlayControlData.firstOrNull { it.id == OverlayControl.COMBINED_DPAD.id }

            // Decide scale based on button ID and user preference
            var scale = 0.25f
            scale *= (IntSetting.OVERLAY_SCALE.getInt() + 50).toFloat()
            scale /= 100f

            // Apply individual scale
            if (dpadData != null) {
                scale *= dpadData.individualScale.let { if (it > 0f) it else 1f }
            }

            // Initialize the InputOverlayDrawableDpad.
            val defaultStateBitmap =
                getBitmap(context, defaultResId, scale)
            val pressedOneDirectionStateBitmap = getBitmap(context, pressedOneDirectionResId, scale)
            val pressedTwoDirectionsStateBitmap =
                getBitmap(context, pressedTwoDirectionsResId, scale)

            val overlayDrawable = InputOverlayDrawableDpad(
                res,
                defaultStateBitmap,
                pressedOneDirectionStateBitmap,
                pressedTwoDirectionsStateBitmap
            )

            // Get the minimum and maximum coordinates of the screen where the button can be placed.
            val min = windowSize.first
            val max = windowSize.second

            // The X and Y coordinates of the InputOverlayDrawableDpad on the InputOverlay.
            // These were set in the input overlay configuration menu.
            val drawableX = (position.first * max.x + min.x).toInt()
            val drawableY = (position.second * max.y + min.y).toInt()
            val width = overlayDrawable.width
            val height = overlayDrawable.height

            // Now set the bounds for the InputOverlayDrawableDpad.
            // This will dictate where on the screen (and the what the size) the InputOverlayDrawableDpad will be.
            overlayDrawable.setBounds(
                drawableX - (width / 2),
                drawableY - (height / 2),
                drawableX + (width / 2),
                drawableY + (height / 2)
            )

            // Need to set the image's position
            overlayDrawable.setPosition(drawableX - (width / 2), drawableY - (height / 2))
            overlayDrawable.setOpacity(IntSetting.OVERLAY_OPACITY.getInt() * 255 / 100)
            return overlayDrawable
        }

        /**
         * Initializes an [InputOverlayDrawableJoystick]
         *
         * @param context         The current [Context]
         * @param windowSize      The size of the window to draw the overlay on.
         * @param resOuter        Resource ID for the outer image of the joystick (the static image that shows the circular bounds).
         * @param defaultResInner Resource ID for the default inner image of the joystick (the one you actually move around).
         * @param pressedResInner Resource ID for the pressed inner image of the joystick.
         * @param joystick        Identifier for which joystick this is.
         * @param buttonId          Identifier for which joystick button this is.
         * @param overlayControlData Identifier for determining where a button appears on screen.
         * @param position           The position on screen as represented by an x and y value between 0 and 1.
         * @return The initialized [InputOverlayDrawableJoystick].
         */
        private fun initializeOverlayJoystick(
            context: Context,
            windowSize: Pair<Point, Point>,
            resOuter: Int,
            defaultResInner: Int,
            pressedResInner: Int,
            joystick: NativeAnalog,
            button: NativeButton,
            overlayControlData: OverlayControlData,
            position: Pair<Double, Double>
        ): InputOverlayDrawableJoystick {
            // Resources handle for fetching the initial Drawable resource.
            val res = context.resources

            // Decide scale based on user preference
            var scale = 0.3f
            scale *= (IntSetting.OVERLAY_SCALE.getInt() + 50).toFloat()
            scale /= 100f

            // Apply individual scale
            scale *= overlayControlData.individualScale.let { if (it > 0f) it else 1f }

            // Initialize the InputOverlayDrawableJoystick.
            val bitmapOuter = getBitmap(context, resOuter, scale)
            val bitmapInnerDefault = getBitmap(context, defaultResInner, 1.0f)
            val bitmapInnerPressed = getBitmap(context, pressedResInner, 1.0f)

            // Get the minimum and maximum coordinates of the screen where the button can be placed.
            val min = windowSize.first
            val max = windowSize.second

            // The X and Y coordinates of the InputOverlayDrawableButton on the InputOverlay.
            // These were set in the input overlay configuration menu.
            val drawableX = (position.first * max.x + min.x).toInt()
            val drawableY = (position.second * max.y + min.y).toInt()
            val outerScale = 1.66f

            // Now set the bounds for the InputOverlayDrawableJoystick.
            // This will dictate where on the screen (and the what the size) the InputOverlayDrawableJoystick will be.
            val outerSize = bitmapOuter.width
            val outerRect = Rect(
                drawableX - (outerSize / 2),
                drawableY - (outerSize / 2),
                drawableX + (outerSize / 2),
                drawableY + (outerSize / 2)
            )
            val innerRect =
                Rect(0, 0, (outerSize / outerScale).toInt(), (outerSize / outerScale).toInt())

            // Send the drawableId to the joystick so it can be referenced when saving control position.
            val overlayDrawable = InputOverlayDrawableJoystick(
                res,
                bitmapOuter,
                bitmapInnerDefault,
                bitmapInnerPressed,
                outerRect,
                innerRect,
                joystick,
                button,
                overlayControlData.id
            )

            // Need to set the image's position
            overlayDrawable.setPosition(drawableX, drawableY)
            overlayDrawable.setOpacity(IntSetting.OVERLAY_OPACITY.getInt() * 255 / 100)
            return overlayDrawable
        }
    }
}
