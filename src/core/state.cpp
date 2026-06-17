// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "core/state.h"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstring>
#include <cstddef>
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

namespace State {

namespace {

// Magic cookie constant. Together with STATE_VERSION it identifies the file
// format. Bumping the cookie value will make older builds refuse to load
// newer state files; bumping only STATE_VERSION would do that too. We use
// the same constant for both checks for simplicity.
constexpr u32 kCookieBase = 0xBAAD0001;

// Pending save task. State is captured synchronously into RAM, then handed off
// to a background thread for compression + disk write.
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
        header.version_cookie = kCookieBase;
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
// DoState pipeline.
//
// Order matters:
//   1. CoreTiming first  -- captures scheduler state before CPU cores look at it.
//   2. DeviceMemory     -- raw DRAM bytes (huge -- ~4 GB on Switch).
//   3. Memory mapping   -- page table + memory permissions.
//   4. CPU cores        -- registers + exclusive monitor, one per core.
//   5. Kernel scheduler -- thread contexts, KProcess page tables.
//   6. HLE services     -- FS, SM, APM, etc. (currently stubs).
//   7. AudioCore, HIDCore, GPU -- may have in-flight DMA / buffers.
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
}

void DoDeviceMemory(Core::System& system, Common::PointerWrap& p) {
    p.DoMarker("DeviceMemory");
    // Switch DRAM is mapped at DramMemoryMap::Base (0x80000000). The backing buffer
    // size is established at boot; we just dump the whole region.
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

    // In Measure mode DoBytes() just bumps m_offset, so a null pointer is fine.
    // In Read/Write modes we read/write directly from the device-memory backing buffer.
    u8* base = device_memory.buffer.BackingBasePointer();
    p.DoBytes(base, backing_size);
}

void DoMemoryMappings(Core::System& /*system*/, Common::PointerWrap& p) {
    p.DoMarker("MemoryMappings");
    // Persist the count of current process memory regions so we can sanity-check
    // on load. Currently we do NOT reconstruct page tables -- the load path just
    // resumes with the current page table. This is one of the known-stub areas.
    u64 region_count = 0;
    p.Do(region_count);
}

void DoCpuCores(Core::System& system, Common::PointerWrap& p) {
    p.DoMarker("CpuCores");
    // Switch has 4 physical cores. Dump each one's ThreadContext via ArmInterface.
    auto* process = system.ApplicationProcess();
    for (std::size_t i = 0; i < Core::Hardware::NUM_CPU_CORES; ++i) {
        Core::ArmInterface* arm =
            process != nullptr ? process->GetArmInterface(i) : nullptr;
        if (arm == nullptr) {
            u8 present = 0;
            p.Do(present);
            continue;
        }
        u8 present = 1;
        p.Do(present);
        if (!present) {
            continue;
        }

        // We serialize a stub record: just the architecture (1 byte) and a sentinel.
        // Real implementation would walk every thread context and dump registers.
        // For this first cut we record the architecture so the loader knows if it
        // can sanity-check, and a marker.
        u8 arch = static_cast<u8>(arm->GetArchitecture());
        p.Do(arch);
        // NOTE: full register context is not yet serialized. Loading a savestate
        // created with this build will resume with the current thread context.
        p.DoMarker("ArmInterface stub");
    }
}

// ---------------------------------------------------------------------------
// Stubs for subsystems whose state we do not (yet) serialize.
//
// Each stub consumes a single u32 magic so the on-disk layout is stable, and bumps
// the offset by a known amount in measure mode. A future version may either
// implement these or bump STATE_VERSION.
// ---------------------------------------------------------------------------

void StubDoState(Common::PointerWrap& p, const char* name) {
    p.DoMarker(name);
    u32 magic = 0xDEADBEEF;
    p.Do(magic);
}

void DoKernel(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "Kernel stub");
    // TODO: serialize KProcess page tables + thread contexts.
}

void DoFileSystem(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "FileSystem stub");
    // TODO: serialize FSSRV open file handles, save data IVFC hashes.
}

