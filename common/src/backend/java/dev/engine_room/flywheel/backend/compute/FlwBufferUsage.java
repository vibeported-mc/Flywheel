package dev.engine_room.flywheel.backend.compute;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import com.mojang.blaze3d.buffers.GpuBuffer;

/**
 * Buffer usage bits Blaze3D 26.2 does not define.
 */
public final class FlwBufferUsage {
	/**
	 * Marks a buffer as bindable for shader storage.
	 *
	 * <p>On OpenGL this is bookkeeping only: any buffer object can be bound to
	 * {@code GL_SHADER_STORAGE_BUFFER}, and {@code GlConst.bufferUsageToGlFlag} ignores bits it does
	 * not recognise, so a buffer created with this flag behaves exactly as it would without it. It
	 * earns its keep as an assertion at our own call sites, and as the thing a Vulkan backend will
	 * have to translate into {@code VK_BUFFER_USAGE_STORAGE_BUFFER_BIT} - which is a real blocker
	 * there, since {@code VulkanConst.bufferUsageToVk} has no case that produces it.
	 *
	 * <p>The value is derived from whatever Blaze3D currently defines rather than hardcoded, so that
	 * a future Minecraft claiming the next bit for itself cannot silently collide with us.
	 */
	public static final int STORAGE = nextFreeBit();

	private FlwBufferUsage() {
	}

	private static int nextFreeBit() {
		int highest = 0;
		for (Field field : GpuBuffer.class.getDeclaredFields()) {
			if (field.getType() == int.class
					&& Modifier.isStatic(field.getModifiers())
					&& field.getName().startsWith("USAGE_")) {
				try {
					highest = Math.max(highest, field.getInt(null));
				} catch (IllegalAccessException ignored) {
					// A USAGE_ constant we cannot read is one we cannot collide with either.
				}
			}
		}
		if (highest == 0) {
			throw new IllegalStateException(
					"GpuBuffer declares no USAGE_ constants; Blaze3D's buffer API has changed shape");
		}
		return Integer.highestOneBit(highest) << 1;
	}
}
