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

/// Save the current emulation state to the given 1-based slot. Returns true on success.
/// Save is queued onto the CPU thread; this call returns once the snapshot has been
/// captured into RAM. Compression + disk write happens off-thread.
bool Save(Core::System& system, int slot);

/// Load state from the given 1-based slot. Returns true if a valid state was loaded.
/// This call blocks until the load completes; it MUST run on the CPU thread (or be
/// marshalled onto it).
bool Load(Core::System& system, int slot);

/// Returns true if the given 1-based slot exists on disk and parses cleanly.
bool Exists(int slot);

/// Delete the given 1-based slot file. Returns true if the file was removed (or didn't exist).
bool Delete(int slot);

} // namespace State