// SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
// SPDX-License-Identifier: GPL-3.0-or-later

#include "common/common_types.h"
#include "common/div_ceil.h"
#include "common/settings.h"

//#include "video_core/sgsr.h"
#include "video_core/host_shaders/sgsr1_shader_mobile_frag_spv.h"
#include "video_core/host_shaders/sgsr1_shader_mobile_edge_direction_frag_spv.h"
#include "video_core/host_shaders/sgsr1_shader_vert_spv.h"
#include "video_core/renderer_vulkan/present/sgsr.h"
#include "video_core/renderer_vulkan/present/util.h"
#include "video_core/renderer_vulkan/vk_scheduler.h"
#include "video_core/renderer_vulkan/vk_shader_util.h"
#include "video_core/vulkan_common/vulkan_device.h"

namespace Vulkan {

using PushConstants = std::array<u32, 4 * 4>;

SGSR::SGSR(const Device& device, MemoryAllocator& memory_allocator, size_t image_count, VkExtent2D extent)
    : m_device{device}, m_memory_allocator{memory_allocator}
    , m_image_count{image_count}, m_extent{extent}
{
    m_dynamic_images.resize(m_image_count);
    for (auto& images : m_dynamic_images) {
        images.images[0] = CreateWrappedImage(m_memory_allocator, m_extent, VK_FORMAT_R16G16B16A16_SFLOAT);
        images.images[1] = CreateWrappedImage(m_memory_allocator, m_extent, VK_FORMAT_R16G16B16A16_SFLOAT);
        images.image_views[0] = CreateWrappedImageView(m_device, images.images[0], VK_FORMAT_R16G16B16A16_SFLOAT);
        images.image_views[1] = CreateWrappedImageView(m_device, images.images[1], VK_FORMAT_R16G16B16A16_SFLOAT);
    }

    m_renderpass = CreateWrappedRenderPass(m_device, VK_FORMAT_R16G16B16A16_SFLOAT);
    for (auto& images : m_dynamic_images) {
        images.framebuffers[0] = CreateWrappedFramebuffer(m_device, m_renderpass, images.image_views[0], m_extent);
        images.framebuffers[1] = CreateWrappedFramebuffer(m_device, m_renderpass, images.image_views[1], m_extent);
    }

    m_sampler = CreateBilinearSampler(m_device);
    m_vert_shader = BuildShader(m_device, SGSR1_SHADER_VERT_SPV);
    m_stage_shader[0] = BuildShader(m_device, SGSR1_SHADER_MOBILE_FRAG_SPV);
    m_stage_shader[1] = BuildShader(m_device, SGSR1_SHADER_MOBILE_EDGE_DIRECTION_FRAG_SPV);
    // 2 descriptors, 2 descriptor sets per invocation
    m_descriptor_pool = CreateWrappedDescriptorPool(m_device, 2 * m_image_count, 2 * m_image_count);
    m_descriptor_set_layout = CreateWrappedDescriptorSetLayout(m_device, {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER});

    std::vector<VkDescriptorSetLayout> layouts(SGSR_STAGE_COUNT, *m_descriptor_set_layout);
    for (auto& images : m_dynamic_images)
        images.descriptor_sets = CreateWrappedDescriptorSets(m_descriptor_pool, layouts);

    const VkPushConstantRange range{
        .stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT,
        .offset = 0,
        .size = sizeof(PushConstants),
    };
    VkPipelineLayoutCreateInfo ci{
        .sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO,
        .pNext = nullptr,
        .flags = 0,
        .setLayoutCount = 1,
        .pSetLayouts = m_descriptor_set_layout.address(),
        .pushConstantRangeCount = 1,
        .pPushConstantRanges = &range,
    };
    m_pipeline_layout = m_device.GetLogical().CreatePipelineLayout(ci);

    m_stage_pipeline[0] = CreateWrappedPipeline(m_device, m_renderpass, m_pipeline_layout, std::tie(m_vert_shader, m_stage_shader[0]));
    m_stage_pipeline[1] = CreateWrappedPipeline(m_device, m_renderpass, m_pipeline_layout, std::tie(m_vert_shader, m_stage_shader[1]));
}

void SGSR::UpdateDescriptorSets(VkImageView image_view, size_t image_index) {
    Images& images = m_dynamic_images[image_index];
    std::vector<VkDescriptorImageInfo> image_infos;
    std::vector<VkWriteDescriptorSet> updates;
    image_infos.reserve(2);
    updates.push_back(CreateWriteDescriptorSet(image_infos, *m_sampler, image_view, images.descriptor_sets[0], 0));
    updates.push_back(CreateWriteDescriptorSet(image_infos, *m_sampler, *images.image_views[0], images.descriptor_sets[1], 0));
    m_device.GetLogical().UpdateDescriptorSets(updates, {});
}

void SGSR::UploadImages(Scheduler& scheduler) {
    if (!m_images_ready) {
        scheduler.Record([&](vk::CommandBuffer cmdbuf) {
            for (auto& image : m_dynamic_images) {
                ClearColorImage(cmdbuf, *image.images[0]);
                ClearColorImage(cmdbuf, *image.images[1]);
            }
        });
        scheduler.Finish();
        m_images_ready = true;
    }
}

VkImageView SGSR::Draw(Scheduler& scheduler, size_t image_index, VkImage source_image, VkImageView source_image_view, VkExtent2D input_image_extent, const Common::Rectangle<f32>& crop_rect) {
    Images& images = m_dynamic_images[image_index];
    auto const stage0_image = *images.images[0];
    auto const stage1_image = *images.images[1];
    auto const stage0_descriptor_set = images.descriptor_sets[0];
    auto const stage1_descriptor_set = images.descriptor_sets[1];
    auto const stage0_framebuffer = *images.framebuffers[0];
    auto const stage1_framebuffer = *images.framebuffers[1];
    auto const stage0_pipeline = *m_stage_pipeline[0];
    auto const stage1_pipeline = *m_stage_pipeline[1];

    VkPipelineLayout pipeline_layout = *m_pipeline_layout;
    VkRenderPass renderpass = *m_renderpass;
    VkExtent2D extent = m_extent;

    const f32 input_image_width = f32(input_image_extent.width);
    const f32 input_image_height = f32(input_image_extent.height);
    const f32 viewport_x = crop_rect.left * input_image_width;
    const f32 viewport_y = crop_rect.top * input_image_height;
    const f32 viewport_width = (crop_rect.right - crop_rect.left) * input_image_width;
    const f32 viewport_height = (crop_rect.bottom - crop_rect.top) * input_image_height;

    // highp vec4
    PushConstants viewport_con{};
    *reinterpret_cast<f32*>(viewport_con.data() + 0) = viewport_x;
    *reinterpret_cast<f32*>(viewport_con.data() + 1) = viewport_y;
    *reinterpret_cast<f32*>(viewport_con.data() + 2) = viewport_width;
    *reinterpret_cast<f32*>(viewport_con.data() + 3) = viewport_height;

    UploadImages(scheduler);
    UpdateDescriptorSets(source_image_view, image_index);

    scheduler.RequestOutsideRenderPassOperationContext();
    scheduler.Record([=](vk::CommandBuffer cmdbuf) {
        TransitionImageLayout(cmdbuf, source_image, VK_IMAGE_LAYOUT_GENERAL);
        TransitionImageLayout(cmdbuf, stage0_image, VK_IMAGE_LAYOUT_GENERAL);
        BeginRenderPass(cmdbuf, renderpass, stage0_framebuffer, extent);
        cmdbuf.BindPipeline(VK_PIPELINE_BIND_POINT_GRAPHICS, stage0_pipeline);
        cmdbuf.BindDescriptorSets(VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_layout, 0, stage0_descriptor_set, {});
        cmdbuf.PushConstants(pipeline_layout, VK_SHADER_STAGE_FRAGMENT_BIT, viewport_con);
        cmdbuf.Draw(3, 1, 0, 0);
        cmdbuf.EndRenderPass();
        TransitionImageLayout(cmdbuf, stage0_image, VK_IMAGE_LAYOUT_GENERAL);
        TransitionImageLayout(cmdbuf, stage1_image, VK_IMAGE_LAYOUT_GENERAL);
        BeginRenderPass(cmdbuf, renderpass, stage1_framebuffer, extent);
        cmdbuf.BindPipeline(VK_PIPELINE_BIND_POINT_GRAPHICS, stage1_pipeline);
        cmdbuf.BindDescriptorSets(VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_layout, 0, stage1_descriptor_set, {});
        cmdbuf.PushConstants(pipeline_layout, VK_SHADER_STAGE_FRAGMENT_BIT, viewport_con);
        cmdbuf.Draw(3, 1, 0, 0);
        cmdbuf.EndRenderPass();
        TransitionImageLayout(cmdbuf, stage1_image, VK_IMAGE_LAYOUT_GENERAL);
    });

    return *images.image_views[1];
}

} // namespace Vulkan
