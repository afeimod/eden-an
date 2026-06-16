// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.chip.Chip
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogComboEditorBinding
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.overlay.model.ComboPreset
import org.yuzu.yuzu_emu.overlay.model.ComboStore

/**
 * 编辑单条组合键的弹窗。
 *
 * - 名称：用户自由输入；选中按钮后自动按 "A + B + A" 这种形式填入，
 *   用户可覆盖。已起过名后不再覆盖。
 * - 按钮 chips（2-8 个）：选中的 chip 即「按下 combo pad 时一起生效的按键」。
 *
 * 没有「目标键」——因为这条组合的语义就是「同时按下选中的所有键」。
 */
class ComboEditorDialogFragment : DialogFragment() {
    private var _binding: DialogComboEditorBinding? = null
    private val binding get() = _binding!!

    private var comboId: String? = null
    private val buttons = linkedSetOf<NativeButton>()
    private var lastAutoName: String = ""
    private var programmaticChange: Boolean = false
    private var currentKind: ComboPreset.Kind = ComboPreset.Kind.CHORD

    /**
     * If the displayed name is empty or still the previously auto-generated
     * one, regenerate it from the current [buttons] selection. The flag
     * [programmaticChange] prevents re-entering the TextWatcher.
     */
    private fun maybeRefreshAutoName(force: Boolean) {
        if (_binding == null) return
        val cur = binding.comboNameInput.text?.toString().orEmpty()
        if (!force && cur.isNotEmpty() && cur != lastAutoName) return
        val gen = autoName()
        if (gen == cur) return
        lastAutoName = gen
        programmaticChange = true
        binding.comboNameInput.setText(gen)
        binding.comboNameInput.setSelection(gen.length)
        programmaticChange = false
    }

    private val candidates = listOf(
        NativeButton.A, NativeButton.B, NativeButton.X, NativeButton.Y,
        NativeButton.L, NativeButton.R, NativeButton.ZL, NativeButton.ZR,
        NativeButton.Plus, NativeButton.Minus,
        NativeButton.DUp, NativeButton.DDown, NativeButton.DLeft, NativeButton.DRight,
        NativeButton.LStick, NativeButton.RStick,
        NativeButton.Home, NativeButton.Capture,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        comboId = arguments?.getString(ARG_COMBO_ID)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogComboEditorBinding.inflate(layoutInflater)

        val preset = ComboStore.load(requireContext())
            .firstOrNull { it.id == comboId }
        if (preset != null) {
            binding.comboNameInput.setText(preset.displayName)
            binding.comboEnabledSwitch.isChecked = preset.enabled
            buttons.clear()
            buttons.addAll(preset.buttons)
            currentKind = preset.kind
        } else {
            binding.comboEnabledSwitch.isChecked = true
            buttons.clear()
            buttons += NativeButton.A
            buttons += NativeButton.B
            currentKind = ComboPreset.Kind.CHORD
            binding.comboNameInput.setText(autoName())
        }
        lastAutoName = autoName()
        applyKindToRadio()

        bindChips()
        wireButtons(preset != null && isBuiltIn(preset))

        // User switching the kind radio: store it. We don't auto-rewrite
        // kind when the chips change (otherwise users couldn't keep a
        // CHORD that happens to include a direction key).
        binding.kindRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentKind = when (checkedId) {
                R.id.kindMacro -> ComboPreset.Kind.MACRO
                else -> ComboPreset.Kind.CHORD
            }
        }

        // 当用户改动 chip 时刷新自动名（仅当当前名字仍是自动生成时）。
        binding.buttonsChipGroup.setOnCheckedStateChangeListener { _, _ ->
            enforceLimit()
            maybeRefreshAutoName(force = false)
        }

