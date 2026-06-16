// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.model

import org.yuzu.yuzu_emu.features.input.model.NativeButton

/**
 * A user-defined (or built-in) virtual button combo.
 *
 * Pressing the combo pad on the overlay sends [buttons] to the game as if
 * the user had physically held each one down at the same time. The exact
 * emission style depends on [kind]:
 *
 * - [Kind.CHORD] (default): all buttons are sent PRESSED in the same
 *   frame, all RELEASED in the same frame when the user lifts off.
 *   Suitable for face / shoulder buttons (e.g. A+B).
 *
 * - [Kind.MACRO]: buttons are sent PRESSED one by one in array order
 *   with a small delay between them, then all RELEASED together after a
 *   short hold. Used to emulate special-move style input (e.g.
 *   "Down + Forward + A" → ↓ → → + A).
 *
 * [displayName] is the user-facing label rendered both in the manager
 * list and directly on the combo pad above the game surface.
 */
data class ComboPreset(
    val id: String,
    val displayName: String,
    val buttons: List<NativeButton>,
    val kind: Kind = Kind.CHORD,
    var enabled: Boolean = true,
    var landscapePosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var portraitPosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var foldablePosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var individualScale: Float = 1.0f,
) {

    /** How [buttons] are emitted when the combo is pressed. */
    enum class Kind { CHORD, MACRO }

    /** True if this combo contains any direction / stick button. */
    val hasDirectional: Boolean
        get() = buttons.any { isDirectional(it) }

    fun positionFromLayout(layout: OverlayLayout): Pair<Double, Double> = when (layout) {
        OverlayLayout.Landscape -> landscapePosition
        OverlayLayout.Portrait -> portraitPosition
        OverlayLayout.Foldable -> foldablePosition
    }

    init {
        require(buttons.size in MIN_TRIGGERS..MAX_TRIGGERS) {
            "ComboPreset requires $MIN_TRIGGERS-$MAX_TRIGGERS buttons (got ${buttons.size})"
        }
        require(buttons.distinct().size == buttons.size) {
            "ComboPreset buttons must be distinct"
        }
    }

    companion object {
        /** Minimum number of buttons per combo. */
        const val MIN_TRIGGERS = 2

        /** Maximum number of buttons per combo. */
        const val MAX_TRIGGERS = 8

        fun isDirectional(b: NativeButton): Boolean = when (b) {
            NativeButton.DUp, NativeButton.DDown,
            NativeButton.DLeft, NativeButton.DRight,
            NativeButton.LStick, NativeButton.RStick -> true
            else -> false
        }

        /**
         * Built-in presets surfaced by "Load preset" in the combo editor.
         */
        val BUILT_IN_PRESETS: List<ComboPreset> = listOf(
            ComboPreset(
                id = "builtin_ab",
                displayName = "AB",
                buttons = listOf(NativeButton.A, NativeButton.B),
                kind = Kind.CHORD,
            ),
            ComboPreset(
                id = "builtin_lr",
                displayName = "LR",
                buttons = listOf(NativeButton.L, NativeButton.R),
                kind = Kind.CHORD,
            ),
            ComboPreset(
                id = "builtin_zlzr",
                displayName = "ZLZR",
                buttons = listOf(NativeButton.ZL, NativeButton.ZR),
                kind = Kind.CHORD,
            ),
            ComboPreset(
                id = "builtin_dfa",
                displayName = "下前A",
                buttons = listOf(NativeButton.DDown, NativeButton.DRight, NativeButton.A),
                kind = Kind.MACRO,
            ),
        )
    }
}
