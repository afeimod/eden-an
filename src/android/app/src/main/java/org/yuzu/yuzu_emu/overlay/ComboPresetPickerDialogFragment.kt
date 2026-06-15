// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.UUID
import org.yuzu.yuzu_emu.databinding.CardComboPresetItemBinding
import org.yuzu.yuzu_emu.databinding.DialogComboPresetPickerBinding
import org.yuzu.yuzu_emu.overlay.model.ComboPreset
import org.yuzu.yuzu_emu.overlay.model.ComboStore

/**
 * "Load preset" bottom sheet - lists built-in combos the user can quickly
 * add to their personal combo list.
 */
class ComboPresetPickerDialogFragment : BottomSheetDialogFragment() {
    private var _binding: DialogComboPresetPickerBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogComboPresetPickerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = PresetAdapter(ComboPreset.BUILT_IN_PRESETS) { preset ->
            // Add (or replace) the picked preset with a fresh id so the
            // user can have multiple customizations of the same logical
            // preset if they want.
            val list = ComboStore.load(requireContext())
            val copy = preset.copy(id = "preset_${UUID.randomUUID()}")
            list += copy
            ComboStore.save(requireContext(), list)
            (activity as? ComboManagerDialogFragment.RefreshOverlayHost)
                ?.refreshInputOverlay()
            dismiss()
        }
        binding.presetList.layoutManager = LinearLayoutManager(requireContext())
        binding.presetList.adapter = adapter
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private class PresetAdapter(
        private val presets: List<ComboPreset>,
        private val onClick: (ComboPreset) -> Unit,
    ) : RecyclerView.Adapter<PresetAdapter.VH>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val binding = CardComboPresetItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return VH(binding)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.binding.presetName.text = presets[position].displayName
            holder.itemView.setOnClickListener { onClick(presets[position]) }
        }

        override fun getItemCount(): Int = presets.size

        class VH(val binding: CardComboPresetItemBinding) :
            RecyclerView.ViewHolder(binding.root)
    }
}
