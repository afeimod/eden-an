// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Emulator-level save state support for Eden (Nintendo Switch emulator).
//
// Snapshot strategy:
//   - Capture everything we can reconstruct: CPU register contexts (per-core),
//     CoreTiming state, raw DRAM, KScheduler per-core counters, and live
//     KThread ThreadContexts.
//   - GPU, HLE service state, audio buffers, HID inputs are NOT captured --
//     stubs only. Most games will show visible glitches or crash within a
//     few seconds of load. See "Status table" at the bottom.
//
// Files are written to <user>/states/slotNN.state and compressed with LZ4.

#pragma once

#include <cstddef>
#include <string>

#include "common/common_types.h"

namespace Core {
class System;
}

namespace State {

inline constexpr u32 NUM_STATES = 3;

/// Bumped whenever the DoState body layout changes. Old savestates become
/// unloadable; bump STATE_VERSION_COOKIE_BASE's complement when changing this.
inline constexpr u32 STATE_VERSION = 2;

/// On-disk header size (matches StateHeader struct below).
inline constexpr std::size_t STATE_HEADER_SIZE = 24;

#pragma pack(push, 1)
struct StateHeader {
    char title_id[6]{};
    u8 reserved[2]{};
    u32 version_cookie{};  // (0xBAAD0000 | STATE_VERSION) | metadata sentinel
    u32 state_version{};   // == STATE_VERSION on a real save; 0xFFFFFFFE on marker.
    u64 unix_time{};       // Seconds since Unix epoch.
};
#pragma pack(pop)
static_assert(sizeof(StateHeader) == STATE_HEADER_SIZE, "StateHeader size mismatch");

void Init();
u64 GetUnixTimeOfSlot(int slot);
std::string GetTitleIdOfSlot(int slot);
bool Exists(int slot);
bool Delete(int slot);
bool IsLoadable(int slot);

/// Full save -- synchronously captures DoState then queues compression +
/// disk write. Blocks for several seconds on Switch DRAM. MUST be called
/// from a thread that has paused emulation (the JNI marshal layer does
/// this for you).
bool Save(Core::System& system, int slot);

/// Metadata-only save -- writes a header + timestamp but no DoState body.
/// Useful for testing the UI/persistence plumbing without freezing the
/// emulation thread. The resulting slot cannot be loaded.
bool SaveMetadataOnly(Core::System& system, int slot);

/// Load state from the given 1-based slot. Blocks until complete; must be
/// called from the emulation thread (or with emulation paused).
bool Load(Core::System& system, int slot);

// ---------------------------------------------------------------------------
// Subsystem DoState status
// ---------------------------------------------------------------------------
// ✅ implemented (best-effort, may still be incomplete)
// ⚠️  stub (header only, won't survive a load roundtrip cleanly)
// ❌  missing entirely
//
// ✅ CoreTiming          -- event_queue size + fifo_id; timer counters
// ✅ DeviceMemory        -- raw DRAM dump
// ✅ CpuCores            -- per-core ThreadContext for the running thread
// ✅ KernelScheduler     -- per-core idle/switch counts
// ✅ KernelThreads       -- per-process KThread contexts + SVC arguments
// ⚠️  FileSystem         -- stub
// ⚠️  ServiceManager     -- stub
// ⚠️  APM (power)        -- stub
// ⚠️  AudioCore          -- stub
// ⚠️  HIDCore            -- stub
// ⚠️  GPU (Tegra)        -- stub (pushbuffer / shader cache / framebuffer NOT saved)
// ⚠️  Host1x             -- stub
// ⚠️  AppletManager      -- stub
// ⚠️  ProfileManager     -- stub

} // namespace State