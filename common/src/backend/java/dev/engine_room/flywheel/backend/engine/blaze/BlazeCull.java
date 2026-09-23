package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compute.BarrierScope;
import dev.engine_room.flywheel.backend.compute.Compute;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;
import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
import dev.engine_room.flywheel.lib.math.MoreMath;
import net.minecraft.core.Vec3i;

/**
 * The GPU deciding what to draw, once a frame, for every instancer.
 *
 * <p>Two dispatches and then one indirect call per instancer. The cull pass tests every instance of
 * a model against the frustum and packs the survivors' indices; the apply pass turns the count it
 * arrived at into draw commands, one per mesh of the model. The CPU writes no command and reads no
 * count -- it issues {@code drawIndexedIndirect} and the GPU decides how much work that is.
 *
 * <p>That is the whole point of the backend. Drawing every instance of every model and letting the
 * rasteriser discard what is off screen costs a vertex shader invocation per vertex per instance,
 * on machinery the player is facing away from.
 *
 * <h2>Why per instancer rather than per material</h2>
 *
 * <p>One instancer is one model, so all of its instances survive or fail the same test and share
 * one compacted list. Batching several models into one call would mean a shared list with a base
 * offset per model, which is how the OpenGL backend does it and is worth doing later; it needs the
 * draw's model index, and that needs {@code gl_DrawID} plumbed through the generated shader.
 */
public class BlazeCull implements AutoCloseable {
	/** Matching {@code local_size_x} in the generated cull shader. */
	private static final int GROUP_SIZE = 64;

	/**
	 * 6 frustum planes, the bounding sphere, the camera, the view-projection, the pyramid's shape,
	 * and a count padded to a vec4.
	 */
	private static final int PARAMS_BYTES = (6 + 1 + 1) * 4 * Float.BYTES
			+ 16 * Float.BYTES
			+ 2 * 4 * Float.BYTES;

	/** indexCount, instanceCount, firstIndex, vertexOffset, firstInstance. */
	static final int COMMAND_INTS = 5;

	static final int COMMAND_BYTES = COMMAND_INTS * Integer.BYTES;

	private final Map<Object, ComputePipeline> cullers = new HashMap<>();
	private final Map<Object, Boolean> failed = new HashMap<>();

	private @Nullable ComputePipeline apply;

	private final Vector4f[] planes = new Vector4f[6];

	public BlazeCull() {
		for (int i = 0; i < planes.length; i++) {
			planes[i] = new Vector4f();
		}
	}

