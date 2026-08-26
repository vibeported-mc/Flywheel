package dev.engine_room.flywheel.backend.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;

/**
 * Binding a framebuffer in 26.2 goes through the OpenGL backend's FBO cache, which is only reachable
 * from the backend GpuDevice wraps.
 */
@Mixin(GpuDevice.class)
public interface GpuDeviceAccessor {
	@Accessor("backend")
	GpuDeviceBackend flywheel$backend();
}
