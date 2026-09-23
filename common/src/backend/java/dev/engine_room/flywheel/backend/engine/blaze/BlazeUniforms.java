package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.blaze3dx.buffer.Staging;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.uniform.LevelUniforms;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.CardinalLighting;
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
	/**
	 * mat4 viewProjection, vec4 cameraPos, two floats padded to 16, ivec4 renderOrigin, the fog
	 * colour and its two ranges, then the two light directions and the ambient-light flag.
	 */
	public static final int SIZE = 64 + 16 + 16 + 16 + 16 + 16 + 16 + 16 + 16;

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
				float flw_renderTicks;
				ivec4 _flw_renderOrigin;
				vec4 flw_fogColor;
				vec4 _flw_fogRanges;
				vec4 _flw_light0Direction;
				vec4 _flw_light1Direction;
				uvec4 _flw_ambient;
			};

			// The two directional lights vanilla shades entities by, and whether this dimension uses
			// the flat cardinal table the nether has. All three are read by `diffuse.glsl`, which is
			// pasted in as shipped, so these are the names it has to find.
			#define flw_light0Direction (_flw_light0Direction.xyz)
			#define flw_light1Direction (_flw_light1Direction.xyz)
			#define flw_constantAmbientLight (_flw_ambient.x)

			// 26.2 runs two independent linear fogs -- an environmental one measured spherically and
			// a render distance one measured cylindrically -- and takes whichever is stronger. Packed
			// into one vec4 here for the same std140 reason as the two above, and named apart so the
			// fog shaders a mod already ships compile against it unchanged.
			#define flw_fogEnvironmentalRange _flw_fogRanges.xy
			#define flw_fogRenderDistanceRange _flw_fogRanges.zw

			// Flywheel's light lookup names it as an ivec3. Declared as an ivec4 above for the same
			// reason the camera is -- std140 packs a three-component member and Std140Builder does
			// not -- and narrowed here so the name the shared GLSL expects is the one it gets.
			#define flw_renderOrigin _flw_renderOrigin.xyz
			""";

	private @Nullable GpuBuffer buffer;

	public void update(RenderContext context, Vec3i renderOrigin) {
		ByteBuffer data = ByteBuffer.allocateDirect(SIZE)
				.order(ByteOrder.nativeOrder());

		Vec3 camera = context.camera().pos;
		FogData fog = context.camera().fogData;

		Std140Builder.intoBuffer(data)
				.putMat4f(context.viewProjection())
				.putVec4((float) (camera.x - renderOrigin.getX()),
						(float) (camera.y - renderOrigin.getY()),
						(float) (camera.z - renderOrigin.getZ()), 0.0f)
				// Not the world time. A rotating shader multiplies this by an instance's speed to
				// get an angle, so it has to advance smoothly between ticks or every cog in the
				// world steps twenty times a second instead of turning.
				.putFloat(renderSeconds(context))
				// Both, because mods use both: a rotating shader works in seconds and a scrolling
				// one in ticks. Declaring one in the block and writing only the other is not a
				// wrong picture -- the shader compiles, the machine draws, and the part that was
				// supposed to move simply does not.
				.putFloat(renderSeconds(context) * 20.0f)
					// Where the light volume is anchored. Instance positions are relative to it and
					// the lookup adds it back to reach an absolute block position.
					.putIVec4(renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ(), 0)
					.putVec4(fog.color.x(), fog.color.y(), fog.color.z(), fog.color.w())
					.putVec4(fog.environmentalStart, fog.environmentalEnd, fog.renderDistanceStart,
							fog.renderDistanceEnd)
					// Filled by LightingMixin from vanilla's own entity lighting, so an instanced part
					// is shaded by the same two lights as the block entity renderer it replaced.
					.putVec4(LevelUniforms.LIGHT0_DIRECTION.x(), LevelUniforms.LIGHT0_DIRECTION.y(),
							LevelUniforms.LIGHT0_DIRECTION.z(), 0.0f)
					.putVec4(LevelUniforms.LIGHT1_DIRECTION.x(), LevelUniforms.LIGHT1_DIRECTION.y(),
							LevelUniforms.LIGHT1_DIRECTION.z(), 0.0f)
					.putIVec4(constantAmbientLight(context), 0, 0, 0);

		BlazeStats.fogEnvironmentalStart = fog.environmentalStart;
		BlazeStats.fogEnvironmentalEnd = fog.environmentalEnd;
		BlazeStats.fogRenderDistanceStart = fog.renderDistanceStart;
		BlazeStats.fogRenderDistanceEnd = fog.renderDistanceEnd;

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

	/**
	 * Whether this dimension shades by the flat table rather than the sky-lit one.
	 *
	 * <p>What used to be {@code DimensionSpecialEffects#constantAmbientLight} is now the dimension's
	 * cardinal light type, and the nether-style flat table is every type that is not the default.
	 */
	private static int constantAmbientLight(RenderContext context) {
		return context.level()
				.dimensionType()
				.cardinalLightType() != CardinalLighting.Type.DEFAULT ? 1 : 0;
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
