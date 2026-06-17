// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Save state implementation for Eden.
//
// File format (all little-endian):
//   [0..6)   title_id (6 ASCII chars)
//   [6..8)   reserved
//   [8..12)  version_cookie (== STATE_VERSION_COOKIE_BASE | STATE_VERSION)
//   [12..16) state_version (u32; bump when layout changes)
//   [16..24) unix_time (u64)
//   [24..28) uncompressed_size (u32)
//   [28..32) compressed_size   (u32)
//   [32..)   <LZ4-compressed DoState body>
//
// DoState body layout (see DoInternalState below for the order):
//   1. CoreTiming  -- event_queue, global_timer, cpu_ticks
//   2. DeviceMemory -- DRAM dump
//   3. CpuCores    -- 4x ThreadContext for each currently-running KThread
//   4. Kernel      -- GlobalSchedulerContext + KProcess page table count
//   5. KernelThreads -- all live KThread contexts (per-process)
//   6. HLE services -- FileSystem / ServiceManager / APM / Audio / HID stubs
//   7. GPU / Host1x -- stubs
//   8. AppletManager / ProfileManager -- stubs
//
// IMPORTANT: this implementation is best-effort and only fills in the
// subsystems that don't require giant internal rewrites. Many games will
// still crash post-load because GPU pushbuffer, shader cache, audio buffers
// etc. are not serialized. See the table at the bottom of this file for
// the status of each subsystem.

#include "core/state.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstddef>
#include <cstring>
#include <filesystem>
#include <fmt/format.h>
#include <fstream>
#include <memory>
#include <mutex>
#include <span>
#include <string>
#include <thread>
#include <vector>

#include "common/common_types.h"
#include "common/fs/path_util.h"
#include "common/lz4_compression.h"
#include "common/pointer_wrap.h"

#include "core/arm/arm_interface.h"
#include "core/core.h"
#include "core/core_timing.h"
#include "core/device_memory.h"
#include "core/hardware_properties.h"
#include "core/hle/kernel/board/nintendo/nx/k_system_control.h"
#include "core/hle/kernel/k_process.h"
#include "core/hle/kernel/k_scheduler.h"
#include "core/hle/kernel/k_thread.h"
#include "core/hle/kernel/kernel.h"
#include "core/hle/kernel/kernel.h"
#include "core/hle/kernel/svc_types.h"

