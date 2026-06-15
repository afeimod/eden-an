// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import org.yuzu.yuzu_emu.databinding.CardComboItemBinding
import org.yuzu.yuzu_emu.overlay.model.ComboPreset

class ComboListAdapter(
    private val onToggle: (ComboPreset, Boolean) -> Unit,
    private val onClick: (ComboPreset) -> Unit,
) : RecyclerView.Adapter<ComboListAdapter.VH>() {

    private val items = mutableListOf<ComboPreset>()

    fun submit(list: List<ComboPreset>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = CardComboItemBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val preset = items[position]
        holder.binding.comboItemName.text = preset.displayName
        holder.binding.comboItemSummary.text = holder.itemView.context.getString(
            org.yuzu.yuzu_emu.R.string.combo_item_summary_fmt,
            preset.triggers.joinToString("+") { it.name },
            preset.target.name
        )
        holder.binding.comboItemVisible.setOnCheckedChangeListener(null)
        holder.binding.comboItemVisible.isChecked = preset.enabled
        holder.binding.comboItemVisible.setOnCheckedChangeListener { _, checked ->
            onToggle(preset, checked)
        }
        holder.itemView.setOnClickListener { onClick(preset) }
    }

    override fun getItemCount(): Int = items.size

    class VH(val binding: CardComboItemBinding) : RecyclerView.ViewHolder(binding.root)
}
