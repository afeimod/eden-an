// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay.model

import android.content.Context
import android.content.SharedPreferences
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.utils.Log

/**
 * Persists user-defined [ComboPreset]s to a dedicated SharedPreferences file
 * keyed by combo id. Kept separate from the native overlay control data so
 * that no native ABI changes are required to ship combo support.
 */
object ComboStore {
    private const val PREFS_NAME = "virtual_combo_prefs"
    private const val KEY_JSON = "combos_json"
    private const val VERSION_KEY = "combos_version"
    // v3: added "kind" field ("CHORD" or "MACRO"). Defaults to CHORD
    // (parallel press) for old saves; for combos that contain any
    // directional button the upgrader switches to MACRO.
    private const val CURRENT_VERSION = 3

    // Naive JSON helpers - we don't pull Gson/Moshi in for one tiny model.
    private fun presetsToJson(presets: List<ComboPreset>): String {
        val sb = StringBuilder("[")
        presets.forEachIndexed { index, p ->
            if (index > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(jsonStr(p.id)).append(',')
            sb.append("\"name\":").append(jsonStr(p.displayName)).append(',')
            sb.append("\"enabled\":").append(p.enabled).append(',')
            sb.append("\"buttons\":[")
            p.buttons.forEachIndexed { i, t ->
                if (i > 0) sb.append(',')
                sb.append(t.int)
            }
            sb.append("],\"kind\":\"").append(p.kind.name).append("\",\"landX\":").append(p.landscapePosition.first)
                .append(",\"landY\":").append(p.landscapePosition.second)
                .append(",\"portX\":").append(p.portraitPosition.first)
                .append(",\"portY\":").append(p.portraitPosition.second)
                .append(",\"foldX\":").append(p.foldablePosition.first)
                .append(",\"foldY\":").append(p.foldablePosition.second)
                .append(",\"scale\":").append(p.individualScale)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun jsonStr(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    private fun presetsFromJson(json: String): MutableList<ComboPreset> {
        val list = mutableListOf<ComboPreset>()
        try {
            var i = 0
            while (i < json.length) {
                val open = json.indexOf('{', i)
                if (open < 0) break
                var depth = 0
                var close = -1
                var j = open
                while (j < json.length) {
                    when (json[j]) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                close = j
                                break
                            }
                        }
                    }
                    j++
                }
                if (close < 0) break
                val obj = json.substring(open + 1, close)
                list += parseObject(obj)
                i = close + 1
            }
        } catch (e: Exception) {
            Log.error("ComboStore: failed to parse json: ${e.message}")
        }
        return list
    }

    private fun parseObject(obj: String): ComboPreset {
        val map = linkedMapOf<String, String>()
        var i = 0
        while (i < obj.length) {
            val k1 = obj.indexOf('"', i)
            if (k1 < 0) break
            val k2 = obj.indexOf('"', k1 + 1)
            if (k2 < 0) break
            val key = obj.substring(k1 + 1, k2)
            val colon = obj.indexOf(':', k2 + 1)
            if (colon < 0) break
            var depth = 0
            var v = colon + 1
            var inStr = false
            var esc = false
            while (v < obj.length) {
                val c = obj[v]
                if (esc) { esc = false; v++; continue }
                if (c == '\\') { esc = true; v++; continue }
                if (c == '"') { inStr = !inStr; v++; continue }
                if (!inStr) {
                    when (c) {
                        '[' -> depth++
                        ']' -> depth--
                        ',' -> if (depth == 0) break
                    }
                }
                v++
            }
            val raw = obj.substring(colon + 1, v).trim()
            map[key] = raw
            i = v + 1
        }

        val id = map["id"]?.trim('"') ?: error("missing id")
        val name = map["name"]?.trim('"') ?: id
        val enabled = map["enabled"]?.toBooleanStrictOrNull() ?: false

        // v2 schema uses "buttons"; v1 used "triggers". We accept both.
        val rawList = map["buttons"] ?: map["triggers"]
        val buttonsRaw = rawList?.trim().orEmpty()
        val parsed = if (buttonsRaw.startsWith("[") && buttonsRaw.endsWith("]")) {
            buttonsRaw.substring(1, buttonsRaw.length - 1)
                .split(',')
                .mapNotNull { it.trim().toIntOrNull() }
                .map { NativeButton.from(it) }
                .distinct()
        } else emptyList()

        val land = Pair(
            map["landX"]?.toDoubleOrNull() ?: 0.85,
            map["landY"]?.toDoubleOrNull() ?: 0.7
        )
        val port = Pair(
            map["portX"]?.toDoubleOrNull() ?: 0.85,
            map["portY"]?.toDoubleOrNull() ?: 0.7
        )
        val fold = Pair(
            map["foldX"]?.toDoubleOrNull() ?: 0.85,
            map["foldY"]?.toDoubleOrNull() ?: 0.7
        )
        val scale = map["scale"]?.toFloatOrNull() ?: 1.0f

        val safeButtons = when {
            parsed.size in ComboPreset.MIN_TRIGGERS..ComboPreset.MAX_TRIGGERS -> parsed
            else -> listOf(NativeButton.A, NativeButton.B)
        }

        val kind = when (map["kind"]?.trim('"')) {
            "MACRO" -> ComboPreset.Kind.MACRO
            else -> ComboPreset.Kind.CHORD
        }

        return ComboPreset(
            id = id,
            displayName = name,
            buttons = safeButtons,
            kind = kind,
            enabled = enabled,
            landscapePosition = land,
            portraitPosition = port,
            foldablePosition = fold,
            individualScale = scale,
        )
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Load combos; on first run, seed with the built-in presets. */
    fun load(context: Context): MutableList<ComboPreset> {
        val p = prefs(context)
        val version = p.getInt(VERSION_KEY, 0)
        if (version < CURRENT_VERSION) {
            val seeded = ComboPreset.BUILT_IN_PRESETS.toMutableList()
            val oldJson = p.getString(KEY_JSON, null)
            if (oldJson != null) {
                val oldPresets = presetsFromJson(oldJson)
                for (old in oldPresets) {
                    if (seeded.none { it.id == old.id }) {
                        // v2 had no kind; auto-pick MACRO if it includes
                        // any direction key.
                        val imported = if (old.hasDirectional) {
                            old.copy(id = "${old.id}_imported", kind = ComboPreset.Kind.MACRO)
                        } else {
                            old.copy(id = "${old.id}_imported")
                        }
                        seeded += imported
                    }
                }
            }
            save(context, seeded)
            p.edit().putInt(VERSION_KEY, CURRENT_VERSION).apply()
            return seeded
        }
        val json = p.getString(KEY_JSON, null) ?: return mutableListOf()
        return presetsFromJson(json).toMutableList()
    }

    fun save(context: Context, presets: List<ComboPreset>) {
        val p = prefs(context)
        p.edit()
            .putString(KEY_JSON, presetsToJson(presets))
            .putInt(VERSION_KEY, CURRENT_VERSION)
            .apply()
    }

    fun resetToBuiltIns(context: Context): MutableList<ComboPreset> {
        val seeded = ComboPreset.BUILT_IN_PRESETS.toMutableList()
        save(context, seeded)
        return seeded
    }
}
