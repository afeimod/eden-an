// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.model

/**
 * A user-named snapshot of the input overlay layout.
 *
 * The snapshot captures everything the user can change in the in-game
 * "Edit overlay" mode:
 *
 *  - The position / scale / visibility of every regular control
 *    (face buttons, shoulders, sticks, dpad, …), stored as an
 *    [OverlayControlData] array.
 *  - The full [ComboPreset] list (so each profile carries its own macro
 *    / chord set, not just the global built-in ones).
 *
 * [gameId] is the programId of the game the profile was captured under,
 * or [GLOBAL_GAME_ID] for a profile the user wants to apply across all
 * games. Loading a profile always targets the currently-running game;
 * the [gameId] is metadata only so the picker can group entries per
 * game.
 *
 * Profiles are written to [OverlayLayoutProfileStore.directory] as one
 * JSON file per profile. The on-disk format is intentionally plain
 * (no Gson / Moshi) so we don't pull a serialisation dependency for
 * one tiny payload.
 */
data class OverlayLayoutProfile(
    val name: String,
    val gameId: String,
    val createdAt: Long,
    val controls: List<OverlayControlData>,
    val combos: List<ComboPreset>,
) {
    companion object {
        /** Sentinel gameId for profiles the user marked "global". */
        const val GLOBAL_GAME_ID = "__global__"
    }
}
