package dev.engine_room.flywheel.backend.compute.vk;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.engine_room.flywheel.backend.compute.ComputeBackend;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;

/**
 * Compute on Vulkan.
 *
 * <p>Easier than the OpenGL case in every way that matters. The device Blaze3D chooses already has a
 * combined graphics-and-compute queue - its own selection code rejects anything else, with an error
 * string that says {@code COMBINED_GRAPHICS_COMPUTE_PRESENT_QUEUE} - so a dispatch records onto the
 * same queue as the drawing with no ownership transfer. {@code VK_KHR_push_descriptor} and
 * {@code VK_KHR_synchronization2} are both required extensions, so bindings need no pool and
 * barriers need no fallback path. And a command buffer carries its own state, so unlike OpenGL there
 * is nothing to put back afterwards.
 *
 * <p>The one thing Blaze3D will not do is hand out a storage buffer: {@code VulkanConst.bufferUsageToVk}
 * has no case that produces {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT}, so a buffer it allocates can
 * never be bound as one. That is what {@code VulkanConstMixin} exists for, and it is the only mixin
 * in this project.
 */
public final class VkComputeBackend implements ComputeBackend {
	private static final Logger LOGGER = LoggerFactory.getLogger("flywheel/compute/vulkan");

	private final VulkanDevice device;

	private VkComputeBackend(VulkanDevice device) {
		this.device = device;
	}

	public static @Nullable VkComputeBackend createIfSupported(VulkanDevice device) {
		return new VkComputeBackend(device);
	}

	@Override
	public boolean available() {
		return true;
	}

	@Override
	public String name() {
		return "vulkan";
	}

	@Override
	public @Nullable ComputePipeline createPipeline(ComputePipeline.Description description) {
		try {
			// 64 to match the OpenGL path; the shader declares it and nothing reads it back.
			return VkComputePipeline.compile(device, description, 64);
		} catch (RuntimeException e) {
			LOGGER.error("could not build compute pipeline {}", description.label(), e);
			return null;
		}
	}

	@Override
	public ComputePass beginPass(String label) {
		return new VkComputePass(device.createCommandEncoder(), label);
	}

	@Override
	public void flush() {
		// Sends everything recorded so far, including our compute command buffers. Safe between
		// frames, which is where the only caller runs; a render pass must not be open.
		device.createCommandEncoder().submit();
	}

	@Override
	public boolean awaitGpu(long timeoutNanos) {
		// A full device wait. Only self-tests and diagnostics ask for this; the renderer never does.
		org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(device.vkDevice());
		return true;
	}
}
