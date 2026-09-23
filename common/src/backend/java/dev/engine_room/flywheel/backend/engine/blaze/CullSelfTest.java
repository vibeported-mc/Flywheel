package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.joml.Vector4f;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import dev.blaze3dx.buffer.Staging;
import dev.blaze3dx.compute.BarrierScope;
import dev.blaze3dx.compute.Blaze3dxBufferUsage;
import dev.blaze3dx.compute.Compute;
import dev.blaze3dx.compute.ComputeBackend;
import dev.blaze3dx.compute.ComputePass;
import dev.blaze3dx.compute.ComputePipeline;

import net.minecraft.resources.Identifier;

/**
 * The GPU decides what to draw, compacts the survivors, and draws them -- all without the CPU.
 *
 * <p>This is the last mechanism a GPU-driven renderer needs and the one with the most ways to be
 * quietly wrong. Culling is two passes, following Flywheel's own shape: a <b>cull</b> pass tests
 * every instance and writes the survivors' indices into a packed list, claiming slots with an
 * atomic; an <b>apply</b> pass turns the resulting counts into draw commands. The vertex shader
 * then has to go through that list to find its instance, because a survivor's position in the list
 * has nothing to do with its position in the instance buffer.
 *
 * <p>The test is built so that the interesting failures cannot pass. Each model has three instances
 * in three different colours and exactly one survives -- a different one for each model. So:
 *
 * <ul>
 * <li>a cull that keeps everything draws the wrong colour, because the surviving instance is not
 * the first one;
 * <li>a compaction that writes the wrong index draws the wrong colour;
 * <li>a vertex shader that reads {@code InstanceData} with {@code gl_InstanceID} directly, skipping
 * the indirection, draws the wrong colour;
 * <li>and the counts are read back and checked besides, so "drew the right colour by luck with the
 * wrong number of instances" is caught too.
 * </ul>
 *
 * <p>Checking the counts alone would not do it: a summed total looks perfectly correct while one
 * model is missing entirely, which is exactly how a dropped indirect command presents.
 */
public final class CullSelfTest {
	private static final int SIZE = 16;
	private static final long TIMEOUT_NS = 5_000_000_000L;

	private static final int COMMAND_INTS = 5;
	private static final int MODELS = 2;
	private static final int INSTANCES_PER_MODEL = 3;
	private static final int TOTAL_INSTANCES = MODELS * INSTANCES_PER_MODEL;

	/** Which instance of each model survives. Deliberately not the first. */
	private static final int LEFT_SURVIVOR = 1;
	private static final int RIGHT_SURVIVOR = 2;

	/** Colours, one per instance, so which instance drew is visible in the picture. */
	private static final int[][] COLOURS = {
			{ 10, 10, 10 }, { 200, 40, 40 }, { 20, 20, 20 },
			{ 30, 30, 30 }, { 40, 40, 40 }, { 40, 200, 40 },
	};

	private static final String CULL_SHADER = """
			layout(local_size_x = 64) in;

			// .x model, .y visible
			layout(std430, FLW_SET(0) binding = 0) readonly buffer Meta {
				uvec4 meta[];
			};

			layout(std430, FLW_SET(0) binding = 1) buffer Counts {
				uint count[];
			};

			layout(std430, FLW_SET(0) binding = 2) writeonly buffer Visible {
				uint visible[];
			};

			void main() {
				uint i = gl_GlobalInvocationID.x;
				if (i >= INSTANCE_COUNT) {
					return;
				}

				uvec4 m = meta[i];
				if (m.y == 0u) {
					return;
				}

				// The atomic is what makes the list packed rather than sparse: every surviving
				// invocation claims the next slot, in whatever order they happen to run.
				uint slot = atomicAdd(count[m.x], 1u);
				visible[m.x * INSTANCES_PER_MODEL + slot] = i;
			}
			""";

