package dev.engine_room.flywheel.backend.engine;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL33C;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.backend.Samplers;
import dev.engine_room.flywheel.backend.gl.GlTextureUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;

public class TextureBinder {
	public static void bind(Identifier resourceLocation) {
		GlStateManager._bindTexture(byName(resourceLocation));
	}

	/**
	 * The overlay and light textures are no longer bound through RenderSystem's shader texture slots
	 * in 26.2; they are plain GPU textures that get bound to Flywheel's own sampler units.
	 */
	public static void bindLightAndOverlay() {
		GameRenderer gameRenderer = Minecraft.getInstance().gameRenderer;
		GpuSampler sampler = RenderSystem.getSamplerCache()
				.getClampToEdge(FilterMode.LINEAR);

		setupTexture(Samplers.OVERLAY, gameRenderer.overlayTexture()
				.getTextureView(), sampler);
		setupTexture(Samplers.LIGHT, gameRenderer.lightmap(), sampler);
	}

	public static void resetLightAndOverlay() {
		Samplers.OVERLAY.makeActive();
		GlStateManager._bindTexture(0);
		GL33C.glBindSampler(Samplers.OVERLAY.number, 0);

		Samplers.LIGHT.makeActive();
		GlStateManager._bindTexture(0);
		GL33C.glBindSampler(Samplers.LIGHT.number, 0);
	}

	private static void setupTexture(GlTextureUnit unit, GpuTextureView textureView, GpuSampler sampler) {
		unit.makeActive();

		GlTexture texture = (GlTexture) textureView.texture();
		int target;
		if ((texture.usage() & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
			target = GL13.GL_TEXTURE_CUBE_MAP;
			GL11.glBindTexture(target, texture.glId());
		} else {
			target = GL11.GL_TEXTURE_2D;
			GlStateManager._bindTexture(texture.glId());
		}

		GL33C.glBindSampler(unit.number, ((GlSampler) sampler).getId());

		int mipLevel = textureView.baseMipLevel();
		GlStateManager._texParameter(target, GL12.GL_TEXTURE_BASE_LEVEL, mipLevel);
		GlStateManager._texParameter(target, GL12.GL_TEXTURE_MAX_LEVEL, mipLevel + textureView.mipLevels() - 1);
	}

	/**
	 * Get a built-in texture by its resource location.
	 *
	 * @param texture The texture's resource location.
	 * @return The texture.
	 */
	public static int byName(Identifier texture) {
		AbstractTexture texture1 = Minecraft.getInstance()
				.getTextureManager()
				.getTexture(texture);
		return ((GlTexture) texture1.getTexture()).glId();
	}
}
