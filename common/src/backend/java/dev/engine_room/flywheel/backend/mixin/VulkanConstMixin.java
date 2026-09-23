package dev.engine_room.flywheel.backend.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.vulkan.VulkanConst;

import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;

/**
 * Let a Blaze3D-allocated Vulkan buffer be used as a storage buffer.
 *
 * <p>The only mixin in this project, and the only thing on the Vulkan side that could not be
 * sidestepped. {@code bufferUsageToVk} translates Blaze3D's usage bits into Vulkan's, and there is
 * no case in it that yields {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT} - because Blaze3D 26.2 has no
 * concept of a storage buffer to translate. Without this, {@code vkCreateBuffer} is told the buffer
 * will never be one, and binding it to a compute shader is invalid usage.
 *
 * <p>OpenGL needs no equivalent: any GL buffer can be bound to {@code GL_SHADER_STORAGE_BUFFER}
 * whatever it was created with, which is why the storage bit is pure bookkeeping there.
 */
@Mixin(VulkanConst.class)
public class VulkanConstMixin {
	/** VK_BUFFER_USAGE_STORAGE_BUFFER_BIT. */
	private static final int STORAGE_BUFFER_BIT = 0x20;

	@Inject(method = "bufferUsageToVk", at = @At("RETURN"), cancellable = true, remap = false)
	private static void flywheel$addStorageUsage(@GpuBuffer.Usage int usage,
			CallbackInfoReturnable<Integer> callback) {
		if ((usage & FlwBufferUsage.STORAGE) != 0) {
			callback.setReturnValue(callback.getReturnValue() | STORAGE_BUFFER_BIT);
		}
	}
}
