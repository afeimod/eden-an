// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Manages custom overlay themes packaged as `.zip` files.
 *
 * The user picks a zip through the system file picker (SAF). The manager
 * copies it into the app's private storage, unzips every `.png` entry into
 * `filesDir/themes/active/` and the rest of the app reads overlay assets
 * from that directory first, falling back to the bundled resources when a
 * particular image is missing.
 *
 * A theme zip is a flat archive containing these optional files:
 *   - background.png                      (drawn behind the game surface)
 *   - facebutton_a.png / _depressed.png
 *   - facebutton_b.png / _depressed.png
 *   - facebutton_x.png / _depressed.png
 *   - facebutton_y.png / _depressed.png
 *   - facebutton_plus.png / _depressed.png
 *   - facebutton_minus.png / _depressed.png
 *   - facebutton_home.png / _depressed.png
 *   - facebutton_screenshot.png / _depressed.png
 *   - l_shoulder.png / _depressed.png
 *   - r_shoulder.png / _depressed.png
 *   - zl_trigger.png / _depressed.png
 *   - zr_trigger.png / _depressed.png
 *   - button_l3.png / _depressed.png
 *   - button_r3.png / _depressed.png
 *   - joystick.png / _depressed.png
 *   - joystick_range.png
 *   - dpad_standard.png
 *   - dpad_standard_cardinal_depressed.png
 *   - dpad_standard_diagonal_depressed.png
 *
 * Unknown entries are ignored. Filenames that don't end in `.png` are
 * ignored. The `background.png` is special-cased and used by the
 * `EmulationFragment` as a static layer behind the rendering surface.
 */
object OverlayThemeManager {
    private const val TAG = "OverlayThemeManager"
    private const val ACTIVE_DIR = "themes/active"
    private const val BACKGROUND_FILE = "background.png"

    // The theme path lives in SharedPreferences instead of NativeConfig's
    // string setting table because:
    //   1. NativeConfig.setString needs an explicit saveGlobalConfig() call
    //      to persist across process restarts, and the picker must read the
    //      value back right after install() returns — too easy to miss.
    //   2. NativeConfig.getString(key, needsGlobal) is unreliable for
    //      custom keys we haven't registered with the native side; the JNI
    //      side only knows about settings it has hard-coded defaults for,
    //      so reading back an unrecognised key returns "" even after a
    //      successful write. SharedPreferences has neither pitfall.
    private const val PREFS_NAME = "overlay_theme_prefs"
    private const val KEY_THEME_URI = "overlay_theme_uri"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All filenames we recognise as overlay assets (case-insensitive). */
    val KNOWN_FILES: Set<String> = setOf(
        BACKGROUND_FILE,
        "facebutton_a.png", "facebutton_a_depressed.png",
        "facebutton_b.png", "facebutton_b_depressed.png",
        "facebutton_x.png", "facebutton_x_depressed.png",
        "facebutton_y.png", "facebutton_y_depressed.png",
        "facebutton_plus.png", "facebutton_plus_depressed.png",
        "facebutton_minus.png", "facebutton_minus_depressed.png",
        "facebutton_home.png", "facebutton_home_depressed.png",
        "facebutton_screenshot.png", "facebutton_screenshot_depressed.png",
        "l_shoulder.png", "l_shoulder_depressed.png",
        "r_shoulder.png", "r_shoulder_depressed.png",
        "zl_trigger.png", "zl_trigger_depressed.png",
        "zr_trigger.png", "zr_trigger_depressed.png",
        "button_l3.png", "button_l3_depressed.png",
        "button_r3.png", "button_r3_depressed.png",
        "joystick.png", "joystick_depressed.png",
        "joystick_range.png",
        "dpad_standard.png",
        "dpad_standard_cardinal_depressed.png",
        "dpad_standard_diagonal_depressed.png"
    )

    /** Where the active theme's PNGs are stored. */
    fun activeDir(context: Context): File =
        File(context.filesDir, ACTIVE_DIR).apply { if (!exists()) mkdirs() }

    /** Where the source zip is cached, so we don't have to re-read it. */
    private fun sourceZip(context: Context): File =
        File(context.filesDir, "themes/last_theme.zip")

    /** Returns true if a custom theme is currently active. */
    fun isActive(context: Context): Boolean =
        savedUri(context).isNotEmpty()

    private fun savedUri(context: Context): String =
        prefs(context).getString(KEY_THEME_URI, "") ?: ""

    /**
     * Install the theme zip pointed to by [uri] as the active theme.
     *
     * Returns the number of PNG files extracted, or -1 on failure.
     */
    fun install(context: Context, uri: Uri): Int {
        val resolver = context.contentResolver
        val targetZip = sourceZip(context)

        try {
            // 1. Copy the zip into our own storage. We need a real file
            //    because ZipInputStream is single-pass; we also want the
            //    theme to survive SAF permission revocations.
            targetZip.parentFile?.mkdirs()
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "Could not open input stream for $uri" }
                FileOutputStream(targetZip).use { output ->
                    input.copyTo(output)
                }
            }

            // 2. Wipe the previous active dir, then re-extract.
            val active = activeDir(context)
            active.listFiles()?.forEach { it.delete() }

            var extracted = 0
            FileInputStream(targetZip).use { fis ->
                ZipInputStream(fis).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        val name = entry.name.substringAfterLast('/').lowercase()
                        if (!entry.isDirectory && name.endsWith(".png") && name in KNOWN_FILES) {
                            val out = File(active, name)
                            FileOutputStream(out).use { zis.copyTo(it) }
                            extracted++
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }
            }

            // 3. Persist the source uri so the next launch can re-apply.
            prefs(context).edit()
                .putString(KEY_THEME_URI, uri.toString())
                .apply()
            Log.i(TAG, "Installed overlay theme from $uri ($extracted files)")
            return extracted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install overlay theme", e)
            // Best-effort cleanup so a partial install doesn't show up.
            runCatching { targetZip.delete() }
            return -1
        }
    }

    /**
     * Re-apply the persisted theme (if any). Called from EmulationFragment
     * on view creation so a theme survives app restarts.
     */
    fun reapply(context: Context) {
        val saved = savedUri(context)
        if (saved.isEmpty()) return
        runCatching { install(context, Uri.parse(saved)) }
    }

    /** Remove the active theme and delete the cached files. */
    fun uninstall(context: Context) {
        prefs(context).edit().remove(KEY_THEME_URI).apply()
        activeDir(context).listFiles()?.forEach { it.delete() }
        runCatching { sourceZip(context).delete() }
    }

    /**
     * Return a [Bitmap] for the given overlay asset, or null if the active
     * theme does not override it. Callers should fall back to the bundled
     * drawable when this returns null.
     */
    fun bitmapFor(context: Context, assetName: String): Bitmap? {
        val file = File(activeDir(context), assetName.lowercase())
        if (!file.exists() || file.length() == 0L) return null
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return BitmapFactory.decodeFile(file.absolutePath, opts)
    }

    /** True if the active theme provides a [BACKGROUND_FILE]. */
    fun hasBackground(context: Context): Boolean =
        File(activeDir(context), BACKGROUND_FILE).exists()

    /**
     * Copy the theme's background.png to a publicly accessible cache path
     * suitable for `BitmapFactory.decodeFile`. Returns null when no
     * background is bundled with the active theme.
     */
    fun backgroundFile(context: Context): File? {
        val f = File(activeDir(context), BACKGROUND_FILE)
        return if (f.exists()) f else null
    }
}