        // TextWatcher：用户清空输入框时回填自动名，否则只记录当前自动名。
        binding.comboNameInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                if (programmaticChange) return
                val cur = s?.toString().orEmpty()
                if (cur.isEmpty()) {
                    // Treat empty as a request to regenerate. We schedule a
                    // post so we never call setText() from inside a text
                    // watcher (which would re-enter afterTextChanged and
                    // stack-overflow on some OEM ROMs).
                    val gen = autoName()
                    lastAutoName = gen
                    binding.comboNameInput.post {
                        if (_binding == null) return@post
                        programmaticChange = true
                        binding.comboNameInput.setText(gen)
                        binding.comboNameInput.setSelection(gen.length)
                        programmaticChange = false
                    }
                } else if (cur == autoName()) {
                    lastAutoName = cur
                }
            }
        })

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.combo_editor_title)
            .setView(binding.root)
            .setPositiveButton(R.string.combo_editor_done) { _, _ -> saveCurrent(close = true) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private fun isBuiltIn(p: ComboPreset): Boolean =
        ComboPreset.BUILT_IN_PRESETS.any { it.id == p.id }

    private fun autoName(): String =
        buttons.joinToString(" + ") { buttonLabel(it) }

    private fun buttonLabel(b: NativeButton): String = when (b) {
        NativeButton.A -> "A"
        NativeButton.B -> "B"
        NativeButton.X -> "X"
        NativeButton.Y -> "Y"
        NativeButton.L -> "L"
        NativeButton.R -> "R"
        NativeButton.ZL -> "ZL"
        NativeButton.ZR -> "ZR"
        NativeButton.Plus -> "+"
        NativeButton.Minus -> "-"
        NativeButton.Home -> "Home"
        NativeButton.Capture -> "Capture"
        NativeButton.DUp -> "↑"
        NativeButton.DDown -> "↓"
        NativeButton.DLeft -> "←"
        NativeButton.DRight -> "→"
        NativeButton.LStick -> "L Stick"
        NativeButton.RStick -> "R Stick"
        else -> b.name
    }

    private fun bindChips() {
        val group = binding.buttonsChipGroup
        group.removeAllViews()
        candidates.forEach { btn ->
            val chip = Chip(requireContext()).apply {
                text = buttonLabel(btn)
                isCheckable = true
                isChecked = btn in buttons
                setOnCheckedChangeListener { _, checked ->
                    if (checked) buttons += btn else buttons -= btn
                    enforceLimit()
                }
            }
            group.addView(chip)
        }
        enforceLimit()
    }

    private fun applyKindToRadio() {
        val id = when (currentKind) {
            ComboPreset.Kind.MACRO -> R.id.kindMacro
            ComboPreset.Kind.CHORD -> R.id.kindChord
        }
        if (binding.kindRadioGroup.checkedRadioButtonId != id) {
            binding.kindRadioGroup.check(id)
        }
    }

    private fun enforceLimit() {
        val group = binding.buttonsChipGroup
        val atMax = buttons.size >= ComboPreset.MAX_TRIGGERS
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            if (!chip.isChecked && atMax) {
                chip.isEnabled = false
                chip.alpha = 0.45f
            } else {
                chip.isEnabled = true
                chip.alpha = 1f
            }
        }
    }

    private fun wireButtons(isBuiltIn: Boolean) {
        binding.comboDeleteButton.visibility =
            if (isBuiltIn) android.view.View.GONE else android.view.View.VISIBLE
        binding.comboDeleteButton.setOnClickListener {
            val id = comboId ?: return@setOnClickListener
            val list = ComboStore.load(requireContext())
            list.removeAll { it.id == id }
            ComboStore.save(requireContext(), list)
            (activity as? ComboManagerDialogFragment.RefreshOverlayHost)
                ?.refreshInputOverlay()
            dismiss()
        }
    }

    private fun saveCurrent(close: Boolean) {
        val id = comboId ?: return
        val list = ComboStore.load(requireContext())
        val existing = list.firstOrNull { it.id == id } ?: return
        val typed = binding.comboNameInput.text?.toString()?.trim().orEmpty()
        val name = if (typed.isNotEmpty()) typed else autoName()
        val chosen = buttons.toList()
            .takeIf { it.size in ComboPreset.MIN_TRIGGERS..ComboPreset.MAX_TRIGGERS }
            ?: existing.buttons
        val updated = existing.copy(
            displayName = name,
            buttons = chosen,
            kind = currentKind,
            enabled = binding.comboEnabledSwitch.isChecked,
        )
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = updated
        ComboStore.save(requireContext(), list)
        (activity as? ComboManagerDialogFragment.RefreshOverlayHost)
            ?.refreshInputOverlay()
        if (close) dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_COMBO_ID = "combo_id"

        fun newInstance(comboId: String) = ComboEditorDialogFragment().apply {
            arguments = Bundle().apply { putString(ARG_COMBO_ID, comboId) }
        }
    }
}
