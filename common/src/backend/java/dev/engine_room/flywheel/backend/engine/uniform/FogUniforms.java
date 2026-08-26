package dev.engine_room.flywheel.backend.engine.uniform;

import org.joml.Vector4fc;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.renderer.fog.FogData;

public final class FogUniforms extends UniformWriter {
	// vec4 color + two vec2 ranges.
	private static final int SIZE = 4 * 8;
	static final UniformBuffer BUFFER = new UniformBuffer(Uniforms.FOG_INDEX, SIZE);

	private FogUniforms() {
	}

	/**
	 * Minecraft 26.2 dropped the single range + fog shape pair in favor of two independent linear
	 * fogs: an environmental one measured spherically, and a render distance one measured
	 * cylindrically. The stronger of the two wins, exactly as vanilla's own fog shader does.
	 */
	public static void update(RenderContext context) {
		long ptr = BUFFER.ptr();

		FogData fog = context.camera().fogData;
		Vector4fc color = fog.color;

		ptr = writeFloat(ptr, color.x());
		ptr = writeFloat(ptr, color.y());
		ptr = writeFloat(ptr, color.z());
		ptr = writeFloat(ptr, color.w());

		ptr = writeFloat(ptr, fog.environmentalStart);
		ptr = writeFloat(ptr, fog.environmentalEnd);
		ptr = writeFloat(ptr, fog.renderDistanceStart);
		ptr = writeFloat(ptr, fog.renderDistanceEnd);

		BUFFER.markDirty();
	}
}
