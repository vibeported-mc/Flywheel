package dev.engine_room.flywheel.backend.compute.vk;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfoKHR;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2KHR;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanGpuSampler;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuBuffer;

import dev.engine_room.flywheel.backend.compute.BarrierScope;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;

/**
 * Compute work recorded into a command buffer of our own.
 *
 * <p>Spliced into the frame with {@code allocateAndBeginTransientCommandBuffer} and {@code execute},
 * both of which Blaze3D makes public, so the dispatch lands in the right order relative to
 * everything else without a mixin. It has to be recorded <b>outside</b> a render pass, which is
 * where the render-stage events fire anyway, and which Vulkan requires regardless.
 *
 * <p>Bindings are pushed rather than allocated. {@code VK_KHR_push_descriptor} is a required device
 * extension for Blaze3D's Vulkan backend and there is no descriptor pool anywhere in it, so this
 * needs nothing allocated per frame and nothing recycled.
 */
public final class VkComputePass implements ComputePass {
	private final VulkanCommandEncoder encoder;
	private final VkCommandBuffer commandBuffer;
	private final String label;

	private @Nullable VkComputePipeline pipeline;
	private final long[] boundBuffers = new long[VkComputePipeline.MAX_BINDINGS];
	private final long[] boundOffsets = new long[VkComputePipeline.MAX_BINDINGS];
	private final long[] boundRanges = new long[VkComputePipeline.MAX_BINDINGS];
	private int boundCount;

	private final long[] boundImageViews = new long[VkComputePipeline.MAX_BINDINGS];
	private final long[] boundSamplers = new long[VkComputePipeline.MAX_BINDINGS];

	private boolean closed;

	VkComputePass(VulkanCommandEncoder encoder, String label) {
		this.encoder = encoder;
		this.label = label;
		this.commandBuffer = encoder.allocateAndBeginTransientCommandBuffer();
	}

	@Override
	public void setPipeline(ComputePipeline pipeline) {
		checkOpen();
		if (!(pipeline instanceof VkComputePipeline vk)) {
			throw new IllegalArgumentException(
					"pipeline " + pipeline.label() + " was not built by the Vulkan backend");
		}
		this.pipeline = vk;
		VK10.vkCmdBindPipeline(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_COMPUTE, vk.pipeline());
	}

	@Override
	public void bindStorageBuffer(int binding, GpuBufferSlice slice) {
		checkOpen();
		if (!(slice.buffer() instanceof VulkanGpuBuffer buffer)) {
			throw new IllegalArgumentException("buffer was not allocated by the Vulkan backend");
		}
		if (binding < 0 || binding >= VkComputePipeline.MAX_BINDINGS) {
			throw new IllegalArgumentException("binding " + binding + " is out of range");
		}

		boundBuffers[binding] = buffer.vkBuffer();
		boundOffsets[binding] = slice.offset();
		boundRanges[binding] = slice.length();
		boundCount = Math.max(boundCount, binding + 1);
	}

	@Override
	public void bindTexture(int binding, GpuTextureView view, GpuSampler sampler) {
		checkOpen();
		if (binding < ComputePipeline.FIRST_IMAGE_BINDING || binding >= VkComputePipeline.MAX_BINDINGS) {
			throw new IllegalArgumentException("binding " + binding + " is not an image binding; "
					+ "images live from " + ComputePipeline.FIRST_IMAGE_BINDING + " upwards");
		}
		if (!(view instanceof VulkanGpuTextureView vulkanView)) {
			throw new IllegalArgumentException("texture view was not created by the Vulkan backend");
		}
		if (!(sampler instanceof VulkanGpuSampler vulkanSampler)) {
			throw new IllegalArgumentException("sampler was not created by the Vulkan backend");
		}

		boundImageViews[binding] = vulkanView.vkImageView;
		boundSamplers[binding] = vulkanSampler.vkSampler;
		boundCount = Math.max(boundCount, binding + 1);
	}

