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

import dev.engine_room.flywheel.backend.compute.Compute;
import dev.engine_room.flywheel.backend.compute.ComputeBackend;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;
import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
import net.minecraft.resources.Identifier;

/**
 * Can this device draw what a compute shader told it to draw, without the CPU ever seeing the
 * command?
 *
 * <p>That is the whole of GPU-driven rendering, and it is the one thing the rest of this backend
 * cannot be built without. Compute working and buffers growing are both necessary and neither is
 * this: the draw commands here are written on the GPU, consumed on the GPU, and the only evidence
 * that any of it was right is the picture that comes out.
 *
 * <p>It is checked by reading the picture back. A compute dispatch writes one indexed indirect
 * command; a pipeline whose vertex shader fetches its colour from a texel buffer draws a quad with
 * it into an offscreen texture; the texture is copied to a buffer and one pixel is compared against
 * the colour that was uploaded. Nothing short of the whole chain being right produces that pixel.
 *
 * <h2>Two 26.2 traps this is shaped around</h2>
 *
 * <p><b>firstInstance must be zero.</b> Blaze3D's Vulkan device never enables
 * {@code drawIndirectFirstInstance}, so a command carrying a non-zero one is invalid there and is
 * dropped without a word -- the counts read back correctly and nothing is on screen. The command
 * written below says zero, as every command this backend ever writes must.
 *
 * <p><b>An indirect draw must supply every uniform its pipeline declares.</b>
 * {@code GlCommandEncoder.executeDrawIndirect} calls {@code trySetup} with an empty dynamic-uniform
 * list, where an ordinary draw passes the ones the render system is about to supply, so a pipeline
 * built on vanilla's snippets would throw {@code Missing uniform} rather than render. This pipeline
 * declares nothing but its texel buffer and emits clip-space positions directly, which keeps the
 * test about the mechanism instead of about uniform plumbing.
 */
public final class IndirectDrawSelfTest {
	private static final int SIZE = 8;
	private static final long TIMEOUT_NS = 5_000_000_000L;

	/** One VkDrawIndexedIndirectCommand: indexCount, instanceCount, firstIndex, vertexOffset, firstInstance. */
	private static final int COMMAND_INTS = 5;

	/** Written into the texel buffer, read by the vertex shader, expected back out of the pixel. */
	private static final int R = 64;
	private static final int G = 128;
	private static final int B = 192;

	/**
	 * Writes the draw command the picture depends on.
	 *
	 * <p>On the GPU rather than from Java on purpose. Uploading the command from the CPU would
	 * exercise the indirect draw and nothing else, and the question here is whether a command the
	 * CPU never touched can drive one.
	 */
	private static final String COMMAND_SHADER = """
			layout(local_size_x = 1) in;

			layout(std430, FLW_SET(0) binding = 0) writeonly buffer Commands {
				uint command[];
			};

			void main() {
				command[0] = 6u;  // indexCount: two triangles
				command[1] = 1u;  // instanceCount
				command[2] = 0u;  // firstIndex
				command[3] = 0u;  // vertexOffset
				command[4] = 0u;  // firstInstance: never anything else, see above
			}
			""";

