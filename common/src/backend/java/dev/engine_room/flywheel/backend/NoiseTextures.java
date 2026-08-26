package dev.engine_room.flywheel.backend;

import java.io.IOException;

import org.jetbrains.annotations.UnknownNullability;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;

import dev.engine_room.flywheel.lib.util.ResourceUtil;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;

public class NoiseTextures {
	public static final Identifier NOISE_TEXTURE = ResourceUtil.rl("textures/flywheel/noise/blue.png");

	@UnknownNullability
	public static AbstractTexture BLUE_NOISE;

	public static void reload(ResourceManager manager) {
		if (BLUE_NOISE != null) {
			BLUE_NOISE.close();
			BLUE_NOISE = null;
		}
		var optional = manager.getResource(NOISE_TEXTURE);

		if (optional.isEmpty()) {
			return;
		}

		try (var is = optional.get()
				.open()) {
			var image = NativeImage.read(NativeImage.Format.LUMINANCE, is);

			BLUE_NOISE = new NoiseTexture(image);
		} catch (IOException e) {

		}
	}

	/**
	 * Minecraft 26.2 uploads textures through the GPU device and keeps filtering and wrapping in
	 * sampler objects, so DynamicTexture no longer offers a way to set them per texture.
	 */
	private static class NoiseTexture extends AbstractTexture {
		private final NativeImage pixels;

		private NoiseTexture(NativeImage image) {
			pixels = image;

			GpuDevice device = RenderSystem.getDevice();
			texture = device.createTexture(() -> "Flywheel Blue Noise", GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST, GpuFormat.R8_UNORM, pixels.getWidth(), pixels.getHeight(), 1, 1);
			sampler = RenderSystem.getSamplerCache()
					.getSampler(AddressMode.REPEAT, AddressMode.REPEAT, FilterMode.LINEAR, FilterMode.LINEAR, false);
			textureView = device.createTextureView(texture);
			device.createCommandEncoder()
					.writeToTexture(texture, pixels);
		}

		@Override
		public void close() {
			pixels.close();
			super.close();
		}
	}
}