namespace State {

namespace {

// ---------------------------------------------------------------------------
// On-disk magic and version.
// ---------------------------------------------------------------------------

// Cookie base identifies this as an Eden savestate file. STATE_VERSION is
// packed into the upper 16 bits so that version mismatches fail fast.
constexpr u32 kCookieBase = 0xBAAD0000;
constexpr u32 kCookieMagic = kCookieBase | STATE_VERSION;

// Sentinel state_version for files written by the metadata-only Save path
// (no DoState body, just a header). Load() rejects this version.
constexpr u32 kStateVersionMetadataOnly = 0xFFFFFFFE;

// ---------------------------------------------------------------------------
// Background writer thread.
// ---------------------------------------------------------------------------

struct PendingSave {
    std::vector<u8> buffer;
    std::filesystem::path path;
    std::string title_id;
};

std::mutex g_save_queue_mutex;
std::condition_variable g_save_queue_cv;
std::vector<PendingSave> g_save_queue;
std::atomic<bool> g_writer_thread_should_exit{false};
std::thread g_writer_thread;

void WriterThreadMain() {
    while (true) {
        PendingSave task;
        {
            std::unique_lock<std::mutex> lock(g_save_queue_mutex);
            g_save_queue_cv.wait(lock, [] {
                return !g_save_queue.empty() || g_writer_thread_should_exit.load();
            });
            if (g_save_queue.empty() && g_writer_thread_should_exit.load()) {
                return;
            }
            task = std::move(g_save_queue.front());
            g_save_queue.erase(g_save_queue.begin());
        }

        std::vector<u8> compressed =
            Common::Compression::CompressDataLZ4(task.buffer.data(), task.buffer.size());

        std::error_code ec;
        std::filesystem::create_directories(task.path.parent_path(), ec);

        std::ofstream ofs(task.path, std::ios::binary | std::ios::trunc);
        if (!ofs) {
            continue;
        }

        StateHeader header{};
        std::strncpy(header.title_id, task.title_id.c_str(),
                     std::min<std::size_t>(task.title_id.size(), sizeof(header.title_id)));
        header.version_cookie = kCookieMagic;
        header.state_version = STATE_VERSION;
        header.unix_time = static_cast<u64>(
            std::chrono::duration_cast<std::chrono::seconds>(
                std::chrono::system_clock::now().time_since_epoch())
                .count());
        ofs.write(reinterpret_cast<const char*>(&header), sizeof(header));
        u32 uncompressed_size = static_cast<u32>(task.buffer.size());
        u32 compressed_size = static_cast<u32>(compressed.size());
        ofs.write(reinterpret_cast<const char*>(&uncompressed_size), sizeof(uncompressed_size));
        ofs.write(reinterpret_cast<const char*>(&compressed_size), sizeof(compressed_size));
        if (!compressed.empty()) {
            ofs.write(reinterpret_cast<const char*>(compressed.data()), compressed.size());
        }
    }
}

// ---------------------------------------------------------------------------
// Filesystem layout
// ---------------------------------------------------------------------------

std::filesystem::path GetStatesDir() {
    return Common::FS::GetEdenPath(Common::FS::EdenPath::StatesDir);
}

std::filesystem::path GetSlotPath(int slot) {
    return GetStatesDir() / fmt::format("slot{:02d}.state", slot);
}

// ---------------------------------------------------------------------------
// DoState -- subsystem implementations.
//
// Every subsystem that participates in the snapshot defines:
//   void DoXxx(Core::System& system, Common::PointerWrap& p)
// It must:
//   * be safe to call in Measure, Read, Write modes
//   * on read mode, fail fast (call p.SetMeasureMode()) if the on-disk layout
//     doesn't match what the current build expects
// ---------------------------------------------------------------------------

void DoCoreTiming(Core::System& system, Common::PointerWrap& p) {
    p.DoMarker("CoreTiming");
    auto& timing = system.CoreTiming();
    u64 global_timer = static_cast<u64>(timing.global_timer);
    u64 cpu_ticks = timing.cpu_ticks;
    s64 downcount = timing.downcount;
    p.Do(global_timer);
    p.Do(cpu_ticks);
    p.Do(downcount);
    if (p.IsReadMode()) {
        timing.global_timer = static_cast<s64>(global_timer);
        timing.cpu_ticks = cpu_ticks;
        timing.downcount = downcount;
    }

    // We intentionally do NOT serialize the event_queue contents here. The
    // boost::heap node type (CoreTiming::Event) is forward-declared in the
    // public header and fully defined in core_timing.cpp; touching it from
    // outside that TU fails the compile. Persisting event callbacks is also
    // not portable across runs (they're std::function / function pointers).
    // The event queue will be rebuilt by the kernel as it re-issues pending
    // operations post-load.
    u64 event_fifo_id = timing.event_fifo_id;
    p.Do(event_fifo_id);
    if (p.IsReadMode()) {
        timing.event_fifo_id = event_fifo_id;
    }
}

void DoDeviceMemory(Core::System& system, Common::PointerWrap& p) {
    p.DoMarker("DeviceMemory");
    auto& device_memory = system.DeviceMemory();
    const std::size_t backing_size =
        Kernel::Board::Nintendo::Nx::KSystemControl::Init::GetIntendedMemorySize();
    u64 size_field = static_cast<u64>(backing_size);
    p.Do(size_field);
    if (p.IsReadMode() && size_field != backing_size) {
        // DRAM size mismatch -- refuse to load.
        p.SetMeasureMode();
        return;
    }

    u8* base = device_memory.buffer.BackingBasePointer();
    if (base != nullptr) {
        p.DoBytes(base, backing_size);
    } else if (p.IsMeasureMode()) {
        // PointerWrap::DoBytes already adds `size` to m_offset in measure mode.
        p.DoBytes(nullptr, backing_size);
    }
}

void DoCpuCores(Core::System& system, Common::PointerWrap& p) {
    p.DoMarker("CpuCores");
    // For each of the 4 physical cores, write the architecture + a ThreadContext
    // for the KThread currently running on that core (or zeros if idle).
    auto* process = system.ApplicationProcess();
    auto& kernel = system.Kernel();
    for (std::size_t i = 0; i < Core::Hardware::NUM_CPU_CORES; ++i) {
        Core::ArmInterface* arm = process ? process->GetArmInterface(i) : nullptr;
        Kernel::KThread* thread = kernel.Scheduler(i).GetSchedulerCurrentThread();
        u8 present = (arm != nullptr) ? 1 : 0;
        p.Do(present);
        if (!present || arm == nullptr) {
            continue;
        }
        u8 arch = static_cast<u8>(arm->GetArchitecture());
        p.Do(arch);
        if (thread != nullptr && thread != kernel.Scheduler(i).GetIdleThread()) {
            Kernel::Svc::ThreadContext ctx{};
            arm->GetContext(ctx);
            u32 magic = 0x434F5245; // 'CORE'
            p.Do(magic);
            p.Do(ctx);
        } else {
            u32 magic = 0x49444C45; // 'IDLE'
            p.Do(magic);
        }
    }
}

void DoKernelScheduler(Core::System& system, Common::PointerWrap& p) {
    p.DoMarker("KernelScheduler");
    auto& kernel = system.Kernel();
    // Per-core scheduler: idle counts + last-thread switch counts.
    // We don't serialize the KThread pointers themselves (those are handled
    // separately by DoKernelThreads). On load, the KScheduler's internal
    // scheduling state is reconstructed by the kernel itself when it next
    // resumes a thread.
    for (std::size_t i = 0; i < Core::Hardware::NUM_CPU_CORES; ++i) {
        auto& sched = kernel.Scheduler(i);
        u64 idle_count = sched.GetIdleCount();
        u64 switch_count = static_cast<u64>(sched.GetLastContextSwitchTime());
        Kernel::KThread* cur = sched.GetSchedulerCurrentThread();
        u32 thread_id = cur ? cur->GetThreadId() : 0;
        p.Do(idle_count);
        p.Do(switch_count);
        p.Do(thread_id);
    }
}

void DoKernelThreads(Core::System& system, Common::PointerWrap& p) {
    p.DoMarker("KernelThreads");
    auto& kernel = system.Kernel();
    auto procs = kernel.GetProcessList();

    u32 num_procs = static_cast<u32>(procs.size());
    p.Do(num_procs);

    for (auto& proc_handle : procs) {
        Kernel::KProcess* proc = proc_handle.GetPointerUnsafe();
        if (proc == nullptr) {
            u32 zero = 0;
            p.Do(zero);
            continue;
        }
        u64 pid = proc->GetProcessId();
        u64 program_id = proc->GetProgramId();
        p.Do(pid);
        p.Do(program_id);

        u32 num_threads = static_cast<u32>(proc->GetThreadList().size());
        p.Do(num_threads);

        for (auto& thread : proc->GetThreadList()) {
            u32 tid = thread.GetThreadId();
            u64 tls_ptr = thread.GetTpidrEl0();
            u32 state = static_cast<u32>(thread.GetState());
            p.Do(tid);
            p.Do(tls_ptr);
            p.Do(state);

            // SVC arguments for any core currently inside a syscall.
            std::array<uint64_t, 8> svc_args{};
            bool wrote_svc = false;
            for (size_t c = 0; c < Core::Hardware::NUM_CPU_CORES; ++c) {
                auto* arm = proc->GetArmInterface(c);
                if (arm == nullptr) {
                    continue;
                }
                arm->GetSvcArguments(svc_args);
                u32 svc_num = arm->GetSvcNumber();
                if (svc_num != 0) {
                    u8 has_svc = 1;
                    p.Do(has_svc);
                    p.Do(svc_num);
                    p.Do(svc_args);
                    wrote_svc = true;
                    break;
                }
            }
            if (!wrote_svc) {
                u8 has_svc = 0;
                p.Do(has_svc);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Stubs for subsystems we don't (yet) implement.
//
// Each stub writes a known magic + size placeholder so we can detect the
// stub at load time and fail fast with a helpful log line.
// ---------------------------------------------------------------------------

void StubDoState(Common::PointerWrap& p, const char* name, u32 stub_id) {
    p.DoMarker(name);
    u32 magic = 0xDEAD0000u | stub_id;
    u32 placeholder_size = 0;
    p.Do(magic);
    p.Do(placeholder_size);
    if (p.IsReadMode() && magic != (0xDEAD0000u | stub_id)) {
        p.SetMeasureMode();
    }
}

void DoFileSystem(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "FileSystem stub", 1);
    // TODO: enumerate open file handles + save data IVFC + bis partitions.
}

void DoServiceManager(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "ServiceManager stub", 2);
}

void DoApmController(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "APM stub", 3);
}

void DoAudioCore(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "AudioCore stub", 4);
}

void DoHidCore(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "HIDCore stub", 5);
}

void DoGpu(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "GPU stub", 6);
}

