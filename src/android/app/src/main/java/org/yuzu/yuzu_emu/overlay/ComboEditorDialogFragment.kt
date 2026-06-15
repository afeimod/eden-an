// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.app.Dialog
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.DialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogComboEditorBinding
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.overlay.model.ComboPreset
import org.yuzu.yuzu_emu.overlay.model.ComboStore

/**
 * Edits a single [ComboPreset]'s name, child trigger keys, target key,
 * and visibility. The "Delete" button is hidden for built-in presets.
 */
class ComboEditorDialogFragment : DialogFragment() {
    private var _binding: DialogComboEditorBinding? = null
    private val binding get() = _binding!!

    private var comboId: String? = null
    private val triggerButtons = mutableSetOf<NativeButton>()
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

        val list = ComboStore.load(requireContext())
        val preset = list.firstOrNull { it.id == comboId }
        if (preset != null) {
            binding.comboNameInput.setText(preset.displayName)
            binding.comboEnabledSwitch.isChecked = preset.enabled
            triggerButtons.clear()
            triggerButtons.addAll(preset.triggers)
            targetButton = preset.target
        } else {
            binding.comboEnabledSwitch.isChecked = true
            triggerButtons.clear()
            triggerButtons.add(NativeButton.L)
            triggerButtons.add(NativeButton.R)
            targetButton = NativeButton.ZL
        }

        bindTriggerChips()
        bindTargetChips()
        wireButtons(preset != null && isBuiltIn(preset))

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.combo_editor_title)
            .setView(binding.root)
            .create()
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
                    if (checked) triggerButtons += btn
                    else triggerButtons -= btn
                    enforceTriggerLimit()
                    refreshTriggerChips()
                }
            }
            group.addView(chip)
        }
    }

    private fun refreshTriggerChips() {
        val group = binding.triggersChipGroup
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            val btn = triggerCandidates.getOrNull(i) ?: continue
            chip.isChecked = btn in triggerButtons
        }
    }

    /** Enforce the 2-8 child key constraint. Disables unchecked chips at limit. */
    private fun enforceTriggerLimit() {
        val group = binding.triggersChipGroup
        val atMax = triggerButtons.size >= ComboPreset.MAX_TRIGGERS
        for (i in 0 until group.childCount) {
            val chip = group.getChildAt(i) as? Chip ?: continue
            val btn = triggerCandidates.getOrNull(i) ?: continue
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

    private fun wireButtons(isPreset: Boolean) {
        binding.comboDeleteButton.visibility =
            if (isPreset) android.view.View.GONE else android.view.View.VISIBLE
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

    override fun onPause() {
        super.onPause()
        // Auto-save when dialog closes, even if the user just hit back.
        saveCurrent()
    }

    private fun saveCurrent() {
        val id = comboId ?: return
        val list = ComboStore.load(requireContext())
        val existing = list.firstOrNull { it.id == id } ?: return
        val name = binding.comboNameInput.text?.toString()?.trim().orEmpty()
            .ifEmpty { existing.displayName }
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