	@Override
	public void dispatch(int x, int y, int z) {
		checkOpen();
		VkComputePipeline bound = pipeline;
		if (bound == null) {
			throw new IllegalStateException("no pipeline set on compute pass " + label);
		}

		// Pushed immediately before the dispatch, so a pass can rebind between dispatches the way
		// the OpenGL path does.
		try (MemoryStack stack = MemoryStack.stackPush()) {
			// Only the slots that were actually bound. A push descriptor for a slot holding nothing
			// is not merely wasteful, it is invalid -- the handle would be null.
			int writeCount = 0;
			for (int i = 0; i < boundCount; i++) {
				if (boundBuffers[i] != VK10.VK_NULL_HANDLE || boundImageViews[i] != VK10.VK_NULL_HANDLE) {
					writeCount++;
				}
			}

			VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(writeCount, stack);
			int at = 0;
			for (int i = 0; i < boundCount; i++) {
				if (boundImageViews[i] != VK10.VK_NULL_HANDLE) {
					VkDescriptorImageInfo.Buffer info = VkDescriptorImageInfo.calloc(1, stack)
							.sampler(boundSamplers[i])
							.imageView(boundImageViews[i])
							.imageLayout(VK10.VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
					writes.get(at++)
							.sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
							.dstBinding(i)
							.descriptorCount(1)
							.descriptorType(VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
							.pImageInfo(info);
					continue;
				}

				if (boundBuffers[i] == VK10.VK_NULL_HANDLE) {
					continue;
				}

				VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
						.buffer(boundBuffers[i])
						.offset(boundOffsets[i])
						.range(boundRanges[i]);
				writes.get(at++)
						.sType(VK10.VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
						.dstBinding(i)
						.descriptorCount(1)
						.descriptorType(VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
						.pBufferInfo(info);
			}

			KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commandBuffer,
					VK10.VK_PIPELINE_BIND_POINT_COMPUTE, bound.pipelineLayout(), 0, writes);
		}

		VK10.vkCmdDispatch(commandBuffer, x, y, z);
	}

	/**
	 * A pipeline barrier covering the reads named by {@code scopes}.
	 *
	 * <p>Blaze3D's own {@code VulkanCommandEncoder.memoryBarrier} is transfer-to-transfer only, so
	 * it is no use here: what has to be ordered is compute against a later draw reading indirect
	 * parameters, index data, or a texel buffer.
	 */
	@Override
	public void barrier(int scopes) {
		checkOpen();

		long dstStage = 0L;
		long dstAccess = 0L;

		if ((scopes & BarrierScope.STORAGE) != 0) {
			dstStage |= KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR;
			dstAccess |= KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_READ_BIT_KHR;
		}
		if ((scopes & BarrierScope.INDIRECT) != 0) {
			dstStage |= KHRSynchronization2.VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT_KHR;
			dstAccess |= KHRSynchronization2.VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT_KHR;
		}
		if ((scopes & (BarrierScope.INDEX | BarrierScope.VERTEX_ATTRIB)) != 0) {
			dstStage |= KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_INPUT_BIT_KHR;
			dstAccess |= KHRSynchronization2.VK_ACCESS_2_INDEX_READ_BIT_KHR
					| KHRSynchronization2.VK_ACCESS_2_VERTEX_ATTRIBUTE_READ_BIT_KHR;
		}
		if ((scopes & BarrierScope.TEXTURE_FETCH) != 0) {
			// The vertex shader reads instances as a uniform texel buffer.
			dstStage |= KHRSynchronization2.VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT_KHR;
			dstAccess |= KHRSynchronization2.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT_KHR;
		}
		if ((scopes & (BarrierScope.BUFFER_UPDATE | BarrierScope.CLIENT_MAPPED_BUFFER)) != 0) {
			dstStage |= KHRSynchronization2.VK_PIPELINE_STAGE_2_HOST_BIT_KHR;
			dstAccess |= KHRSynchronization2.VK_ACCESS_2_HOST_READ_BIT_KHR;
		}
		if (dstStage == 0L) {
			return;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkMemoryBarrier2KHR.Buffer barrier = VkMemoryBarrier2KHR.calloc(1, stack)
					.sType(KHRSynchronization2.VK_STRUCTURE_TYPE_MEMORY_BARRIER_2_KHR)
					.srcStageMask(KHRSynchronization2.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT_KHR)
					.srcAccessMask(KHRSynchronization2.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT_KHR)
					.dstStageMask(dstStage)
					.dstAccessMask(dstAccess);

			VkDependencyInfoKHR dependency = VkDependencyInfoKHR.calloc(stack)
					.sType(KHRSynchronization2.VK_STRUCTURE_TYPE_DEPENDENCY_INFO_KHR)
					.pMemoryBarriers(barrier);

			KHRSynchronization2.vkCmdPipelineBarrier2KHR(commandBuffer, dependency);
		}
	}

	/**
	 * End the command buffer and hand it back for submission in frame order.
	 *
	 * <p>Nothing to restore here, unlike OpenGL: a Vulkan command buffer carries its own state, so a
	 * dispatch cannot disturb what the graphics backend believes it has bound.
	 */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		encoder.execute(commandBuffer);
	}

	private void checkOpen() {
		if (closed) {
			throw new IllegalStateException("compute pass " + label + " is already closed");
		}
	}
}
