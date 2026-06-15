// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.yuzu.yuzu_emu.overlay

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.snackbar.Snackbar
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.yuzu.yuzu_emu.R
import org.yuzu.yuzu_emu.databinding.DialogOverlayThemeBinding

/**
 * Bottom sheet that lets the user pick a theme zip (via SAF) or clear the
 * currently active theme. The current state is summarised with the file
 * name and the count of bundled assets.
 */
class OverlayThemePickerDialogFragment : BottomSheetDialogFragment() {
    private var _binding: DialogOverlayThemeBinding? = null
    private val binding get() = _binding!!

    private val openZip = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val uri = result.data?.data ?: return@registerForActivityResult
            // Persist read access for this uri so we can re-apply the theme
            // after a process restart.
            runCatching {
                requireContext().contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            applyTheme(uri)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = DialogOverlayThemeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Bottom sheet behaviour: expand by default and skip the half-state
        // so the user always sees the full picker.
        (dialog as? BottomSheetDialog)?.behavior?.apply {
            state = BottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }

        binding.title.text = getString(R.string.overlay_theme_picker_title)
        binding.description.text = getString(R.string.overlay_theme_picker_description)
        binding.buttonPick.setOnClickListener { launchPicker() }
        binding.buttonClear.setOnClickListener { clearTheme() }

        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val ctx = requireContext()
        val saved = OverlayThemeManager.savedUri(ctx)
        if (saved.isEmpty()) {
            binding.statusTitle.text = getString(R.string.overlay_theme_status_none)
            binding.statusDetail.text = getString(R.string.overlay_theme_status_none_detail)
            binding.buttonClear.isEnabled = false
        } else {
            binding.statusTitle.text = getString(R.string.overlay_theme_status_active)
            val file = File(OverlayThemeManager.activeDir(ctx), "background.png")
            val fileCount = OverlayThemeManager.activeDir(ctx).listFiles()?.size ?: 0
            val hasBg = file.exists()
            val bgText = getString(
                if (hasBg) R.string.overlay_theme_yes else R.string.overlay_theme_no
            )
            binding.statusDetail.text = getString(
                R.string.overlay_theme_status_active_detail,
                fileCount,
                bgText
            )
            binding.buttonClear.isEnabled = true
        }
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            // Some providers (Files, Solid) advertise the zip mime type; we
            // also fall back to application/octet-stream below.
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf("application/zip", "application/octet-stream")
            )
        }
        openZip.launch(Intent.createChooser(intent, getString(R.string.overlay_theme_picker_title)))
    }

    private fun applyTheme(uri: Uri) {
        val ctx = requireContext()
        binding.buttonPick.isEnabled = false
        binding.buttonClear.isEnabled = false
        binding.statusTitle.text = getString(R.string.overlay_theme_installing)

        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                OverlayThemeManager.install(ctx, uri)
            }
            binding.buttonPick.isEnabled = true
            binding.buttonClear.isEnabled = true

            if (count < 0) {
                Snackbar.make(
                    binding.root,
                    R.string.overlay_theme_install_failed,
                    Snackbar.LENGTH_LONG
                ).show()
            } else {
                Snackbar.make(
                    binding.root,
                    getString(R.string.overlay_theme_install_ok, count),
                    Snackbar.LENGTH_LONG
                ).show()
            }
            refreshStatus()
        }
    }

    private fun clearTheme() {
        OverlayThemeManager.uninstall(requireContext())
        refreshStatus()
        Snackbar.make(
            binding.root,
            R.string.overlay_theme_cleared,
            Snackbar.LENGTH_SHORT
        ).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "OverlayThemePickerDialogFragment"
    }
}
