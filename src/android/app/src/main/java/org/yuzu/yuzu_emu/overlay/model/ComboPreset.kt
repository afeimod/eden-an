// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.model

import org.yuzu.yuzu_emu.features.input.model.NativeButton

/**
 * A user-defined (or built-in) virtual button combo.
 *
 * Pressing [triggers] (2 or 3 child keys, held simultaneously) inside the
 * combo pad's bounding rect will synthesize [target] as if the user had
 * pressed the corresponding host button.
 *
 * Stored position / scale follow the same screen-relative convention as
 * the regular overlay controls so that the user can drag the combo pad
 * around just like a normal button.
 */
data class ComboPreset(
    val id: String,
    val displayName: String,
    val triggers: List<NativeButton>,
    val target: NativeButton,
    var enabled: Boolean = true,
    var landscapePosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var portraitPosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var foldablePosition: Pair<Double, Double> = Pair(0.85, 0.7),
    var individualScale: Float = 1.0f,
) {
    init {
        require(triggers.size in MIN_TRIGGERS..MAX_TRIGGERS) {
            "ComboPreset requires $MIN_TRIGGERS-$MAX_TRIGGERS child triggers (got ${triggers.size})"
        }
        require(triggers.distinct().size == triggers.size) {
            "ComboPreset triggers must be distinct"
        }
    }

    fun positionFromLayout(layout: OverlayLayout): Pair<Double, Double> = when (layout) {
        OverlayLayout.Landscape -> landscapePosition
        OverlayLayout.Portrait -> portraitPosition
        OverlayLayout.Foldable -> foldablePosition
    }

    companion object {
        /** Minimum number of child trigger keys per combo. */
        const val MIN_TRIGGERS = 2
        /** Maximum number of child trigger keys per combo. */
        const val MAX_TRIGGERS = 8

        /**
         * Built-in presets surfaced by "Load preset" in the combo editor.
         *
         * Each one is a (displayName, triggers, target) triple. The id is
         * stable so that user-saved customizations won't collide with
         * future renames.
         */
        val BUILT_IN_PRESETS: List<ComboPreset> = listOf(
            ComboPreset(
                id = "builtin_lr_to_zl",
                displayName = "L + R → ZL",
                triggers = listOf(NativeButton.L, NativeButton.R),
                target = NativeButton.ZL,
            ),
            ComboPreset(
                id = "builtin_zlzr_to_home",
                displayName = "ZL + ZR → Home",
                triggers = listOf(NativeButton.ZL, NativeButton.ZR),
                target = NativeButton.Home,
            ),
            ComboPreset(
                id = "builtin_ab_to_capture",
                displayName = "A + B → Capture",
                triggers = listOf(NativeButton.A, NativeButton.B),
                target = NativeButton.Capture,
            ),
            ComboPreset(
                id = "builtin_minusplus_to_plus",
                displayName = "Minus + Plus → Plus (long)",
                triggers = listOf(NativeButton.Minus, NativeButton.Plus),
                target = NativeButton.Plus,
            ),
        )
    }
}
