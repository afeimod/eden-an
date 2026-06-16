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
 * 编辑单条组合键的弹窗。UI 风格与普通按键编辑一致：名称、触发键
 * chips（2-8 个）、目标键 chips、显示/隐藏、删除。
 *
 * 名字默认按 "A + B → ZL" 这种模式生成，用户可以在输入框覆盖。
 */
class ComboEditorDialogFragment : DialogFragment() {
    private var _binding: DialogComboEditorBinding? = null
    private val binding get() = _binding!!

    private var comboId: String? = null
    private val triggerButtons = linkedSetOf<NativeButton>()
    private var targetButton: NativeButton? = null

    private val triggerCandidates = listOf(
        NativeButton.A, NativeButton.B, NativeButton.X, NativeButton.Y,
        NativeButton.L, NativeButton.R, NativeButton.ZL, NativeButton.ZR,
        NativeButton.Plus, NativeButton.Minus,
        NativeButton.DUp, NativeButton.DDown, NativeButton.DLeft, NativeButton.DRight,
        NativeButton.LStick, NativeButton.RStick,
    )

    private val targetCandidates = listOf(
        NativeButton.A, NativeButton.B, NativeButton.X, NativeButton.Y,
        NativeButton.L, NativeButton.R, NativeButton.ZL, NativeButton.ZR,
        NativeButton.Plus, NativeButton.Minus,
        NativeButton.Home, NativeButton.Capture,
        NativeButton.DUp, NativeButton.DDown, NativeButton.DLeft, NativeButton.DRight,
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
            triggerButtons.clear()
            triggerButtons.addAll(preset.triggers)
            targetButton = preset.target
        } else {
            binding.comboEnabledSwitch.isChecked = true
            triggerButtons.clear()
            triggerButtons += NativeButton.L
            triggerButtons += NativeButton.R
            targetButton = NativeButton.ZL
            // Auto-generate the default name from the selection.
            binding.comboNameInput.setText(autoName())
        }

        bindTriggerChips()
        bindTargetChips()
        wireButtons(preset != null && isBuiltIn(preset))

        // Live-update default name as the user changes the trigger / target
        // selection, but only if the field still equals the previously
        // generated name (so user-edited names aren't overwritten).
        val autoNameListener = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val current = s?.toString().orEmpty()
                if (current.isEmpty() || current == lastAutoName) {
                    val gen = autoName()
                    lastAutoName = gen
                    binding.comboNameInput.setText(gen)
                    binding.comboNameInput.setSelection(binding.comboNameInput.text?.length ?: 0)
                }
            }
        }
        // Auto-name refresh when chips toggle:
        val chipWatcher: () -> Unit = {
            val gen = autoName()
            lastAutoName = gen
            val cur = binding.comboNameInput.text?.toString().orEmpty()
            if (cur.isEmpty()) {
                binding.comboNameInput.setText(gen)
            }
        }
        binding.comboNameInput.addTextChangedListener(autoNameListener)
        binding.triggersChipGroup.setOnCheckedStateChangeListener { _, _ ->
            enforceTriggerLimit()
            chipWatcher()
        }
        binding.targetChipGroup.setOnCheckedStateChangeListener { _, _ -> chipWatcher() }

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.combo_editor_title)
            .setView(binding.root)
            .setPositiveButton(R.string.combo_editor_done) { _, _ ->
                saveCurrent(close = true)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
    }

    private var lastAutoName: String = ""

    private fun autoName(): String {
        val left = triggerButtons.joinToString(" + ") { buttonLabel(it) }
        val right = targetButton?.let { buttonLabel(it) } ?: "?"
        return "$left → $right"
    }

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

    private fun isBuiltIn(p: ComboPreset): Boolean =
        ComboPreset.BUILT_IN_PRESETS.any { it.id == p.id }

    private fun bindTriggerChips() {
        val group = binding.triggersChipGroup
        group.removeAllViews()
        triggerCandidates.forEach { btn ->
            val chip = Chip(requireContext()).apply {
                text = btn.name
                isCheckable = true
                isChecked = btn in triggerButtons
                setOnCheckedChangeListener { _, checked ->
                    if (checked) triggerButtons += btn else triggerButtons -= btn
                    enforceTriggerLimit()
                }
            }
            group.addView(chip)
        }
        enforceTriggerLimit()
    }

    /** Cap trigger count at MAX_TRIGGERS; disable the remaining unchecked chips. */
    private fun enforceTriggerLimit() {
        val group = binding.triggersChipGroup
        val atMax = triggerButtons.size >= ComboPreset.MAX_TRIGGERS
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

    private fun bindTargetChips() {
        val group = binding.targetChipGroup
        group.removeAllViews()
        targetCandidates.forEach { btn ->
            val chip = Chip(requireContext()).apply {
                text = btn.name
                isCheckable = true
                isChecked = btn == targetButton
                setOnCheckedChangeListener { _, checked ->
                    if (checked) targetButton = btn
                    else if (targetButton == btn) targetButton = null
                }
            }
            group.addView(chip)
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
        val name = if (typed.isNotEmpty() && typed != autoName()) typed else autoName()
        val trig = triggerButtons.toList()
            .takeIf { it.size in ComboPreset.MIN_TRIGGERS..ComboPreset.MAX_TRIGGERS }
            ?: existing.triggers
        val tgt = targetButton ?: existing.target
        val updated = existing.copy(
            displayName = name,
            triggers = trig,
            target = tgt,
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
