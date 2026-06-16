// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.model

import org.yuzu.yuzu_emu.features.input.model.NativeButton

/**
 * A user-defined (or built-in) virtual button combo.
 *
 * Pressing the combo pad on the overlay sends [buttons] to the game as if
 * the user had physically held each one down at the same time. This lets
 * a single tap emit a "macro" / "chord" like "Down + Forward + A" used
 * for special moves in fighting games.
 *
 * [displayName] is the user-facing label rendered both in the manager
 * list and directly on the combo pad above the game surface.
 *
 * Stored position / scale follow the same screen-relative convention as
 * the regular overlay controls so the user can drag the combo pad
 * around just like a normal button.
 */
data class ComboPreset(
    val id: String,
    val displayName: String,
    val buttons: List<NativeButton>,
    var enabled: Boolean = true,
    var landscapePosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var portraitPosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var foldablePosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var individualScale: Float = 1.0f,
) {
    init {
        require(buttons.size in MIN_TRIGGERS..MAX_TRIGGERS) {
            "ComboPreset requires $MIN_TRIGGERS-$MAX_TRIGGERS buttons (got ${buttons.size})"
        }
        require(buttons.distinct().size == buttons.size) {
            "ComboPreset buttons must be distinct"
        }
    }

    fun positionFromLayout(layout: OverlayLayout): Pair<Double, Double> = when (layout) {
        OverlayLayout.Landscape -> landscapePosition
        OverlayLayout.Portrait -> portraitPosition
        OverlayLayout.Foldable -> foldablePosition
    }

    companion object {
        /** Minimum number of buttons per combo. */
        const val MIN_TRIGGERS = 2
        /** Maximum number of buttons per combo. */
        const val MAX_TRIGGERS = 8

        /**
         * Built-in presets surfaced by "Load preset" in the combo editor.
         */
        val BUILT_IN_PRESETS: List<ComboPreset> = listOf(
            ComboPreset(
                id = "builtin_ab",
                displayName = "AB",
                buttons = listOf(NativeButton.A, NativeButton.B),
            ),
            ComboPreset(
                id = "builtin_lr",
                displayName = "LR",
                buttons = listOf(NativeButton.L, NativeButton.R),
            ),
            ComboPreset(
                id = "builtin_zlzr",
                displayName = "ZLZR",
                buttons = listOf(NativeButton.ZL, NativeButton.ZR),
            ),
            ComboPreset(
                id = "builtin_dfa",
                displayName = "下前A",
                buttons = listOf(NativeButton.DDown, NativeButton.DRight, NativeButton.A),
            ),
        )
    }
}