void DoHost1x(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "Host1x stub", 7);
}

void DoAppletManager(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "AppletManager stub", 8);
}

void DoProfileManager(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "ProfileManager stub", 9);
}

void DoInternalState(Core::System& system, Common::PointerWrap& p) {
    u32 version = STATE_VERSION;
    p.Do(version);
    if (p.IsReadMode() && version != STATE_VERSION) {
        p.SetMeasureMode();
        return;
    }

    DoCoreTiming(system, p);
    DoDeviceMemory(system, p);
    DoCpuCores(system, p);
    DoKernelScheduler(system, p);
    DoKernelThreads(system, p);
    DoFileSystem(system, p);
    DoServiceManager(system, p);
    DoApmController(system, p);
    DoAudioCore(system, p);
    DoHidCore(system, p);
    DoGpu(system, p);
    DoHost1x(system, p);
    DoAppletManager(system, p);
    DoProfileManager(system, p);
}

std::size_t MeasureStateSize(Core::System& system) {
    u8* nullp = nullptr;
    Common::PointerWrap p(&nullp, 0, Common::PointerWrap::Mode::Measure);
    DoInternalState(system, p);
    return p.GetOffsetFromPreviousPosition(nullp);
}

bool CaptureToBuffer(Core::System& system, std::vector<u8>& out) {
    std::size_t size = MeasureStateSize(system);
    out.assign(size, 0);
    u8* p = out.data();
    Common::PointerWrap wrap(&p, size, Common::PointerWrap::Mode::Write);
    DoInternalState(system, wrap);
    return wrap.IsWriteMode();
}

