// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Emulator-level save state support for Eden (Nintendo Switch emulator).
//
// Each savestate is a snapshot of every major subsystem (memory, CPU cores,
// core timing, GPU command buffer, HLE services, audio, HID, kernel scheduler).
//
// Files are written to <user>/states/<title-id>.s<N> and compressed with LZ4.
// Header format (24 bytes, all little-endian, trivially copyable):
//   [0..6)   title_id (6 chars, padded with 0)
//   [6..8)   reserved
//   [8..12)  version_cookie (== 0xBAAD0001 | build_id)
//   [12..16) state_version (u32; bump when serialization layout changes)
//   [16..24) unix_time (u64, seconds since epoch)
// followed by:
//   u32 uncompressed_size (LE)
//   u32 compressed_size   (LE)
//   <compressed bytes; raw LZ4 frame>
//
// CAVEATS / known-stub subsystems are documented inline in state.cpp.

#pragma once

#include <cstddef>
#include <functional>
#include <string>

#include "common/common_types.h"

namespace Core {
class System;
}

namespace State {

/// Number of savestate slots exposed to the user. The frontend shows slot 1..NUM_STATES.
inline constexpr u32 NUM_STATES = 3;

/// Bumped whenever the serialization layout of any DoState() changes. Old savestates
/// become unloadable; users must delete them.
inline constexpr u32 STATE_VERSION = 1;

/// Sentinel value written into `state_version` by the metadata-only Save()
/// path. Such files hold a valid header + timestamp but no actual emulation
/// state (body is zero bytes). Load() rejects this value so the frontend can
/// present a clear "this slot is only a marker, not a recoverable state"
/// message instead of an opaque failure.
inline constexpr u32 STATE_VERSION_METADATA_ONLY = 0xFFFFFFFE;

/// On-disk header size (matches StateHeader struct below).
inline constexpr std::size_t STATE_HEADER_SIZE = 24;

#pragma pack(push, 1)
struct StateHeader {
    char title_id[6]{};        ///< Lower-hex of the running program id, zero padded.
    u8 reserved[2]{};
    u32 version_cookie{};      ///< Sanity cookie; abort load if mismatched.
    u32 state_version{};      ///< Must equal STATE_VERSION at load time.
    u64 unix_time{};          ///< Seconds since Unix epoch.
};
#pragma pack(pop)
static_assert(sizeof(StateHeader) == STATE_HEADER_SIZE, "StateHeader size mismatch");

/// Initialize the state module. Must be called once at boot, before any Save/Load.
void Init();

/// Returns the time (unix seconds) at which the slot was written, or 0 if empty/invalid.
u64 GetUnixTimeOfSlot(int slot);

/// Returns the title-id stored in the slot, or empty string if empty/invalid.
std::string GetTitleIdOfSlot(int slot);

/// Returns true if the slot exists AND contains a recoverable full state
/// (i.e. not a metadata-only save). The frontend uses this to decide whether
/// the [Load] button should be enabled.
bool IsLoadable(int slot);

/// Save the current emulation state to the given 1-based slot. Returns true on success.
///
/// The default Save() writes a metadata-only header (title-id + timestamp) so it
/// returns quickly. This is safe to call from the Android UI thread; full DRAM
/// capture would block for several seconds and trigger an ANR.
///
/// To save the complete state (4 GiB DRAM dump + subsystem DoState), use SaveFull
/// instead -- this MUST be called from the CPU thread while emulation is paused.
bool Save(Core::System& system, int slot);

/// Full save: captures CoreTiming + DRAM + all subsystem DoState into a buffer,
/// queues compression + disk write on the writer thread. Blocks while capturing
/// (can take seconds on Switch DRAM). Caller is responsible for ensuring the
/// emulation thread isn't racing the capture.
bool SaveFull(Core::System& system, int slot);

/// Load state from the given 1-based slot. Returns true if a valid state was loaded.
/// This call blocks until the load completes; it MUST run on the CPU thread (or be
/// marshalled onto it).
bool Load(Core::System& system, int slot);

/// Returns true if the given 1-based slot exists on disk and parses cleanly.
bool Exists(int slot);

/// Delete the given 1-based slot file. Returns true if the file was removed (or didn't exist).
bool Delete(int slot);

} // namespace State