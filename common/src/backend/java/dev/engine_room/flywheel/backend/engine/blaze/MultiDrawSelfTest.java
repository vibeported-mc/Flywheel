package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

import dev.engine_room.flywheel.backend.compute.BarrierScope;
import dev.engine_room.flywheel.backend.compute.Compute;
import dev.engine_room.flywheel.backend.compute.ComputeBackend;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;
import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
import net.minecraft.resources.Identifier;

/**
 * Two different models, drawn in one indirect call, from commands a compute shader wrote.
 *
 * <p>Where {@link IndirectDrawSelfTest} asks whether the mechanism works at all, this asks whether
 * it works in the shape the engine actually needs: several models sharing one vertex buffer, one
 * index buffer and one instance buffer, each finding its own geometry by offset and its own
 * instances by {@code gl_DrawID}. That is the entire reason a GPU-driven renderer is faster than
 * the alternative, and every part of it is a place to be silently wrong.
 *
 * <p>The two models are drawn to opposite halves of the target in different colours, and both
 * halves are checked. One draw landing and the other not is the characteristic failure here -- a
 * non-zero {@code firstInstance}, a {@code vertexOffset} counted in bytes, a {@code gl_DrawID} that
 * does not increment -- and a test looking at one pixel, or at a sum, would call that a pass.
 */
public final class MultiDrawSelfTest {
	private static final int SIZE = 16;
	private static final long TIMEOUT_NS = 5_000_000_000L;

	private static final int COMMAND_INTS = 5;
	private static final int MODELS = 2;

	/** Instances per model. More than one, so an instance count of one cannot pass by accident. */
	private static final int INSTANCES_PER_MODEL = 3;

	private static final int LEFT_R = 200;
	private static final int LEFT_G = 40;
	private static final int LEFT_B = 40;

	private static final int RIGHT_R = 40;
	private static final int RIGHT_G = 200;
	private static final int RIGHT_B = 40;