	private static final String APPLY_SHADER = """
			layout(local_size_x = 1) in;

			layout(std430, FLW_SET(0) binding = 0) writeonly buffer Commands {
				uint command[];
			};

			// .x indexCount, .y firstIndex, .z vertexOffset
			layout(std430, FLW_SET(0) binding = 1) readonly buffer DrawParams {
				uvec4 params[];
			};

			layout(std430, FLW_SET(0) binding = 2) readonly buffer Counts {
				uint count[];
			};

			void main() {
				uint i = gl_GlobalInvocationID.x;
				uvec4 p = params[i];

				command[i * 5u + 0u] = p.x;
				// The whole point: how many to draw was not known when this frame started.
				command[i * 5u + 1u] = count[i];
				command[i * 5u + 2u] = p.y;
				command[i * 5u + 3u] = p.z;
				command[i * 5u + 4u] = 0u;
			}
			""";

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("flywheel", "pipeline/cull_probe"))
			.withVertexShader(Identifier.fromNamespaceAndPath("flywheel", "cull_probe"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("flywheel", "cull_probe"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("InstanceData", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
					.withUniform("ModelData", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
					// R32, not RGBA32. The cull pass writes a flat array of uints, so one texel has
					// to be one uint -- with a four-component format, texel n would start at byte
					// 16n while the shader wrote byte 4n, and every lookup past the first would
					// read the wrong instance.
					.withUniform("VisibleIndices", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
					.build())
			.withCull(false)
			.build();

	private CullSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();
		ComputeBackend gpu = Compute.backend();

		if (!gpu.available()) {
			lines.add("no compute backend, so nothing can be culled");
			return new Result(false, lines);
		}

		var device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();

		String defines = "#define INSTANCE_COUNT " + TOTAL_INSTANCES + "u\n"
				+ "#define INSTANCES_PER_MODEL " + INSTANCES_PER_MODEL + "u\n";

		try (ProbeMeshPool pool = ProbeMeshPool.builder()
				.add(quad(-1.0f, 0.0f), order())
				.add(quad(0.0f, 1.0f), order())
				.build("flywheel cull probe");
				GpuBuffer instances = device.createBuffer(() -> "flywheel cull probe instances",
						GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, instances());
				GpuBuffer meta = device.createBuffer(() -> "flywheel cull probe meta",
						Blaze3dxBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST, meta());
				GpuBuffer models = device.createBuffer(() -> "flywheel cull probe models",
						GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, models());
				GpuBuffer drawParams = device.createBuffer(() -> "flywheel cull probe draw params",
						Blaze3dxBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST, drawParams(pool));
				// Written by cull, read by the vertex shader. Both roles on one buffer is what the
				// whole design rests on, and the combination Blaze3D had no word for until
				// Blaze3dxBufferUsage.STORAGE was added beside its own bits.
				GpuBuffer visible = device.createBuffer(() -> "flywheel cull probe visible",
						Blaze3dxBufferUsage.STORAGE | GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER
								| GpuBuffer.USAGE_COPY_DST,
						(long) MODELS * INSTANCES_PER_MODEL * Integer.BYTES);
				GpuBuffer counts = device.createBuffer(() -> "flywheel cull probe counts",
						Blaze3dxBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC,
						(long) MODELS * Integer.BYTES);
				GpuBuffer commands = device.createBuffer(() -> "flywheel cull probe commands",
						Blaze3dxBufferUsage.STORAGE | GpuBuffer.USAGE_INDIRECT_PARAMETERS
								| GpuBuffer.USAGE_COPY_DST,
						(long) MODELS * COMMAND_INTS * Integer.BYTES);
				GpuTexture target = device.createTexture(() -> "flywheel cull probe target",
						GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
						GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
				GpuBuffer pixelReadback = device.createBuffer(() -> "flywheel cull probe pixels",
						GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) SIZE * SIZE * 4);
				GpuBuffer countReadback = device.createBuffer(() -> "flywheel cull probe counts back",
						GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
						(long) MODELS * Integer.BYTES)) {

			// Counts start at zero, because cull only ever adds to them. A frame that forgot this
			// would draw last frame's survivors as well as its own, growing every frame.
			Staging.upload(counts.slice(), zeroes(MODELS));

			try (ComputePipeline cull = gpu.createPipeline(
					ComputePipeline.Description.of("cull probe cull", defines + CULL_SHADER));
					ComputePipeline apply = gpu.createPipeline(
							ComputePipeline.Description.of("cull probe apply", APPLY_SHADER))) {

				if (cull == null || apply == null) {
					lines.add("a compute shader would not build");
					return new Result(false, lines);
				}

				try (ComputePass pass = gpu.beginPass("cull probe")) {
					pass.setPipeline(cull);
					pass.bindStorageBuffer(0, meta.slice());
					pass.bindStorageBuffer(1, counts.slice());
					pass.bindStorageBuffer(2, visible.slice());
					pass.dispatch(1, 1, 1);

					// Between the two dispatches, because apply reads what cull wrote. Without it
					// apply can read counts of zero and every command says draw nothing.
					pass.barrier(BarrierScope.STORAGE);

					pass.setPipeline(apply);
					pass.bindStorageBuffer(0, commands.slice());
					pass.bindStorageBuffer(1, drawParams.slice());
					pass.bindStorageBuffer(2, counts.slice());
					pass.dispatch(MODELS, 1, 1);

					pass.barrier(BarrierScope.STORAGE | BarrierScope.INDIRECT
							| BarrierScope.TEXTURE_FETCH);
				}
			}

			gpu.flush();

			try (GpuTextureView view = device.createTextureView(target);
					RenderPass pass = encoder.createRenderPass(() -> "flywheel cull probe",
							view, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))) {
				pass.setPipeline(PIPELINE);
				pass.setVertexBuffer(0, pool.vertices()
						.slice());
				pass.setIndexBuffer(pool.indices(), IndexType.SHORT);
				pass.setUniform("InstanceData", instances.slice());
				pass.setUniform("ModelData", models.slice());
				pass.setUniform("VisibleIndices", visible.slice());
				pass.drawIndexedIndirect(commands.slice(), MODELS);
			}

			encoder.copyTextureToBuffer(target, pixelReadback, 0, () -> {
			}, 0);
			encoder.copyToBuffer(counts.slice(), countReadback.slice());

			gpu.flush();
			if (!gpu.awaitGpu(TIMEOUT_NS)) {
				lines.add("the GPU did not signal within " + (TIMEOUT_NS / 1_000_000) + "ms");
				return new Result(false, lines);
			}

			try (GpuBufferSlice.MappedView mapped = countReadback.map(true, false)) {
				IntBuffer got = mapped.data()
						.asIntBuffer();
				int left = got.get(0);
				int right = got.get(1);

				lines.add("survivors: " + left + ", " + right);

				if (left != 1 || right != 1) {
					lines.add("expected exactly one survivor per model; the cull kept the wrong "
							+ "number, so either the visibility test or the atomic is wrong");
					return new Result(false, lines);
				}
			}

			try (GpuBufferSlice.MappedView mapped = pixelReadback.map(true, false)) {
				ByteBuffer pixels = mapped.data();

				int[] left = pixelAt(pixels, SIZE / 4, SIZE / 2);
				int[] right = pixelAt(pixels, SIZE * 3 / 4, SIZE / 2);

				lines.add("left: " + left[0] + ", " + left[1] + ", " + left[2]);
				lines.add("right: " + right[0] + ", " + right[1] + ", " + right[2]);

				if (!matches(left, COLOURS[LEFT_SURVIVOR])) {
					lines.add("the left half is not the colour of the instance that survived, so "
							+ "the compacted list is not being followed to the right instance");
					return new Result(false, lines);
				}
				if (!matches(right, COLOURS[INSTANCES_PER_MODEL + RIGHT_SURVIVOR])) {
					lines.add("the right half is not the colour of the instance that survived");
					return new Result(false, lines);
				}
			}

			lines.add("the GPU chose one instance per model and drew exactly those");
		} catch (RuntimeException e) {
			lines.add("threw: " + e);
			return new Result(false, lines);
		}

		return new Result(true, lines);
	}

	private static boolean matches(int[] pixel, int[] colour) {
		return pixel[0] == colour[0] && pixel[1] == colour[1] && pixel[2] == colour[2];
	}

	private static int[] pixelAt(ByteBuffer pixels, int x, int y) {
		int at = (y * SIZE + x) * 4;
		return new int[] {
				pixels.get(at) & 0xFF,
				pixels.get(at + 1) & 0xFF,
				pixels.get(at + 2) & 0xFF,
		};
	}

	private static float[] quad(float x0, float x1) {
		return new float[] {
				x0, -1.0f, 0.0f,
				x1, -1.0f, 0.0f,
				x1, 1.0f, 0.0f,
				x0, 1.0f, 0.0f,
		};
	}

	private static short[] order() {
		return new short[] { 0, 1, 2, 0, 2, 3 };
	}

	private static ByteBuffer instances() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(TOTAL_INSTANCES * 4 * Integer.BYTES)
				.order(ByteOrder.nativeOrder());

		for (int[] colour : COLOURS) {
			buffer.putInt(colour[0])
					.putInt(colour[1])
					.putInt(colour[2])
					.putInt(255);
		}

		return buffer.flip();
	}

	/** Per instance: which model it belongs to, and whether it survives. */
	private static ByteBuffer meta() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(TOTAL_INSTANCES * 4 * Integer.BYTES)
				.order(ByteOrder.nativeOrder());

		for (int model = 0; model < MODELS; model++) {
			int survivor = model == 0 ? LEFT_SURVIVOR : RIGHT_SURVIVOR;

			for (int i = 0; i < INSTANCES_PER_MODEL; i++) {
				buffer.putInt(model)
						.putInt(i == survivor ? 1 : 0)
						.putInt(0)
						.putInt(0);
			}
		}

		return buffer.flip();
	}

	/** Per model: how many slots its survivors may take, and where they start. */
	private static ByteBuffer models() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(MODELS * 4 * Integer.BYTES)
				.order(ByteOrder.nativeOrder());

		for (int i = 0; i < MODELS; i++) {
			buffer.putInt(INSTANCES_PER_MODEL)
					.putInt(i * INSTANCES_PER_MODEL)
					.putInt(0)
					.putInt(0);
		}

		return buffer.flip();
	}

	private static ByteBuffer drawParams(ProbeMeshPool pool) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(MODELS * 4 * Integer.BYTES)
				.order(ByteOrder.nativeOrder());

		for (int i = 0; i < MODELS; i++) {
			ProbeMeshPool.Mesh mesh = pool.mesh(i);
			buffer.putInt(mesh.indexCount())
					.putInt(mesh.firstIndex())
					.putInt(mesh.vertexOffset())
					.putInt(0);
		}

		return buffer.flip();
	}

	private static ByteBuffer zeroes(int ints) {
		return ByteBuffer.allocateDirect(ints * Integer.BYTES)
				.order(ByteOrder.nativeOrder());
	}
}