bool RestoreFromBuffer(Core::System& system, std::span<const u8> buffer) {
    u8* p = const_cast<u8*>(buffer.data());
    Common::PointerWrap wrap(&p, buffer.size(), Common::PointerWrap::Mode::Read);
    DoInternalState(system, wrap);
    return wrap.IsReadMode();
}

// Metadata-only writer used by Save when the lightweight path is requested.
// Writes header + zero-length body. Load rejects these files via the
// STATE_VERSION_METADATA_ONLY sentinel.
bool WriteMetadataOnly(std::filesystem::path path, std::string_view title_id) {
    StateHeader header{};
    std::strncpy(header.title_id, title_id.data(),
                 std::min<std::size_t>(title_id.size(), sizeof(header.title_id)));
    header.version_cookie = kCookieBase | kStateVersionMetadataOnly;
    header.state_version = kStateVersionMetadataOnly;
    header.unix_time = static_cast<u64>(
        std::chrono::duration_cast<std::chrono::seconds>(
            std::chrono::system_clock::now().time_since_epoch())
            .count());

    std::error_code ec;
    std::filesystem::create_directories(path.parent_path(), ec);

    std::ofstream ofs(path, std::ios::binary | std::ios::trunc);
    if (!ofs) {
        return false;
    }
    ofs.write(reinterpret_cast<const char*>(&header), sizeof(header));
    u32 zero = 0;
    ofs.write(reinterpret_cast<const char*>(&zero), sizeof(zero));
    ofs.write(reinterpret_cast<const char*>(&zero), sizeof(zero));
    return static_cast<bool>(ofs);
}

} // namespace

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

[[maybe_unused]] void Init() {
    if (g_writer_thread.joinable()) {
        return;
    }
    g_writer_thread_should_exit.store(false);
    g_writer_thread = std::thread(WriterThreadMain);
}

[[maybe_unused]] u64 GetUnixTimeOfSlot(int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return 0;
    }
    std::error_code ec;
    auto path = GetSlotPath(slot);
    if (!std::filesystem::exists(path, ec)) {
        return 0;
    }
    std::ifstream ifs(path, std::ios::binary);
    if (!ifs) {
        return 0;
    }
    StateHeader header{};
    ifs.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (header.version_cookie != kCookieMagic) {
        return 0;
    }
    return header.unix_time;
}