	/**
	 * Writes both draw commands, and derives them rather than restating them.
	 *
	 * <p>Two buffers rather than one, and they are not interchangeable. {@code DrawParams} is what
	 * a command is made of -- where the geometry is and how many instances to draw -- and only
	 * compute reads it. {@code ModelData} is what a vertex shader needs to find its own instance --
	 * how many there are and where the slice begins -- and only the draw reads it. Folding them
	 * into one texel would mean one field meaning two different things depending on who read it.
	 */
	private static final String COMMAND_SHADER = """
			layout(local_size_x = 1) in;

			layout(std430, FLW_SET(0) binding = 0) writeonly buffer Commands {
				uint command[];
			};

			// x: indexCount, y: firstIndex, z: vertexOffset, w: instanceCount
			layout(std430, FLW_SET(0) binding = 1) readonly buffer DrawParams {
				uvec4 params[];
			};

			void main() {
				uint i = gl_GlobalInvocationID.x;
				uvec4 p = params[i];

				command[i * 5u + 0u] = p.x;  // indexCount
				command[i * 5u + 1u] = p.w;  // instanceCount
				command[i * 5u + 2u] = p.y;  // firstIndex
				command[i * 5u + 3u] = p.z;  // vertexOffset
				command[i * 5u + 4u] = 0u;   // firstInstance: always, on both backends
			}
			""";

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("flywheel", "pipeline/multi_probe"))
			.withVertexShader(Identifier.fromNamespaceAndPath("flywheel", "multi_probe"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("flywheel", "multi_probe"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("InstanceData", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
					.withUniform("ModelData", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
					.build())
			.withCull(false)
			.build();

	private MultiDrawSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();
		ComputeBackend gpu = Compute.backend();

		if (!gpu.available()) {
			lines.add("no compute backend, so there is nothing to write the commands with");
			return new Result(false, lines);
		}

		var device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();

		try (ProbeMeshPool pool = ProbeMeshPool.builder()
				.add(quad(-1.0f, 0.0f), order())
				.add(quad(0.0f, 1.0f), order())
				.build("flywheel multi probe");
				GpuBuffer instances = device.createBuffer(() -> "flywheel multi probe instances",
						GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, instances());
				GpuBuffer models = device.createBuffer(() -> "flywheel multi probe models",
						GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, models());
				GpuBuffer drawParams = device.createBuffer(() -> "flywheel multi probe draw params",
						FlwBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST, drawParams(pool));
				GpuBuffer commands = device.createBuffer(() -> "flywheel multi probe commands",
						FlwBufferUsage.STORAGE | GpuBuffer.USAGE_INDIRECT_PARAMETERS
								| GpuBuffer.USAGE_COPY_DST,
						(long) MODELS * COMMAND_INTS * Integer.BYTES);
				GpuTexture target = device.createTexture(() -> "flywheel multi probe target",
						GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
						GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
				GpuBuffer readback = device.createBuffer(() -> "flywheel multi probe readback",
						GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
						(long) SIZE * SIZE * 4)) {

			try (ComputePipeline pipeline = gpu.createPipeline(
					ComputePipeline.Description.of("multi probe commands", COMMAND_SHADER))) {

				if (pipeline == null) {
					lines.add("the command shader would not build");
					return new Result(false, lines);
				}

				try (ComputePass pass = gpu.beginPass("multi probe commands")) {
					pass.setPipeline(pipeline);
					pass.bindStorageBuffer(0, commands.slice());
					pass.bindStorageBuffer(1, drawParams.slice());
					pass.dispatch(MODELS, 1, 1);
					pass.barrier(BarrierScope.STORAGE | BarrierScope.INDIRECT);
				}
			}

			gpu.flush();

			try (GpuTextureView view = device.createTextureView(target);
					RenderPass pass = encoder.createRenderPass(() -> "flywheel multi probe",
							view, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))) {
				pass.setPipeline(PIPELINE);
				pass.setVertexBuffer(0, pool.vertices()
						.slice());
				pass.setIndexBuffer(pool.indices(), IndexType.SHORT);
				pass.setUniform("InstanceData", instances.slice());
				pass.setUniform("ModelData", models.slice());

				// Both models, one call. Bound once, and the GPU decides the rest.
				pass.drawIndexedIndirect(commands.slice(), MODELS);
			}

			encoder.copyTextureToBuffer(target, readback, 0, () -> {
			}, 0);

			gpu.flush();
			if (!gpu.awaitGpu(TIMEOUT_NS)) {
				lines.add("the GPU did not signal within " + (TIMEOUT_NS / 1_000_000) + "ms");
				return new Result(false, lines);
			}

			try (GpuBufferSlice.MappedView mapped = readback.map(true, false)) {
				ByteBuffer pixels = mapped.data();

				int[] left = pixelAt(pixels, SIZE / 4, SIZE / 2);
				int[] right = pixelAt(pixels, SIZE * 3 / 4, SIZE / 2);

				lines.add("left: " + left[0] + ", " + left[1] + ", " + left[2]);
				lines.add("right: " + right[0] + ", " + right[1] + ", " + right[2]);

				boolean leftOk = left[0] == LEFT_R && left[1] == LEFT_G && left[2] == LEFT_B;
				boolean rightOk = right[0] == RIGHT_R && right[1] == RIGHT_G && right[2] == RIGHT_B;

				if (leftOk && !rightOk) {
					lines.add("the first draw landed and the second did not. That is what a dropped "
							+ "indirect command looks like -- a non-zero firstInstance, or a "
							+ "gl_DrawID that does not increment, so the second draw read the "
							+ "first draw's model");
					return new Result(false, lines);
				}
				if (!leftOk && rightOk) {
					lines.add("the second draw landed and the first did not, so the commands are "
							+ "being written or consumed out of order");
					return new Result(false, lines);
				}
				if (!leftOk) {
					lines.add("neither half is right; expected left " + LEFT_R + ", " + LEFT_G + ", "
							+ LEFT_B + " and right " + RIGHT_R + ", " + RIGHT_G + ", " + RIGHT_B);
					return new Result(false, lines);
				}
			}

			lines.add("two models, " + (MODELS * INSTANCES_PER_MODEL)
					+ " instances, one indirect call, both halves correct");
		} catch (RuntimeException e) {
			lines.add("threw: " + e);
			return new Result(false, lines);
		}

		return new Result(true, lines);
	}

	private static int[] pixelAt(ByteBuffer pixels, int x, int y) {
		int at = (y * SIZE + x) * 4;
		return new int[] {
				pixels.get(at) & 0xFF,
				pixels.get(at + 1) & 0xFF,
				pixels.get(at + 2) & 0xFF,
		};
	}

	/** A quad spanning {@code x0..x1} horizontally and the whole of clip space vertically. */
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

	/**
	 * Both models' instances, end to end: the left model's three, then the right model's three.
	 *
	 * <p>Every instance of a model carries the same colour, so a draw reading the wrong slice shows
	 * up as the wrong half being the wrong colour rather than as a subtle blend.
	 */
	private static ByteBuffer instances() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(MODELS * INSTANCES_PER_MODEL * 4 * Integer.BYTES)
				.order(ByteOrder.nativeOrder());

		for (int i = 0; i < INSTANCES_PER_MODEL; i++) {
			buffer.putInt(LEFT_R)
					.putInt(LEFT_G)
					.putInt(LEFT_B)
					.putInt(255);
		}
		for (int i = 0; i < INSTANCES_PER_MODEL; i++) {
			buffer.putInt(RIGHT_R)
					.putInt(RIGHT_G)
					.putInt(RIGHT_B)
					.putInt(255);
		}

		return buffer.flip();
	}

	/** What compute needs to build a command: indexCount, firstIndex, vertexOffset, instanceCount. */
	private static ByteBuffer drawParams(ProbeMeshPool pool) {
		ByteBuffer buffer = ByteBuffer.allocateDirect(MODELS * 4 * Integer.BYTES)
				.order(ByteOrder.nativeOrder());

		for (int i = 0; i < MODELS; i++) {
			ProbeMeshPool.Mesh mesh = pool.mesh(i);
			buffer.putInt(mesh.indexCount())
					.putInt(mesh.firstIndex())
					.putInt(mesh.vertexOffset())
					.putInt(INSTANCES_PER_MODEL);
		}

		return buffer.flip();
	}

	/**
	 * What a vertex shader needs to find its instance: instanceCount, baseInstance.
	 *
	 * <p>The base is where this model's slice of the shared instance buffer begins, and it is here
	 * rather than in the command because a command may not carry it -- see the shader.
	 */
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
}
