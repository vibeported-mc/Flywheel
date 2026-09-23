package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

/**
 * What every draw needs to know about the frame it is in.
 *
 * <p>One std140 block, rebuilt once a frame and bound to both the cull pass and the draws. The
 * OpenGL backend spreads this across five uniform buffers bound by index with
 * {@code glBindBufferRange}; here there is one, declared in the pipeline's bind group layout like
 * anything else, because 26.2 binds a uniform block only if the pipeline said it existed.
 *
 * <h2>Positions are relative to the render origin</h2>
 *
 * <p>The view-projection is pre-translated by the negated camera position, and instance positions
 * are relative to a render origin that follows the player. Both exist because a world coordinate
 * far from spawn does not survive a float: at 10 million blocks the spacing between representable
 * positions is larger than a block, and geometry visibly snaps. Keeping everything near zero is
 * what makes a 32-bit pipeline usable at Minecraft's world size, and it is why a shader here never
 * sees an absolute position.
 */
public class BlazeUniforms implements AutoCloseable {
	/** mat4 viewProjection, vec4 cameraPos, float renderSeconds padded out to 16. */
	public static final int SIZE = 64 + 16 + 16;

	public static final String BLOCK_NAME = "FlwFrame";

	/**
	 * Declared in every generated shader that needs it, and matched by name at link time.
	 *
	 * <p>The camera is a vec4 rather than a vec3, and that is not cosmetic.
	 *
	 * <p>GLSL's std140 gives a vec3 an alignment of 16 and a size of 12, so a float declared after
	 * one is packed into the gap at offset 76. {@code Std140Builder.putVec3} does not pack it: it
	 * writes three floats and then skips the fourth, leaving the next write at 80. Declare the
	 * member as a vec3 and the clock is written at 80 and read at 76 -- and the shader reads zero
	 * from the padding, so every machine in the world stands still while the matrix beside it,
	 * written the same way, is perfectly correct.
	 *
	 * <p>Making it a vec4 puts both sides at 80 and the question does not arise.
	 */
	public static final String GLSL = """
			layout(std140) uniform FlwFrame {
				mat4 flw_viewProjection;
				vec4 flw_cameraPos;
				float flw_renderSeconds;
			};
			""";

	private @Nullable GpuBuffer buffer;

	public void update(RenderContext context, Vec3i renderOrigin) {
		ByteBuffer data = ByteBuffer.allocateDirect(SIZE)
				.order(ByteOrder.nativeOrder());

		Vec3 camera = context.camera().pos;

		Std140Builder.intoBuffer(data)
				.putMat4f(context.viewProjection())
				.putVec4((float) (camera.x - renderOrigin.getX()),
						(float) (camera.y - renderOrigin.getY()),
						(float) (camera.z - renderOrigin.getZ()), 0.0f)
				// Not the world time. A rotating shader multiplies this by an instance's speed to
				// get an angle, so it has to advance smoothly between ticks or every cog in the
				// world steps twenty times a second instead of turning.
				.putFloat(renderSeconds(context));

		data.rewind();

		if (buffer == null) {
			buffer = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel frame uniforms",
							GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, SIZE);
		}

		Staging.upload(buffer.slice(), data);
	}

	public @Nullable GpuBufferSlice slice() {
		return buffer == null ? null : buffer.slice();
	}

	@Override
	public void close() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
	}

	private static float renderSeconds(RenderContext context) {
		long ticks = context.level()
				.getGameTime();

		// Wrapped, because a float stops being able to represent fractions of a second long before
		// a world's age stops growing -- and a shader that stutters after a few real-time days is
		// the kind of bug nobody reproduces.
		float seconds = (ticks % 24000L) / 20.0f;

		return seconds + context.partialTick() / 20.0f;
	}
}