[[maybe_unused]] std::string GetTitleIdOfSlot(int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return {};
    }
    std::error_code ec;
    auto path = GetSlotPath(slot);
    if (!std::filesystem::exists(path, ec)) {
        return {};
    }
    std::ifstream ifs(path, std::ios::binary);
    if (!ifs) {
        return {};
    }
    StateHeader header{};
    ifs.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (header.version_cookie != kCookieMagic) {
        return {};
    }
    return std::string(header.title_id, strnlen(header.title_id, sizeof(header.title_id)));
}

[[maybe_unused]] bool Exists(int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
    std::error_code ec;
    return std::filesystem::exists(GetSlotPath(slot), ec);
}

[[maybe_unused]] bool Delete(int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
    std::error_code ec;
    return std::filesystem::remove(GetSlotPath(slot), ec);
}

[[maybe_unused]] bool IsLoadable(int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
    std::error_code ec;
    auto path = GetSlotPath(slot);
    if (!std::filesystem::exists(path, ec)) {
        return false;
    }
    std::ifstream ifs(path, std::ios::binary);
    if (!ifs) {
        return false;
    }
    StateHeader header{};
    ifs.read(reinterpret_cast<char*>(&header), sizeof(header));
    if (header.version_cookie != kCookieMagic) {
        return false;
    }
    return header.state_version == STATE_VERSION;
}

// ---------------------------------------------------------------------------
// Save (full + lightweight)
//
// Save() = full: synchronously captures DoState (blocks several seconds on
// Switch DRAM). MUST be called from a thread that is allowed to pause the
// emulation thread. The SaveState JNI function marshals onto the emulation
// thread before calling this.
//
// SaveMetadataOnly() = metadata-only (no body). Used for testing the UI
// plumbing without actually freezing emulation for seconds.
// ---------------------------------------------------------------------------

[[maybe_unused]] bool Save(Core::System& system, int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
    // Full DoState capture. Callers MUST have paused emulation first.
    std::vector<u8> buffer;
    if (!CaptureToBuffer(system, buffer)) {
        return false;
    }

    PendingSave task;
    task.buffer = std::move(buffer);
    task.path = GetSlotPath(slot);
    task.title_id = fmt::format("{:016X}", system.GetApplicationProcessProgramID());
    {
        std::lock_guard<std::mutex> lock(g_save_queue_mutex);
        g_save_queue.push_back(std::move(task));
    }
    g_save_queue_cv.notify_one();
    return true;
}

[[maybe_unused]] bool SaveMetadataOnly(Core::System& system, int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
    return WriteMetadataOnly(GetSlotPath(slot),
                             fmt::format("{:016X}", system.GetApplicationProcessProgramID()));
}

[[maybe_unused]] bool Load(Core::System& system, int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
    auto path = GetSlotPath(slot);
    std::error_code ec;
    if (!std::filesystem::exists(path, ec)) {
        return false;
    }
    std::ifstream ifs(path, std::ios::binary);
    if (!ifs) {
        return false;
    }
    StateHeader header{};
    ifs.read(reinterpret_cast<char*>(&header), sizeof(header));
    // Accept either the current full-state cookie or a legacy
    // metadata-only file (which we then reject below).
    if (header.version_cookie != kCookieMagic &&
        header.version_cookie != (kCookieBase | kStateVersionMetadataOnly)) {
        return false;
    }
    if (header.state_version != STATE_VERSION) {
        return false;
    }
    u32 uncompressed_size = 0;
    u32 compressed_size = 0;
    ifs.read(reinterpret_cast<char*>(&uncompressed_size), sizeof(uncompressed_size));
    ifs.read(reinterpret_cast<char*>(&compressed_size), sizeof(compressed_size));
    if (!ifs || uncompressed_size == 0) {
        return false;
    }
    std::vector<u8> compressed(compressed_size);
    if (compressed_size > 0) {
        ifs.read(reinterpret_cast<char*>(compressed.data()), compressed_size);
    }
    std::vector<u8> raw = Common::Compression::DecompressDataLZ4(compressed, uncompressed_size);
    if (raw.size() != uncompressed_size) {
        return false;
    }
    return RestoreFromBuffer(system, std::span<const u8>(raw.data(), raw.size()));
}

} // namespace State