	/**
	 * Runs the cull and apply passes for every instancer that has anything to draw.
	 *
	 * <p>One pass for all of them rather than one each: a compute pass is a command buffer on
	 * Vulkan and an out-of-band program bind on OpenGL, and neither wants opening per instancer.
	 */
	public Set<BlazeInstancer<?>> dispatch(List<BlazeInstancer<?>> instancers, RenderContext context,
			Vec3i origin, DepthPyramid depthPyramid) {
		GpuTextureView pyramid = depthPyramid.fullView();
		Set<BlazeInstancer<?>> culled = Collections.newSetFromMap(new IdentityHashMap<>());

		ComputePipeline applyPipeline = applyPipeline();
		if (applyPipeline == null) {
			return culled;
		}

		extractPlanes(context.viewProjection());

		// Every upload first, before a pass is open. A staged upload is a copy recorded into a
		// transient command buffer of its own, and Vulkan will not have one opened inside a compute
		// pass that is still recording.
		List<BlazeInstancer<?>> ready = new ArrayList<>();
		for (BlazeInstancer<?> instancer : instancers) {
			if (cullerFor(instancer) == null) {
				continue;
			}
			if (instancer.prepareCull(planes, context, origin, depthPyramid)) {
				ready.add(instancer);
			}
		}

		if (ready.isEmpty()) {
			return culled;
		}

		try (ComputePass pass = Compute.backend()
				.beginPass("flywheel cull")) {

			for (BlazeInstancer<?> instancer : ready) {
				ComputePipeline culler = cullers.get(instancer.type);
				if (culler == null) {
					continue;
				}

				pass.setPipeline(culler);
				pass.bindStorageBuffer(0, instancer.storageSlice());
				pass.bindStorageBuffer(1, instancer.countsSlice());
				pass.bindStorageBuffer(2, instancer.visibleSlice());
				pass.bindStorageBuffer(3, instancer.cullParamsSlice());

				// The pyramid, when there is one. A frame before anything has been drawn has none,
				// and the params say so, so the shader does not read this.
				if (pyramid != null) {
					pass.bindTexture(ComputePipeline.FIRST_IMAGE_BINDING, pyramid, nearest());
				}

				pass.dispatch(MoreMath.ceilingDiv(instancer.instanceCount(), GROUP_SIZE), 1, 1);

				culled.add(instancer);
			}

			// Between the two, because apply reads the counts cull wrote. Without it apply can read
			// zero and every command says draw nothing -- which looks exactly like a culler that
			// rejected the world.
			pass.barrier(BarrierScope.STORAGE);

			pass.setPipeline(applyPipeline);

			for (BlazeInstancer<?> instancer : culled) {
				pass.bindStorageBuffer(0, instancer.commandsSlice());
				pass.bindStorageBuffer(1, instancer.drawParamsSlice());
				pass.bindStorageBuffer(2, instancer.countsSlice());
				pass.dispatch(instancer.drawCount(), 1, 1);
			}

			// INDIRECT because what reads the commands next is the draw's command fetch, and
			// TEXTURE_FETCH because the vertex shader reads the visible list as a texel buffer.
			pass.barrier(BarrierScope.STORAGE | BarrierScope.INDIRECT | BarrierScope.TEXTURE_FETCH);
		}

		return culled;
	}

