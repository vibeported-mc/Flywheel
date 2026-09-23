package dev.engine_room.flywheel.backend.compute;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.opengl.GlCommandEncoder;
import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.backend.mixin.GpuDeviceAccessor;

/**
 * The one seam between Flywheel and Minecraft's graphics internals.
 *
 * <p>Everything that reaches past Blaze3D's public surface goes through here, so that the blast
 * radius of a Minecraft update is this file plus the access transformer and accessor it uses. For
 * scale: 26.3 moves every type named below into {@code com.mojang.renderpearl}.
 */
public final class Blaze3dx {
	private Blaze3dx() {
	}

	/**
	 * The OpenGL backend behind the current device, or null when we are not on OpenGL.
	 *
	 * <p>{@code GpuDevice} keeps its backend in a private field with no getter, so the field is
	 * widened by access transformer. Without this there is no way at all to get from a live device
	 * to the concrete backend.
	 */
	public static @Nullable GlDevice glDevice() {
		return backend() instanceof GlDevice gl ? gl : null;
	}

	/**
	 * The concrete backend behind the current device, whichever it is.
	 *
	 * <p>{@code GpuDevice} keeps it in a private field with no getter, so it is reached through
	 * {@link GpuDeviceAccessor} -- the same accessor {@code GlRenderTargets} already uses to find
	 * the FBO cache. Without it there is no way at all to get from a live device to the thing
	 * actually doing the work.
	 */
	public static GpuDeviceBackend backend() {
		return ((GpuDeviceAccessor) RenderSystem.getDevice()).flywheel$backend();
	}

	/**
	 * The live OpenGL command encoder.
	 *
	 * <p>{@code GlDevice.createCommandEncoder()} does not create anything - it returns the single
	 * cached encoder the game records every frame through. That is what makes this reachable
	 * without widening {@code CommandEncoder.backend()} as well.
	 */
	public static @Nullable GlCommandEncoder glEncoder() {
		GlDevice device = glDevice();
		return device == null ? null : (GlCommandEncoder) device.createCommandEncoder();
	}

	/**
	 * Limit which mip levels of a texture may be sampled, on OpenGL only.
	 *
	 * <p>For a pass that draws into one level of a texture while sampling another. Vulkan describes
	 * each level with its own image view and the two never meet, so this is a no-op there. OpenGL
	 * binds the whole texture object to a sampler, so the level being drawn into is also, as far as
	 * the driver can tell, readable -- which is a feedback loop, and the result of the whole read is
	 * undefined.
	 *
	 * <p>Undefined in practice means zero, everywhere, with no GL error raised: a depth pyramid whose
	 * first level is correct and whose every later level is empty. Restricting the range to exactly
	 * the source level makes the two disjoint and the read well defined again.
	 *
	 * <p>Callers must put the range back with {@link #releaseMipRange} once the pass is done, or the
	 * next thing to sample that texture sees only the sliver left behind.
	 */
	public static void restrictMipRange(com.mojang.blaze3d.textures.GpuTexture texture, int base,
			int max) {
		if (glDevice() == null
				|| !(texture instanceof com.mojang.blaze3d.opengl.GlTexture gl)) {
			return;
		}

		if (org.lwjgl.opengl.GL.getCapabilities().GL_ARB_direct_state_access) {
			org.lwjgl.opengl.ARBDirectStateAccess.glTextureParameteri(gl.glId(),
					org.lwjgl.opengl.GL33C.GL_TEXTURE_BASE_LEVEL, base);
			org.lwjgl.opengl.ARBDirectStateAccess.glTextureParameteri(gl.glId(),
					org.lwjgl.opengl.GL33C.GL_TEXTURE_MAX_LEVEL, max);
			return;
		}

		// Without direct state access the texture has to be bound to be touched. Unit 11 is the last
		// one GlStateManager keeps a shadow of, so it is the least likely to be carrying anything a
		// draw is about to want, and it is put back empty afterwards.
		com.mojang.blaze3d.opengl.GlStateManager._activeTexture(org.lwjgl.opengl.GL33C.GL_TEXTURE0 + 11);
		com.mojang.blaze3d.opengl.GlStateManager._bindTexture(gl.glId());
		org.lwjgl.opengl.GL33C.glTexParameteri(org.lwjgl.opengl.GL33C.GL_TEXTURE_2D,
				org.lwjgl.opengl.GL33C.GL_TEXTURE_BASE_LEVEL, base);
		org.lwjgl.opengl.GL33C.glTexParameteri(org.lwjgl.opengl.GL33C.GL_TEXTURE_2D,
				org.lwjgl.opengl.GL33C.GL_TEXTURE_MAX_LEVEL, max);
		com.mojang.blaze3d.opengl.GlStateManager._bindTexture(0);
		com.mojang.blaze3d.opengl.GlStateManager._activeTexture(org.lwjgl.opengl.GL33C.GL_TEXTURE0);
	}

	/** Undoes {@link #restrictMipRange}, opening the whole chain back up. */
	public static void releaseMipRange(com.mojang.blaze3d.textures.GpuTexture texture, int levels) {
		restrictMipRange(texture, 0, Math.max(0, levels - 1));
	}

	/**
	 * Forget which program and pipeline Blaze3D believes are bound.
	 *
	 * <p>{@code GlCommandEncoder} only calls {@code glUseProgram} when the program differs from the
	 * one it last bound. A compute dispatch binds a program out of band, so without this the next
	 * draw would skip the rebind and render with the compute program still current - which shows up
	 * as geometry silently vanishing rather than as an error.
	 *
	 * <p>Callers do not invoke this directly; a compute pass does it on close, so that the
	 * invalidation cannot be forgotten.
	 */
	public static void invalidateProgramCache() {
		GlCommandEncoder encoder = glEncoder();
		if (encoder == null) {
			return;
		}
		encoder.lastProgram = null;
		encoder.lastPipeline = null;
		encoder.lastVertexArray = null;
	}
}