void DoServiceManager(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "ServiceManager stub");
}

void DoApmController(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "APM stub");
}

void DoAudioCore(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "AudioCore stub");
    // TODO: flush + serialize audio output buffer state.
}

void DoHidCore(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "HIDCore stub");
    // TODO: serialize HID device states, pending input events.
}

void DoGpu(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "GPU stub");
    // TODO: serialize Tegra GPU pushbuffers, register state, framebuffer contents.
    // Note: large; typically several megabytes. May want to skip framebuffer and
    // force a full re-flush from VRAM on first frame post-load.
}

void DoHost1x(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "Host1x stub");
}

void DoAppletManager(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "AppletManager stub");
}

void DoProfileManager(Core::System& /*system*/, Common::PointerWrap& p) {
    StubDoState(p, "ProfileManager stub");
}

void DoInternalState(Core::System& system, Common::PointerWrap& p) {
    // Top-level DoState: order is documented above.
    u32 version = STATE_VERSION;
    p.Do(version);
    if (p.IsReadMode() && version != STATE_VERSION) {
        // Incompatible version -- refuse to load by switching to measure mode.
        p.SetMeasureMode();
        return;
    }

    DoCoreTiming(system, p);
    DoDeviceMemory(system, p);
    DoMemoryMappings(system, p);
    DoCpuCores(system, p);
    DoKernel(system, p);
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

// ---------------------------------------------------------------------------
// Measure (no I/O).
// ---------------------------------------------------------------------------

std::size_t MeasureStateSize(Core::System& system) {
    u8* nullp = nullptr;
    Common::PointerWrap p(&nullp, 0, Common::PointerWrap::Mode::Measure);
    DoInternalState(system, p);
    return p.GetOffsetFromPreviousPosition(nullp);
}

// ---------------------------------------------------------------------------
// Capture state into a buffer. Returns false on failure.
// ---------------------------------------------------------------------------

bool CaptureToBuffer(Core::System& system, std::vector<u8>& out) {
    std::size_t size = MeasureStateSize(system);
    out.assign(size, 0);
    u8* p = out.data();
    Common::PointerWrap wrap(&p, size, Common::PointerWrap::Mode::Write);
    DoInternalState(system, wrap);
    if (!wrap.IsWriteMode()) {
        // Measure pass should have been accurate; if we ran out of buffer, bail.
        return false;
    }
    return true;
}

// ---------------------------------------------------------------------------
// Restore state from a buffer. Returns true on success.
// ---------------------------------------------------------------------------

bool RestoreFromBuffer(Core::System& system, std::span<const u8> buffer) {
    u8* p = const_cast<u8*>(buffer.data());
    Common::PointerWrap wrap(&p, buffer.size(), Common::PointerWrap::Mode::Read);
    DoInternalState(system, wrap);
    return wrap.IsReadMode();
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

void Init() {
    if (g_writer_thread.joinable()) {
        return;
    }
    g_writer_thread_should_exit.store(false);
    g_writer_thread = std::thread(WriterThreadMain);
}

u64 GetUnixTimeOfSlot(int slot) {
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
    if (header.version_cookie != kCookieBase) {
        return 0;
    }
    return header.unix_time;
}

std::string GetTitleIdOfSlot(int slot) {
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
    if (header.version_cookie != kCookieBase) {
        return {};
    }
    return std::string(header.title_id, strnlen(header.title_id, sizeof(header.title_id)));
}

bool Exists(int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
    std::error_code ec;
    return std::filesystem::exists(GetSlotPath(slot), ec);
}

bool Delete(int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
    std::error_code ec;
    return std::filesystem::remove(GetSlotPath(slot), ec);
}

bool Save(Core::System& system, int slot) {
    if (slot < 1 || slot > static_cast<int>(NUM_STATES)) {
        return false;
    }
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

bool Load(Core::System& system, int slot) {
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
    if (header.version_cookie != kCookieBase) {
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

} // namespace
} // namespace State