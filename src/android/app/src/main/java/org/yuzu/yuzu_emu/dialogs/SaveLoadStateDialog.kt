// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.dialogs

import android.app.Dialog
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.yuzu.yuzu_emu.NativeLibrary
import org.yuzu.yuzu_emu.R

/**
 * 3-slot save/load state picker shown from the in-game drawer.
 *
 * Each row contains a slot label (with relative time of last save) and two buttons:
 *   [Save] overwrites the slot for the running game.
 *   [Load] restores from the slot. We pause emulation before issuing the native
 *          load, then resume (or stay paused, depending on prior state) after.
 *
 * NOTE: Native load is destructive to live CPU state. The caller is expected to
 * already have the drawer open (i.e. emulation may or may not be paused). For
 * safety we always force-pause before load and resume after success.
 */
class SaveLoadStateDialog : DialogFragment() {

    private enum class Action { SAVE, LOAD }

    private data class SlotViews(
        val label: TextView,
        val saveButton: Button,
        val loadButton: Button
    )

    private var contentView: View? = null
    private val slots: List<SlotViews> by lazy {
        val view = requireNotNull(contentView) { "Dialog content view not initialized" }
        listOf(
            SlotViews(
                view.findViewById(R.id.slot_label_1),
                view.findViewById(R.id.slot_save_button_1),
                view.findViewById(R.id.slot_load_button_1)
            ),
            SlotViews(
                view.findViewById(R.id.slot_label_2),
                view.findViewById(R.id.slot_save_button_2),
                view.findViewById(R.id.slot_load_button_2)
            ),
            SlotViews(
                view.findViewById(R.id.slot_label_3),
                view.findViewById(R.id.slot_save_button_3),
                view.findViewById(R.id.slot_load_button_3)
            )
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val view = requireActivity().layoutInflater.inflate(
            R.layout.dialog_saveload_state, null, false
        )
        contentView = view
        view.findViewById<TextView>(R.id.dialog_title).setText(
            R.string.emulation_save_load_state
        )

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setView(view)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            slots.forEachIndexed { index, slot ->
                refreshSlot(index, slot)
                val oneBasedSlot = index + 1
                slot.saveButton.setOnClickListener {
                    handleAction(oneBasedSlot, Action.SAVE)
                }
                slot.loadButton.setOnClickListener {
                    handleAction(oneBasedSlot, Action.LOAD)
                }
            }
        }

        return dialog
    }

    override fun onDestroyView() {
        super.onDestroyView()
        contentView = null
    }

    private fun refreshSlot(zeroBasedIndex: Int, views: SlotViews) {
        val oneBased = zeroBasedIndex + 1
        val creationTimeMs = NativeLibrary.getUnixTimeOfStateSlot(oneBased) * 1000L
        views.label.text = if (creationTimeMs > 0L) {
            val rel = DateUtils.getRelativeTimeSpanString(
                creationTimeMs,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            )
            getString(R.string.emulation_state_slot_time, oneBased, rel)
        } else {
            getString(R.string.emulation_state_slot_empty, oneBased)
        }
        // Disable Load when slot is empty.
        views.loadButton.isEnabled = creationTimeMs > 0L
    }

    private fun handleAction(slot: Int, action: Action) {
        when (action) {
            Action.SAVE -> {
                val ok = NativeLibrary.saveState(slot)
                showToast(if (ok) R.string.emulation_state_saved else R.string.emulation_state_save_failed)
                if (ok) {
                    slots.getOrNull(slot - 1)?.let { refreshSlot(slot - 1, it) }
                }
            }
            Action.LOAD -> {
                if (!NativeLibrary.stateSlotExists(slot)) {
                    showToast(R.string.emulation_state_slot_empty_msg)
                    return
                }
                // Pause first so the load isn't racing against the CPU thread.
                val wasPaused = NativeLibrary.isPaused()
                if (!wasPaused) {
                    NativeLibrary.pauseEmulation()
                }
                val ok = NativeLibrary.loadState(slot)
                if (ok) {
                    showToast(R.string.emulation_state_loaded)
                    if (!wasPaused) {
                        NativeLibrary.unpauseEmulation()
                    }
                    dismissAllowingStateLoss()
                } else {
                    showToast(R.string.emulation_state_load_failed)
                    // Stay paused on failure so the user can investigate.
                }
            }
        }
    }

    private fun showToast(resId: Int) {
        Toast.makeText(requireContext(), resId, Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val TAG = "SaveLoadStateDialog"

        fun newInstance(): SaveLoadStateDialog = SaveLoadStateDialog()
    }
}