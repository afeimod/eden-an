// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Serialization helper for save states.
// Based on Dolphin's Common/ChunkFile.h PointerWrap concept.
//
// Usage pattern (mirrors Dolphin):
//   // 1st pass: measure
//   u8* p = nullptr;
//   PointerWrap pw(&p, 0, PointerWrap::Mode::Measure);
//   DoState(pw);
//   size_t size = pw.GetOffsetFromPreviousPosition(p);
//
//   // 2nd pass: actual write into pre-sized buffer
//   std::vector<u8> buf(size);
//   p = buf.data();
//   PointerWrap pw(&p, size, PointerWrap::Mode::Write);
//   DoState(pw);
//
// For read: std::vector<u8> buf = ... ; u8* p = buf.data();
//   PointerWrap pw(&p, buf.size(), PointerWrap::Mode::Read);
//   DoState(pw);

#pragma once

#include <cstddef>
#include <cstring>
#include <span>
#include <type_traits>
#include <vector>

#include "common/common_types.h"

namespace Common {

class PointerWrap {
public:
    enum class Mode {
        Read,
        Write,
        Measure,
    };

    PointerWrap(u8** ptr, std::size_t size, Mode mode)
        : m_ptr{ptr}, m_end{ptr ? *ptr + size : nullptr}, m_mode{mode}, m_offset{0} {}

    Mode GetMode() const {
        return m_mode;
    }

    bool IsReadMode() const {
        return m_mode == Mode::Read;
    }
    bool IsWriteMode() const {
        return m_mode == Mode::Write;
    }
    bool IsMeasureMode() const {
        return m_mode == Mode::Measure;
    }

    /// Set the wrapper to measure mode without consuming data. Used after error paths.
    void SetMeasureMode() {
        m_mode = Mode::Measure;
    }

    /// Reserve bytes; returns nullptr on measure.
    u8* ReserveBytes(std::size_t size) {
        if (IsMeasureMode()) {
            m_offset += size;
            return nullptr;
        }
        if (m_ptr == nullptr || *m_ptr + size > m_end) {
            return nullptr;
        }
        u8* ret = *m_ptr;
        *m_ptr += size;
        return ret;
    }

    void DoBytes(void* data, std::size_t size) {
        if (IsMeasureMode()) {
            m_offset += size;
            return;
        }
        if (IsReadMode()) {
            if (*m_ptr + size > m_end) {
                // Truncated; bail out by switching to measure (signals caller)
                SetMeasureMode();
                return;
            }
            std::memcpy(data, *m_ptr, size);
            *m_ptr += size;
        } else {
            if (*m_ptr + size > m_end) {
                SetMeasureMode();
                return;
            }
            std::memcpy(*m_ptr, data, size);
            *m_ptr += size;
        }
    }

    template <typename T>
    void Do(T& value) {
        static_assert(std::is_trivially_copyable_v<T>,
                      "PointerWrap::Do only handles trivially copyable types");
        DoBytes(&value, sizeof(T));
    }

    template <typename T>
    void Do(std::span<T> span) {
        // Persist length so we can resize on read.
        u64 count = static_cast<u64>(span.size());
        Do(count);
        if (IsMeasureMode()) {
            m_offset += count * sizeof(T);
            return;
        }
        if (IsReadMode()) {
            span = std::span<T>(span.data(), static_cast<std::size_t>(count));
        }
        if (span.size() > 0) {
            DoBytes(span.data(), span.size() * sizeof(T));
        }
    }

    template <typename T>
    void Do(std::vector<T>& vec) {
        u64 count = static_cast<u64>(vec.size());
        Do(count);
        if (IsMeasureMode()) {
            m_offset += count * sizeof(T);
            return;
        }
        if (IsReadMode()) {
            vec.resize(static_cast<std::size_t>(count));
        }
        if (!vec.empty()) {
            DoBytes(vec.data(), vec.size() * sizeof(T));
        }
    }

    void DoString(std::string& s) {
        u64 size = static_cast<u64>(s.size());
        Do(size);
        if (IsMeasureMode()) {
            m_offset += size;
            return;
        }
        if (IsReadMode()) {
            s.resize(static_cast<std::size_t>(size));
        }
        if (size > 0) {
            DoBytes(s.data(), static_cast<std::size_t>(size));
        }
    }

    /// Optional named marker; helpful for debugging state files. Empty in implementation.
    void DoMarker(std::string_view /*name*/) {}

    /// Offset consumed since constructor. Valid in any mode; in Measure it equals size to allocate.
    std::size_t GetOffsetFromPreviousPosition(const u8* prev) const {
        if (IsMeasureMode()) {
            return m_offset;
        }
        return static_cast<std::size_t>(*m_ptr - prev);
    }

private:
    u8** m_ptr{};
    u8* m_end{};
    Mode m_mode;
    std::size_t m_offset{};
};

} // namespace Common