	private static com.mojang.blaze3d.textures.GpuSampler nearest() {
		return RenderSystem.getSamplerCache()
				.getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST);
	}

	@Override
	public void close() {
		for (ComputePipeline pipeline : cullers.values()) {
			pipeline.close();
		}
		cullers.clear();
		failed.clear();

		if (apply != null) {
			apply.close();
			apply = null;
		}
	}

	private @Nullable ComputePipeline applyPipeline() {
		if (apply == null && !failed.containsKey(BlazeCull.class)) {
			apply = Compute.backend()
					.createPipeline(ComputePipeline.Description.of("flywheel apply", CullShaders.APPLY));

			if (apply == null) {
				FlwBackend.LOGGER.error("The apply shader would not build; nothing will be culled");
				failed.put(BlazeCull.class, true);
			}
		}
		return apply;
	}

	private @Nullable ComputePipeline cullerFor(BlazeInstancer<?> instancer) {
		Object type = instancer.type;

		if (failed.containsKey(type)) {
			return null;
		}

		ComputePipeline pipeline = cullers.get(type);
		if (pipeline != null) {
			return pipeline;
		}

		try {
			String source = CullShaders.generate(instancer.type, instancer.instanceStride());
			pipeline = Compute.backend()
					.createPipeline(ComputePipeline.Description.of("cull " + type, source));
		} catch (Exception e) {
			FlwBackend.LOGGER.error("Could not assemble a cull shader for {}", type, e);
			pipeline = null;
		}

		if (pipeline == null) {
			// Remembered, so a type whose culler will not build is not rebuilt and re-logged every
			// frame. It simply draws uncalled, which is slower and correct.
			failed.put(type, true);
			return null;
		}

		cullers.put(type, pipeline);
		return pipeline;
	}

	/**
	 * The six planes of the view frustum, in the space the draw works in.
	 *
	 * <p>Taken from the view-projection rather than from Minecraft's own frustum object, because
	 * this has to agree exactly with what the vertex shader will do with the same matrix. A culler
	 * testing against a slightly different frustum than the one being drawn is a culler that eats
	 * geometry at the edge of the screen.
	 */
	private void extractPlanes(org.joml.Matrix4fc viewProjection) {
		Matrix4f matrix = new Matrix4f(viewProjection);

		for (int i = 0; i < 6; i++) {
			matrix.frustumPlane(i, planes[i]);
		}
	}

	/** Buffers one instancer needs to be culled and drawn indirectly. */
	static final class Resources implements AutoCloseable {
		private static final int VISIBLE_USAGE = FlwBufferUsage.STORAGE
				| GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;
		private static final int COMMAND_USAGE = FlwBufferUsage.STORAGE
				| GpuBuffer.USAGE_INDIRECT_PARAMETERS | GpuBuffer.USAGE_COPY_DST;
		private static final int PLAIN_USAGE = FlwBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST;

		@Nullable GpuBuffer visible;
		@Nullable GpuBuffer counts;
		@Nullable GpuBuffer commands;
		@Nullable GpuBuffer drawParams;
		@Nullable GpuBuffer cullParams;

		long visibleCapacity;
		int drawCount;

		void ensure(int instanceCount, int draws) {
			var device = RenderSystem.getDevice();

			long needed = (long) instanceCount * Integer.BYTES;
			if (visible == null || needed > visibleCapacity) {
				if (visible != null) {
					visible.close();
				}
				visible = device.createBuffer(() -> "flywheel visible", VISIBLE_USAGE,
						Math.max(needed, 16L));
				visibleCapacity = Math.max(needed, 16L);
			}

			if (counts == null) {
				counts = device.createBuffer(() -> "flywheel visible count", PLAIN_USAGE,
						Integer.BYTES);
			}
			if (cullParams == null) {
				cullParams = device.createBuffer(() -> "flywheel cull params", PLAIN_USAGE,
						PARAMS_BYTES);
			}

			if (commands == null || draws != drawCount) {
				if (commands != null) {
					commands.close();
				}
				if (drawParams != null) {
					drawParams.close();
				}
				commands = device.createBuffer(() -> "flywheel commands", COMMAND_USAGE,
						(long) draws * COMMAND_INTS * Integer.BYTES);
				drawParams = device.createBuffer(() -> "flywheel draw params", PLAIN_USAGE,
						(long) draws * 4 * Integer.BYTES);
				drawCount = draws;
			}
		}

		@Override
		public void close() {
			for (GpuBuffer buffer : new GpuBuffer[] { visible, counts, commands, drawParams,
					cullParams }) {
				if (buffer != null) {
					buffer.close();
				}
			}
			visible = counts = commands = drawParams = cullParams = null;
			visibleCapacity = 0;
			drawCount = 0;
		}
	}

	static ByteBuffer paramsFor(Vector4f[] planes, org.joml.Vector4fc boundingSphere,
			float cameraX, float cameraY, float cameraZ, int instanceCount,
			org.joml.Matrix4fc viewProjection, float pyramidWidth, float pyramidHeight,
			float pyramidLevels) {
		ByteBuffer data = ByteBuffer.allocateDirect(PARAMS_BYTES)
				.order(ByteOrder.nativeOrder());

		for (Vector4f plane : planes) {
			data.putFloat(plane.x)
					.putFloat(plane.y)
					.putFloat(plane.z)
					.putFloat(plane.w);
		}

		data.putFloat(boundingSphere.x())
				.putFloat(boundingSphere.y())
				.putFloat(boundingSphere.z())
				.putFloat(boundingSphere.w());

		data.putFloat(cameraX)
				.putFloat(cameraY)
				.putFloat(cameraZ)
				.putFloat(0.0f);

		float[] matrix = new float[16];
		viewProjection.get(matrix);
		for (float element : matrix) {
			data.putFloat(element);
		}

		// The w component doubles as the switch: a pyramid that was never built leaves it at zero
		// and the shader skips the occlusion test entirely, rather than testing against nothing.
		data.putFloat(pyramidWidth)
				.putFloat(pyramidHeight)
				.putFloat(pyramidLevels)
				.putFloat(pyramidLevels > 0 ? 1.0f : 0.0f);

		data.putInt(instanceCount);

		return data.rewind();
	}

	static GpuBufferSlice sliceOf(@Nullable GpuBuffer buffer) {
		if (buffer == null) {
			throw new IllegalStateException("cull resources were not prepared");
		}
		return buffer.slice();
	}
}