	private static final RenderPipeline PIPELINE = RenderPipeline.builder()
			.withLocation(Identifier.fromNamespaceAndPath("flywheel", "pipeline/indirect_probe"))
			.withVertexShader(Identifier.fromNamespaceAndPath("flywheel", "indirect_probe"))
			.withFragmentShader(Identifier.fromNamespaceAndPath("flywheel", "indirect_probe"))
			.withVertexBinding(0, DefaultVertexFormat.POSITION)
			.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
			.withBindGroupLayout(BindGroupLayout.builder()
					.withUniform("InstanceData", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
					.build())
			.withCull(false)
			.build();

	private IndirectDrawSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();
		ComputeBackend gpu = Compute.backend();

		if (!gpu.available()) {
			lines.add("no compute backend, so there is nothing to write the command with");
			return new Result(false, lines);
		}

		var device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();

		try (GpuBuffer vertices = device.createBuffer(() -> "flywheel indirect probe vertices",
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, quad());
				GpuBuffer indices = device.createBuffer(() -> "flywheel indirect probe indices",
						GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, quadIndices());
				GpuBuffer instances = device.createBuffer(() -> "flywheel indirect probe instances",
						GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST, instance());
				// Storage so compute can write it, indirect parameters so the draw can read it. The
				// same buffer in both roles is the point of the exercise.
				GpuBuffer commands = device.createBuffer(() -> "flywheel indirect probe commands",
						FlwBufferUsage.STORAGE | GpuBuffer.USAGE_INDIRECT_PARAMETERS
								| GpuBuffer.USAGE_COPY_DST,
						(long) COMMAND_INTS * Integer.BYTES);
				GpuTexture target = device.createTexture(() -> "flywheel indirect probe target",
						GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
						GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
				GpuBuffer readback = device.createBuffer(() -> "flywheel indirect probe readback",
						GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
						(long) SIZE * SIZE * 4)) {

			try (ComputePipeline pipeline = gpu.createPipeline(
					ComputePipeline.Description.of("indirect probe command", COMMAND_SHADER))) {

				if (pipeline == null) {
					lines.add("the command shader would not build");
					return new Result(false, lines);
				}

				try (ComputePass pass = gpu.beginPass("indirect probe command")) {
					pass.setPipeline(pipeline);
					pass.bindStorageBuffer(0, commands.slice());
					pass.dispatch(1, 1, 1);
					// INDIRECT, because what reads this next is the draw command fetch rather than
					// another shader. The wrong scope here is the classic way to get a draw that
					// reads the command buffer as it was before the dispatch: all zeros, so zero
					// instances, so a blank picture and no error anywhere.
					pass.barrier(dev.engine_room.flywheel.backend.compute.BarrierScope.STORAGE
							| dev.engine_room.flywheel.backend.compute.BarrierScope.INDIRECT);
				}
			}

			gpu.flush();

			try (GpuTextureView view = device.createTextureView(target);
					RenderPass pass = encoder.createRenderPass(() -> "flywheel indirect probe",
							view, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))) {
				pass.setPipeline(PIPELINE);
				pass.setVertexBuffer(0, vertices.slice());
				pass.setIndexBuffer(indices, IndexType.SHORT);
				pass.setUniform("InstanceData", instances.slice());
				pass.drawIndexedIndirect(commands.slice(), 1);
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
				int centre = ((SIZE / 2) * SIZE + SIZE / 2) * 4;

				int r = pixels.get(centre) & 0xFF;
				int g = pixels.get(centre + 1) & 0xFF;
				int b = pixels.get(centre + 2) & 0xFF;

				lines.add("centre pixel: " + r + ", " + g + ", " + b);

				if (r == 0 && g == 0 && b == 0) {
					lines.add("the quad was not drawn at all -- the clear colour survived, so either "
							+ "the command came through as zero instances or the draw never ran");
					return new Result(false, lines);
				}
				if (r != R || g != G || b != B) {
					lines.add("drawn, but with the wrong colour: expected " + R + ", " + G + ", " + B
							+ " -- the draw happened and the texel buffer did not reach the vertex "
							+ "shader intact");
					return new Result(false, lines);
				}
			}

			lines.add("a compute-written indirect draw produced the expected picture");
		} catch (RuntimeException e) {
			lines.add("threw: " + e);
			return new Result(false, lines);
		}

		return new Result(true, lines);
	}

	/** A quad over the whole of clip space, so every pixel of the target is covered. */
	private static ByteBuffer quad() {
		float[] corners = {
				-1.0f, -1.0f, 0.0f,
				1.0f, -1.0f, 0.0f,
				1.0f, 1.0f, 0.0f,
				-1.0f, 1.0f, 0.0f,
		};

		ByteBuffer buffer = ByteBuffer.allocateDirect(corners.length * Float.BYTES)
				.order(ByteOrder.nativeOrder());
		for (float f : corners) {
			buffer.putFloat(f);
		}
		return buffer.flip();
	}

	private static ByteBuffer quadIndices() {
		short[] order = { 0, 1, 2, 0, 2, 3 };

		ByteBuffer buffer = ByteBuffer.allocateDirect(order.length * Short.BYTES)
				.order(ByteOrder.nativeOrder());
		for (short s : order) {
			buffer.putShort(s);
		}
		return buffer.flip();
	}

	/** One instance, as one RGBA32_UINT texel. */
	private static ByteBuffer instance() {
		ByteBuffer buffer = ByteBuffer.allocateDirect(4 * Integer.BYTES)
				.order(ByteOrder.nativeOrder());
		buffer.putInt(R)
				.putInt(G)
				.putInt(B)
				.putInt(255);
		return buffer.flip();
	}
}
