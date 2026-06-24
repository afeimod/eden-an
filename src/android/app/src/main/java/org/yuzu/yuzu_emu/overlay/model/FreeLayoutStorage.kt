// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.model

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the free-layout rectangle in a dedicated Android
 * [SharedPreferences] file, independent of the native config layers.
 *
 * The native [NativeConfig.setBoolean] / [NativeConfig.setFloat] APIs
 * write to whichever config is "current" in memory, which on Eden can
 * be per-game, and the data is then dropped when [NativeConfig.reloadGlobalConfig]
 * is called on game exit. We side-step that entirely by storing the
 * free-layout rect on the Android side so it always survives game
 * switching and app restarts.
 */
object FreeLayoutStorage {
    private const val PREFS = "free_layout_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_X1 = "x1"
    private const val KEY_Y1 = "y1"
    private const val KEY_X2 = "x2"
    private const val KEY_Y2 = "y2"

    /** Default rectangle: fullscreen. */
    val DEFAULT = floatArrayOf(0f, 0f, 1f, 1f)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Read the saved rect, or [DEFAULT] if not saved / invalid. */
    fun load(context: Context): FloatArray {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ENABLED, false)) return DEFAULT.copyOf()
        val x1 = p.getFloat(KEY_X1, -1f)
        val y1 = p.getFloat(KEY_Y1, -1f)
        val x2 = p.getFloat(KEY_X2, -1f)
        val y2 = p.getFloat(KEY_Y2, -1f)
        return if (x1 < 0f || y1 < 0f || x2 < 0f || y2 < 0f || x2 <= x1 || y2 <= y1) {
            DEFAULT.copyOf()
        } else floatArrayOf(x1, y1, x2, y2)
    }

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, false)

    fun save(context: Context, x1: Float, y1: Float, x2: Float, y2: Float) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, true)
            .putFloat(KEY_X1, x1)
            .putFloat(KEY_Y1, y1)
            .putFloat(KEY_X2, x2)
            .putFloat(KEY_Y2, y2)
            .apply()
    }

    fun reset(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_ENABLED, false)
            .putFloat(KEY_X1, 0f)
            .putFloat(KEY_Y1, 0f)
            .putFloat(KEY_X2, 1f)
            .putFloat(KEY_Y2, 1f)
            .apply()
    }
}
