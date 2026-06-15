// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.UUID
import org.yuzu.yuzu_emu.databinding.DialogComboManagerBinding
import org.yuzu.yuzu_emu.overlay.model.ComboPreset
import org.yuzu.yuzu_emu.overlay.model.ComboStore

/**
 * Top-level combo management UI. Shows a list of all currently saved
 * combos (with on/off toggles), an "Add new" button, and a "Load preset"
 * shortcut for the four built-in combos.
 */
class ComboManagerDialogFragment : BottomSheetDialogFragment() {
    private var _binding: DialogComboManagerBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ComboListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogComboManagerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ComboListAdapter(
            onToggle = { preset, visible ->
                val list = ComboStore.load(requireContext())
                val target = list.firstOrNull { it.id == preset.id } ?: return@ComboListAdapter
                target.enabled = visible
                ComboStore.save(requireContext(), list)
                refreshOverlay()
            },
            onClick = { preset ->
                ComboEditorDialogFragment.newInstance(preset.id)
                    .show(parentFragmentManager, "combo_editor")
            }
        )

        binding.comboList.layoutManager = LinearLayoutManager(requireContext())
        binding.comboList.adapter = adapter

        binding.addComboButton.setOnClickListener {
            val id = "custom_${UUID.randomUUID()}"
            val newCombo = ComboPreset(
                id = id,
                displayName = "New combo",
                triggers = listOf(
                    org.yuzu.yuzu_emu.features.input.model.NativeButton.L,
                    org.yuzu.yuzu_emu.features.input.model.NativeButton.R,
                ),
                target = org.yuzu.yuzu_emu.features.input.model.NativeButton.ZL,
                enabled = true,
            )
            val list = ComboStore.load(requireContext())
            list += newCombo
            ComboStore.save(requireContext(), list)
            refreshOverlay()
            adapter.submit(ComboStore.load(requireContext()))
            ComboEditorDialogFragment.newInstance(id)
                .show(parentFragmentManager, "combo_editor")
        }

        binding.loadPresetButton.setOnClickListener {
            ComboPresetPickerDialogFragment().show(parentFragmentManager, "combo_presets")
        }

        adapter.submit(ComboStore.load(requireContext()))
    }

    override fun onResume() {
        super.onResume()
        // Re-pull in case the editor closed and changed something.
        adapter.submit(ComboStore.load(requireContext()))
        refreshOverlay()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun refreshOverlay() {
        // Trigger the InputOverlay in the activity to reload combos.
        (activity as? RefreshOverlayHost)?.refreshInputOverlay()
    }

    interface RefreshOverlayHost {
        fun refreshInputOverlay()
    }

    companion object {
        const val TAG = "ComboManagerDialog"
    }
}
