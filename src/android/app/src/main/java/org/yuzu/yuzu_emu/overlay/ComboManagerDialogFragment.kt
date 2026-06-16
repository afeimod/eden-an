// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.content.DialogInterface
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import java.util.UUID
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogComboManagerBinding
import org.yuzu.yuzu_emu.features.input.model.NativeButton
import org.yuzu.yuzu_emu.overlay.model.ComboPreset
import org.yuzu.yuzu_emu.overlay.model.ComboStore

/**
 * 组合键管理面板：列出全部组合（带显示/隐藏开关），新增自定义组合，
 * 一键加载 4 个内置预设。点击行进入编辑器；拖动 overlay 上的 pad
 * 可以调整位置。
 */
class ComboManagerDialogFragment : BottomSheetDialogFragment() {
    private var _binding: DialogComboManagerBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: ComboListAdapter

    // Watches our child editor / picker fragments so we re-pull the list
    // whenever they go away (after the user adds / edits / deletes).
    private val childLifecycle = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentResumed(fm: FragmentManager, f: Fragment) {
            if (f is ComboEditorDialogFragment || f is ComboPresetPickerDialogFragment) {
                reloadList()
            }
        }

        override fun onFragmentDestroyed(fm: FragmentManager, f: Fragment) {
            if (f is ComboEditorDialogFragment || f is ComboPresetPickerDialogFragment) {
                reloadList()
                refreshOverlay()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        parentFragmentManager.registerFragmentLifecycleCallbacks(childLifecycle, false)
    }

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
                    .show(parentFragmentManager, EDITOR_TAG)
            }
        )

        binding.comboList.layoutManager = LinearLayoutManager(requireContext())
        binding.comboList.adapter = adapter

        binding.addComboButton.setOnClickListener {
            val id = "custom_${UUID.randomUUID()}"
            val newCombo = ComboPreset(
                id = id,
                displayName = getString(R.string.combo_default_name),
                buttons = listOf(NativeButton.A, NativeButton.B),
                enabled = true,
            )
            val list = ComboStore.load(requireContext())
            list += newCombo
            ComboStore.save(requireContext(), list)
            reloadList()
            refreshOverlay()
            ComboEditorDialogFragment.newInstance(id)
                .show(parentFragmentManager, EDITOR_TAG)
        }

        binding.loadPresetButton.setOnClickListener {
            ComboPresetPickerDialogFragment().show(parentFragmentManager, PRESETS_TAG)
        }

        reloadList()
    }

    override fun onDismiss(dialog: DialogInterface) {
        // Detach lifecycle watcher to avoid leaks.
        runCatching {
            parentFragmentManager.unregisterFragmentLifecycleCallbacks(childLifecycle)
        }
        super.onDismiss(dialog)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun reloadList() {
        if (_binding == null) return
        adapter.submit(ComboStore.load(requireContext()))
    }

    private fun refreshOverlay() {
        (activity as? RefreshOverlayHost)?.refreshInputOverlay()
    }

    interface RefreshOverlayHost {
        fun refreshInputOverlay()
    }

    companion object {
        const val TAG = "ComboManagerDialog"
        private const val EDITOR_TAG = "combo_editor"
        private const val PRESETS_TAG = "combo_presets"
    }
}
