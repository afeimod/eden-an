// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.model

import android.content.Context
import java.io.File
import java.util.Locale
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.utils.Log

/**
 * Persists user-named [OverlayLayoutProfile]s to disk.
 *
 * Layout: one JSON file per profile inside
 *   <filesDir>/overlay_profiles/<sanitised-name>--<gameId-or-global>.json
 *
 * The on-disk format is hand-rolled (same approach as [ComboStore]) so we
 * don't take a Gson/Moshi dependency for one tiny payload.
 */
object OverlayLayoutProfileStore {

    private const val TAG = "OverlayLayoutProfileStore"
    private const val DIR_NAME = "overlay_profiles"

    /** Where profile files live. Created lazily on first access. */
    fun directory(context: Context): File =
        File(context.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    // ---------- capture ----------

    /**
     * Build a [OverlayLayoutProfile] from the currently-loaded native
     * overlay control data and the combos in [ComboStore].
     *
     * The caller decides [gameId]: pass the running game's programId
     * for a per-game profile, or [OverlayLayoutProfile.GLOBAL_GAME_ID]
     * for a cross-game one.
     */
    fun capture(
        context: Context,
        name: String,
        gameId: String,
        controls: Array<OverlayControlData>,
    ): OverlayLayoutProfile {
        val combos = ComboStore.load(context)
        return OverlayLayoutProfile(
            name = name,
            gameId = gameId,
            createdAt = System.currentTimeMillis(),
            controls = controls.toList(),
            combos = combos.toList(),
        )
    }

    /**
     * Persist [profile] to disk. Returns true on success. Overwrites an
     * existing profile with the same name + gameId.
     */
    fun save(context: Context, profile: OverlayLayoutProfile): Boolean {
        return try {
            val file = fileFor(context, profile.name, profile.gameId, create = true)
            file.writeText(serialize(profile))
            true
        } catch (e: Exception) {
            Log.error("[$TAG] Failed to save profile ${profile.name}: ${e.message}")
            false
        }
    }

    fun delete(context: Context, name: String, gameId: String): Boolean {
        return try {
            val file = fileFor(context, name, gameId, create = false)
            if (file.exists()) file.delete() else false
        } catch (e: Exception) {
            Log.error("[$TAG] Failed to delete profile $name: ${e.message}")
            false
        }
    }

    /** Every profile on disk, newest first. Files that fail to parse are skipped. */
    fun listAll(context: Context): List<OverlayLayoutProfile> {
        val dir = directory(context)
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?: return emptyList()
        return files.mapNotNull { runCatching { deserialize(it.readText()) }.getOrNull() }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Profiles available for [currentGameId], including all global ones
     * (so the user can apply a global profile to the current game).
     */
    fun listForGame(context: Context, currentGameId: String): List<OverlayLayoutProfile> {
        return listAll(context).filter {
            it.gameId == currentGameId || it.gameId == OverlayLayoutProfile.GLOBAL_GAME_ID
        }
    }

    /** Load a profile by name + gameId, or null if not found. */
    fun load(context: Context, name: String, gameId: String): OverlayLayoutProfile? {
        val file = fileFor(context, name, gameId, create = false)
        if (!file.exists()) return null
        return runCatching { deserialize(file.readText()) }.getOrNull()
    }

    // ---------- apply ----------

    /**
     * Apply [profile] to the in-memory native overlay state and to the
     * combo store, then save so the change persists across launches.
     *
     * @return true if the apply + save succeeded end-to-end.
     */
    fun apply(context: Context, profile: OverlayLayoutProfile): Boolean {
        return try {
            val newControls = profile.controls.toTypedArray()
            org.yuzu.yuzu_emu.utils.NativeConfig.setOverlayControlData(newControls)
            // Persist to per-game if one is loaded, otherwise to global.
            // (We do NOT call saveGlobalConfig() blindly here — the
            // SaveAllValuesForcingOverlay path inside saveOverlayControlData
            // is what actually writes the control_data block to disk, and
            // it picks the right INI based on perGame.)
            org.yuzu.yuzu_emu.utils.NativeConfig.saveOverlayControlData(
                perGame = org.yuzu.yuzu_emu.utils.NativeConfig.isPerGameConfigLoaded()
            )
            // Combos live in SharedPreferences, not in the native INI.
            ComboStore.save(context, profile.combos.toMutableList())
            true
        } catch (e: Exception) {
            Log.error("[$TAG] Failed to apply profile ${profile.name}: ${e.message}")
            false
        }
    }

    // ---------- file path helpers ----------

    private fun fileFor(
        context: Context,
        name: String,
        gameId: String,
        create: Boolean,
    ): File {
        val safeName = sanitiseName(name)
        val gameTag = if (gameId == OverlayLayoutProfile.GLOBAL_GAME_ID) "global"
            else sanitiseName(gameId.ifEmpty { OverlayLayoutProfile.GLOBAL_GAME_ID })
        return File(directory(context), "${safeName}--$gameTag.json")
    }

    private fun sanitiseName(raw: String): String {
        val cleaned = raw.trim()
            .replace(Regex("[^A-Za-z0-9._\\-一-龥]"), "_")
            .take(64)
        return if (cleaned.isEmpty()) "unnamed" else cleaned
    }

    // ---------- (de)serialise ----------

    private fun serialize(p: OverlayLayoutProfile): String = buildString {
        append('{')
        append("\"name\":").append(jsonStr(p.name)).append(',')
        append("\"gameId\":").append(jsonStr(p.gameId)).append(',')
        append("\"createdAt\":").append(p.createdAt).append(',')
        append("\"controls\":[")
        p.controls.forEachIndexed { i, c ->
            if (i > 0) append(',')
            append('{')
            append("\"id\":").append(jsonStr(c.id)).append(',')
            append("\"enabled\":").append(if (c.enabled) "true" else "false").append(',')
            append("\"landX\":").append(c.landscapePosition.first).append(',')
            append("\"landY\":").append(c.landscapePosition.second).append(',')
            append("\"portX\":").append(c.portraitPosition.first).append(',')
            append("\"portY\":").append(c.portraitPosition.second).append(',')
            append("\"foldX\":").append(c.foldablePosition.first).append(',')
            append("\"foldY\":").append(c.foldablePosition.second).append(',')
            append("\"scale\":").append(formatDouble(c.individualScale))
            append('}')
        }
        append("],\"combos\":[")
        p.combos.forEachIndexed { i, preset ->
            if (i > 0) append(',')
            append('{')
            append("\"id\":").append(jsonStr(preset.id)).append(',')
            append("\"name\":").append(jsonStr(preset.displayName)).append(',')
            append("\"enabled\":").append(if (preset.enabled) "true" else "false").append(',')
            append("\"kind\":").append(jsonStr(preset.kind.name)).append(',')
            append("\"landX\":").append(preset.landscapePosition.first).append(',')
            append("\"landY\":").append(preset.landscapePosition.second).append(',')
            append("\"portX\":").append(preset.portraitPosition.first).append(',')
            append("\"portY\":").append(preset.portraitPosition.second).append(',')
            append("\"foldX\":").append(preset.foldablePosition.first).append(',')
            append("\"foldY\":").append(preset.foldablePosition.second).append(',')
            append("\"scale\":").append(formatDouble(preset.individualScale)).append(',')
            append("\"buttons\":[")
            preset.buttons.forEachIndexed { j, b ->
                if (j > 0) append(',')
                append(b.int)
            }
            append(']')
            append('}')
        }
        append("]}")
    }

    private fun formatDouble(v: Float): String =
        // Locale-independent, deterministic decimal so round-tripping a
        // Float through JSON doesn't shift digits depending on the
        // device's language settings.
        String.format(Locale.ROOT, "%.6f", v.toDouble())

    private fun jsonStr(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    /**
     * Minimal tolerant JSON parser. We control the producer, so we don't
     * need a full implementation — the grammar is just `{ "key": <value>
     * , ... }` where value can be a string, number, true / false, or a
     * nested array / object. Anything we don't understand we skip.
     */
    private fun deserialize(json: String): OverlayLayoutProfile {
        val root = parseObject(json)
        val name = root.string("name")
        val gameId = root.string("gameId", OverlayLayoutProfile.GLOBAL_GAME_ID)
        val createdAt = root.long("createdAt", System.currentTimeMillis())

        val controlsArr = root.array("controls")
        val controls = controlsArr.mapNotNull { obj ->
            try {
                OverlayControlData(
                    id = obj.string("id"),
                    enabled = obj.bool("enabled", true),
                    landscapePosition = Pair(obj.double("landX", 0.85), obj.double("landY", 0.7)),
                    portraitPosition = Pair(obj.double("portX", 0.85), obj.double("portY", 0.7)),
                    foldablePosition = Pair(obj.double("foldX", 0.85), obj.double("foldY", 0.7)),
                    individualScale = obj.float("scale", 1.0f),
                )
            } catch (e: Exception) {
                Log.warning("[$TAG] Skipping malformed control entry: ${e.message}")
                null
            }
        }

        val combosArr = root.array("combos")
        val combos = combosArr.mapNotNull { obj ->
            try {
                val rawBtns = obj.string("buttons", "[]")
                val parsed = if (rawBtns.startsWith("[") && rawBtns.endsWith("]")) {
                    rawBtns.substring(1, rawBtns.length - 1)
                        .split(',')
                        .mapNotNull { it.trim().toIntOrNull() }
                        .map { NativeButton.from(it) }
                        .distinct()
                } else emptyList()
                val safeButtons = when {
                    parsed.size in ComboPreset.MIN_TRIGGERS..ComboPreset.MAX_TRIGGERS -> parsed
                    else -> listOf(NativeButton.A, NativeButton.B)
                }
                val kind = when (obj.string("kind", "CHORD")) {
                    "MACRO" -> ComboPreset.Kind.MACRO
                    else -> ComboPreset.Kind.CHORD
                }
                ComboPreset(
                    id = obj.string("id"),
                    displayName = obj.string("name", obj.string("id")),
                    buttons = safeButtons,
                    kind = kind,
                    enabled = obj.bool("enabled", true),
                    landscapePosition = Pair(obj.double("landX", 0.85), obj.double("landY", 0.7)),
                    portraitPosition = Pair(obj.double("portX", 0.85), obj.double("portY", 0.7)),
                    foldablePosition = Pair(obj.double("foldX", 0.85), obj.double("foldY", 0.7)),
                    individualScale = obj.float("scale", 1.0f),
                )
            } catch (e: Exception) {
                Log.warning("[$TAG] Skipping malformed combo entry: ${e.message}")
                null
            }
        }

        return OverlayLayoutProfile(
            name = name,
            gameId = gameId,
            createdAt = createdAt,
            controls = controls,
            combos = combos,
        )
    }

    // ---------- tiny JSON helpers ----------

    /** Tokenise a single JSON object body (without the surrounding `{` / `}`). */
    private fun parseObject(body: String): Map<String, String> {
        val out = linkedMapOf<String, String>()
        var i = 0
        while (i < body.length) {
            val k1 = body.indexOf('"', i)
            if (k1 < 0) break
            val k2 = body.indexOf('"', k1 + 1)
            if (k2 < 0) break
            val key = body.substring(k1 + 1, k2)
            val colon = body.indexOf(':', k2 + 1)
            if (colon < 0) break
            var v = colon + 1
            var depth = 0
            var inStr = false
            var esc = false
            while (v < body.length) {
                val c = body[v]
                if (esc) { esc = false; v++; continue }
                if (c == '\\') { esc = true; v++; continue }
                if (c == '"') { inStr = !inStr; v++; continue }
                if (!inStr) {
                    when (c) {
                        '[', '{' -> depth++
                        ']', '}' -> depth--
                        ',' -> if (depth == 0) break
                    }
                }
                v++
            }
            out[key] = body.substring(colon + 1, v).trim()
            i = v + 1
        }
        return out
    }

    /** Parse a JSON array literal back into a list of object bodies. */
    private fun Map<String, String>.array(key: String): List<Map<String, String>> {
        val raw = this[key] ?: return emptyList()
        if (!raw.startsWith("[") || !raw.endsWith("]")) return emptyList()
        val inner = raw.substring(1, raw.length - 1)
        val out = mutableListOf<Map<String, String>>()
        var depth = 0
        var start = -1
        var inStr = false
        var esc = false
        for (i in inner.indices) {
            val c = inner[i]
            if (esc) { esc = false; continue }
            if (c == '\\') { esc = true; continue }
            if (c == '"') { inStr = !inStr; continue }
            if (inStr) continue
            when (c) {
                '{' -> { if (depth == 0) start = i + 1; depth++ }
                '}' -> { depth--; if (depth == 0 && start >= 0) {
                    out += parseObject(inner.substring(start, i))
                    start = -1
                } }
            }
        }
        return out
    }

    private fun Map<String, String>.string(key: String, default: String = ""): String =
        this[key]?.trim()?.trim('"') ?: default

    private fun Map<String, String>.bool(key: String, default: Boolean): Boolean =
        this[key]?.trim()?.toBooleanStrictOrNull() ?: default

    private fun Map<String, String>.double(key: String, default: Double): Double =
        this[key]?.trim()?.toDoubleOrNull() ?: default

    private fun Map<String, String>.float(key: String, default: Float): Float =
        this[key]?.trim()?.toFloatOrNull() ?: default

    private fun Map<String, String>.long(key: String, default: Long): Long =
        this[key]?.trim()?.toLongOrNull() ?: default
}
