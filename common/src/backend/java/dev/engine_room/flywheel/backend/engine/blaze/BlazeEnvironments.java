package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.blaze3dx.buffer.Staging;

import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;

/**
 * The transform an embedded environment puts its instances in.
 *
 * <p>Most instances live in the world: a cogwheel's position is a block position and that is the
 * end of it. An <em>embedded</em> one does not. Everything mounted on a Create contraption -- every
 * cog, belt and actor on a moving ship, train or piston -- is positioned in the contraption's own
 * space, and the contraption carries a matrix saying where that space currently is. Ignore it and
 * the parts draw where the contraption was assembled rather than where it is now, which is to say
 * scattered around the world's origin while the contraption flies away empty.
 *
 * <p>Flywheel keeps one matrix per environment in an arena, and its own shaders apply it after the
 * mod's instance body and before the projection. This holds a small uniform buffer per environment
 * with the same 112-byte record -- a mat4 pose and a mat3 normal matrix padded out to three vec4s
 * -- copied straight out of that arena, because the arena already stores it in exactly that shape.
 *
 * <p>Environment zero is the world itself. It is written as an identity here rather than read from
 * the arena: the arena reserves index zero but a matrix that came back as zeros would collapse
 * every unembedded model in the world to a point, and that is too expensive a thing to assume.
 */
public class BlazeEnvironments implements AutoCloseable {
	/** mat4 pose, then a mat3 as three vec4s, which is how std140 and the arena both store it. */
	public static final int BASE_SIZE = 64 + 48;

	/**
	 * The same, plus the lighting scene Sable attaches to every environment.
	 *
	 * <p>Sable rewrites the arena's element size and writes four more floats and a matrix after the
	 * normal, then its own copies of Flywheel's shaders read them. Both halves of that are invisible
	 * to this backend -- it generates its shaders rather than including Flywheel's, so Sable's
	 * declarations never reach them -- which is how machinery inside a sub-level came to be prepared,
	 * culled and never drawn, its pipeline failing to compile on an identifier nothing declared.
	 *
	 * <p>Padding included rather than implied: {@code skyLightScale} and {@code sceneId} are a float
	 * and a uint, and std140 puts the matrix that follows them on a sixteen-byte boundary. Sable
	 * writes those two pad words explicitly, so the layouts line up exactly.
	 */
	public static final int LIGHTING_SCENE_SIZE = BASE_SIZE + 16 + 64;

	public static final String BLOCK_NAME = "FlwEnvironment";

	private static final String BASE_GLSL = """
			layout(std140) uniform FlwEnvironment {
				mat4 _flw_pose;
				vec4 _flw_normalA;
				vec4 _flw_normalB;
				vec4 _flw_normalC;
			};
			""";

	private static final String LIGHTING_SCENE_GLSL = """
			layout(std140) uniform FlwEnvironment {
				mat4 _flw_pose;
				vec4 _flw_normalA;
				vec4 _flw_normalB;
				vec4 _flw_normalC;
				float _flw_skyLightScale;
				uint _flw_lightingSceneId;
				vec2 _flw_environmentPadding;
				mat4 _flw_lightingSceneMatrix;
			};
			""";

	/**
	 * Whether environments carry a lighting scene, which is a question about what is installed.
	 *
	 * <p>Asked of the arena rather than assumed, because the answer is Sable's to give: with it
	 * absent a record is {@link #BASE_SIZE} and declaring the longer block would have the shader read
	 * past the end of the buffer.
	 */
	public static boolean hasLightingScene(EnvironmentStorage environments) {
		return environments.arena.elementSize() >= LIGHTING_SCENE_SIZE;
	}

	public static String glsl(boolean lightingScene) {
		return lightingScene ? LIGHTING_SCENE_GLSL : BASE_GLSL;
	}

	private final Map<Integer, GpuBuffer> buffers = new HashMap<>();

	/** Render thread, once a frame, after {@code EnvironmentStorage.flush}. */
	public void flush(EnvironmentStorage environments) {
		for (Map.Entry<Integer, GpuBuffer> entry : buffers.entrySet()) {
			upload(environments, entry.getKey(), entry.getValue());
		}
	}

	/**
	 * The pose an environment currently puts its instances in, for anything that has to agree with
	 * the shader about where they are.
	 *
	 * <p>Read out of the same arena bytes the uniform is copied from rather than recomposed from the
	 * environment tree, so the cull pass and the draw cannot disagree -- which they would only do
	 * intermittently, and only for things in motion.
	 */
	public org.joml.Matrix4f poseOf(EnvironmentStorage environments, int matrixIndex,
			org.joml.Matrix4f dest) {
		if (matrixIndex == 0) {
			return dest.identity();
		}

		return dest.set(MemoryUtil.memByteBuffer(environments.arena.indexToPointer(matrixIndex), 64)
				.order(ByteOrder.nativeOrder())
				.asFloatBuffer());
	}

	public GpuBufferSlice slice(EnvironmentStorage environments, int matrixIndex) {
		GpuBuffer buffer = buffers.get(matrixIndex);

		if (buffer == null) {
			buffer = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel environment " + matrixIndex,
							GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST,
							sizeOf(environments));
			buffers.put(matrixIndex, buffer);

			// Filled immediately: an environment first seen mid-frame is drawn in that same frame,
			// and an unwritten buffer is zeros, which is a matrix that deletes its geometry.
			upload(environments, matrixIndex, buffer);
		}

		return buffer.slice();
	}

	@Override
	public void close() {
		for (GpuBuffer buffer : buffers.values()) {
			buffer.close();
		}
		buffers.clear();
	}

	/** As many bytes as a record actually occupies, which is Sable's to decide. */
	private static int sizeOf(EnvironmentStorage environments) {
		return (int) Math.max(BASE_SIZE, environments.arena.elementSize());
	}

	private void upload(EnvironmentStorage environments, int matrixIndex, GpuBuffer buffer) {
		int size = sizeOf(environments);

		if (matrixIndex == 0) {
			Staging.upload(buffer.slice(), identity(size));
			return;
		}

		// Straight out of the arena: Flywheel already stores the record in the layout the shader
		// declares, so this is a copy rather than a conversion.
		Staging.upload(buffer.slice(),
				MemoryUtil.memByteBuffer(environments.arena.indexToPointer(matrixIndex), size));
	}

	private static ByteBuffer identity(int size) {
		ByteBuffer data = ByteBuffer.allocateDirect(size)
				.order(ByteOrder.nativeOrder());

		for (int i = 0; i < 4; i++) {
			data.putFloat(i * 20, 1.0f);
		}

		// The normal matrix, as three vec4 rows with the diagonal set.
		data.putFloat(64, 1.0f);
		data.putFloat(80 + 4, 1.0f);
		data.putFloat(96 + 8, 1.0f);

		return data;
	}
}
