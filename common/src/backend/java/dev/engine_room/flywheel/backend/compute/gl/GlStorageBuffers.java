package dev.engine_room.flywheel.backend.compute.gl;

import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlBuffer;

/**
 * Binding storage buffers for <em>graphics</em> work.
 *
 * <p>A compute pass binds its own; this exists because a vertex shader reads per-instance data from
 * a storage buffer too, and Blaze3D's {@code RenderPass} has no way to express that -
 * {@code UniformType} is only {@code UNIFORM_BUFFER} and {@code TEXEL_BUFFER}, and
 * {@code opengl.Uniform} is a sealed interface that cannot grow a storage-buffer variant.
 *
 * <p>It does not need to. Storage buffer bindings are global state, independent of the bound
 * program and of any render pass, and they live in a namespace of their own that Blaze3D never
 * touches. So binding one before opening a render pass works, and nothing vanilla does will disturb
 * it. That is the same property that keeps the compute path free of mixins.
 */
public final class GlStorageBuffers {
	private GlStorageBuffers() {
	}

	public static void bind(int binding, GpuBufferSlice slice) {
		if (!(slice.buffer() instanceof GlBuffer buffer)) {
			throw new IllegalArgumentException("buffer was not allocated by the OpenGL backend");
		}
		GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer.handle(),
				slice.offset(), slice.length());
	}

	public static void unbind(int binding) {
		GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0);
	}
}
