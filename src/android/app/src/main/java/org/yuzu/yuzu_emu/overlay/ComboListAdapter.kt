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
        val ctx = holder.itemView.context
        // 主标题：用户自定义名字（如“下前A”）
        holder.binding.comboItemName.text = preset.displayName
        // 副标题：按下的键组合（“↓ + → + A”），让用户记得这个组合干了什么
        holder.binding.comboItemSummary.text =
            preset.buttons.joinToString(" + ") { abbreviate(it) }
        holder.binding.comboItemVisible.setOnCheckedChangeListener(null)
        holder.binding.comboItemVisible.isChecked = preset.enabled
        holder.binding.comboItemVisible.setOnCheckedChangeListener { _, checked ->
            onToggle(preset, checked)
        }
        holder.itemView.setOnClickListener { onClick(preset) }
    }

    private fun abbreviate(b: org.yuzu.yuzu_emu.features.input.model.NativeButton): String =
        when (b) {
            org.yuzu.yuzu_emu.features.input.model.NativeButton.A -> "A"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.B -> "B"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.X -> "X"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.Y -> "Y"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.L -> "L"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.R -> "R"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.ZL -> "ZL"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.ZR -> "ZR"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.Plus -> "+"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.Minus -> "-"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.Home -> "Home"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.Capture -> "Capture"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.DUp -> "↑"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.DDown -> "↓"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.DLeft -> "←"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.DRight -> "→"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.LStick -> "L Stick"
            org.yuzu.yuzu_emu.features.input.model.NativeButton.RStick -> "R Stick"
            else -> b.name
        }

    override fun getItemCount(): Int = items.size

    class VH(val binding: CardComboItemBinding) : RecyclerView.ViewHolder(binding.root)
}
