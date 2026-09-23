package dev.engine_room.flywheel.backend.gl;

import java.util.Collections;

import org.lwjgl.opengl.GL32;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.blaze3dx.Blaze3dx;
import net.minecraft.client.Minecraft;

/**
 * Binding a render target by hand, which {@link RenderTarget} itself stopped offering in 26.2.
 * <p>
 * Every draw now goes through a render pass, and the OpenGL backend keeps one framebuffer per set of
 * attachments rather than one per target. It binds that framebuffer when a pass opens and rebinds
 * the window's own framebuffer when the pass is submitted, so at every point Flywheel is handed
 * control - the render stage events fire between passes - nothing but the back buffer is bound, and
 * raw GL draws issued there never reach the level.
 */
public final class GlRenderTargets {
	private GlRenderTargets() {
	}

	/**
	 * The target the level is being drawn into, which is what Flywheel's own draws belong in.
	 */
	public static RenderTarget main() {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}

	public static void bind(RenderTarget target) {
		GlStateManager._glBindFramebuffer(GL32.GL_FRAMEBUFFER, fbo(target));
	}

	/**
	 * Hands the back buffer back, which is what the backend leaves bound between passes.
	 */
	public static void unbind() {
		GlStateManager._glBindFramebuffer(GL32.GL_FRAMEBUFFER, 0);
	}

	public static int fbo(RenderTarget target) {
		// Through blaze3dx rather than the accessor directly: it owns the mixin that reaches
		// GpuDevice's private backend field now, and one seam onto Minecraft internals is the point
		// of the library.
		GlDevice device = Blaze3dx.glDevice();
		return device.frameBufferCache()
				.getFbo(device.directStateAccess(), Collections.singletonList((GlTexture) target.getColorTexture()), (GlTexture) target.getDepthTexture());
	}
}
