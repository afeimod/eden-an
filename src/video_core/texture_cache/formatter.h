// SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

// SPDX-FileCopyrightText: Copyright 2020 yuzu Emulator Project
// SPDX-License-Identifier: GPL-2.0-or-later

#pragma once

#include <string>

#include <fmt/base.h>
#include <fmt/ranges.h>

#include "video_core/surface.h"
#include "video_core/texture_cache/types.h"

template <>
struct fmt::formatter<VideoCommon::Extent3D> {
    constexpr auto parse(fmt::format_parse_context& ctx) {
        return ctx.begin();
    }

    template <typename FormatContext>
    auto format(const VideoCommon::Extent3D& extent, FormatContext& ctx) const {
        return fmt::format_to(ctx.out(), "{{{}, {}, {}}}", extent.width, extent.height, extent.depth);
    }
};

namespace VideoCommon {

struct ImageBase;
struct ImageViewBase;
struct RenderTargets;

[[nodiscard]] std::string Name(const ImageBase& image);

[[nodiscard]] std::string Name(const ImageViewBase& image_view, GPUVAddr addr);

[[nodiscard]] std::string Name(const RenderTargets& render_targets);

} // namespace VideoCommon
