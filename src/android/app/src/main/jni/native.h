// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2023 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#include <android/native_window_jni.h>
#include "common/android/applets/software_keyboard.h"
#include "common/detached_tasks.h"
#include "core/core.h"
#include "core/file_sys/registered_cache.h"
#include "core/hle/service/acc/profile_manager.h"
#include "core/perf_stats.h"
#include "frontend_common/content_manager.h"
#include "jni/emu_window/emu_window.h"
#include "video_core/rasterizer_interface.h"

#pragma once

#include <atomic>

class EmulationSession final {
public:
    explicit EmulationSession();
    ~EmulationSession() = default;

    static EmulationSession& GetInstance();
    const Core::System& System() const;
    Core::System& System();
    FileSys::ManualContentProvider* GetContentProvider();
    InputCommon::InputSubsystem& GetInputSubsystem();

    const EmuWindow_Android& Window() const;
    EmuWindow_Android& Window();
    ANativeWindow* NativeWindow() const;
    void SetNativeWindow(ANativeWindow* native_window);
    void SurfaceChanged();

    void InitializeGpuDriver(const std::string& hook_lib_dir, const std::string& custom_driver_dir,
                             const std::string& custom_driver_name,
                             const std::string& file_redirect_dir);

    bool IsRunning() const;
    bool IsPaused() const;
    void PauseEmulation();
    void UnPauseEmulation();
    void HaltEmulation();
    void RunEmulation();
    void ShutdownEmulation();

    const Core::PerfStatsResults& PerfStats();
    int ShadersBuilding();
    void ConfigureFilesystemProvider(const std::string& filepath);
    void ConfigureFilesystemProviderFromGameFolder(const std::string& filepath);
    void InitializeSystem(bool reload);
    void SetAppletId(int applet_id);
    Core::SystemResultStatus InitializeEmulation(const std::string& filepath,
                                                 const std::size_t program_index,
                                                 const bool frontend_initiated);

    /// Queue a savestate request. The emulation thread will pick it up
    /// within ~800ms and execute State::Save() there. Thread-safe.
    /// @param slot 1..NUM_STATES
    void RequestSaveState(int slot);

    /// Queue a loadstate request. The emulation thread will pick it up
    /// within ~800ms, pause the CPU threads, execute State::Load(), and
    /// resume. Thread-safe.
    /// @param slot 1..NUM_STATES
    void RequestLoadState(int slot);

    /// True iff a Save/Load request is currently pending.
    bool HasPendingStateRequest() const;

    /// True iff the previous pending request completed successfully.
    bool ConsumeStateRequestResult();

    /// 1-based slot of the last completed request, or 0.
    int ConsumeStateRequestSlot();

    /// "Save"/"Load" tag for the completed request, or 0 for none.
    int ConsumeStateRequestKind();

    Common::Android::SoftwareKeyboard::AndroidKeyboard* SoftwareKeyboard();

    static void OnEmulationStarted();

    static u64 GetProgramId(JNIEnv* env, jstring jprogramId);

private:
    static void LoadDiskCacheProgress(VideoCore::LoadCallbackStage stage, int progress, int max);
    static void OnEmulationStopped(Core::SystemResultStatus result);
    static void ChangeProgram(std::size_t program_index);

private:
    // Window management
    std::unique_ptr<EmuWindow_Android> m_window;
    ANativeWindow* m_native_window{};

    // Core emulation
    Core::System m_system;
    InputCommon::InputSubsystem m_input_subsystem;
    Common::DetachedTasks m_detached_tasks;
    Core::PerfStatsResults m_perf_stats{};
    int m_shaders_building{0};
    std::shared_ptr<FileSys::VfsFilesystem> m_vfs;
    Core::SystemResultStatus m_load_result{Core::SystemResultStatus::ErrorNotInitialized};
    std::atomic<bool> m_is_running = false;
    std::atomic<bool> m_is_paused = false;
    Common::Android::SoftwareKeyboard::AndroidKeyboard* m_software_keyboard{};
    std::unique_ptr<FileSys::ManualContentProvider> m_manual_provider;
    int m_applet_id{1};

    // GPU driver parameters
    std::shared_ptr<Common::DynamicLibrary> m_vulkan_library;

    // Synchronization
    std::condition_variable_any m_cv;
    mutable std::mutex m_mutex;

    // Pending save/load request -- written by any thread (typically the UI
    // thread via JNI), read and cleared by the emulation thread in
    // RunEmulation's wait loop. The synchronization is intentionally
    // minimal because m_mutex is already held by the emulation thread when
    // it reads, and the atomic stores happen-before the m_cv.notify_one().
    std::atomic<int> m_pending_state_slot{0};   // 1..NUM_STATES or 0 = none
    std::atomic<int> m_pending_state_kind{0};   // 1 = save, 2 = load
    std::atomic<bool> m_state_request_done{false};
    std::atomic<bool> m_state_request_ok{false};

    // Program index for next boot
    std::atomic<s32> m_next_program_index = -1;
};
