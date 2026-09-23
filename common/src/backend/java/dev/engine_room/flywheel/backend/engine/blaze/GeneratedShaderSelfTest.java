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
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import dev.engine_room.flywheel.backend.compute.Compute;
import net.minecraft.resources.Identifier;

/**
 * Can a pipeline be built from shader source Flywheel made up at runtime?
 *
 * <p>The answer decides whether this backend can exist. Flywheel's vertex shaders are assembled per
 * instance type -- a struct derived from a layout, the code that unpacks it, and a body a mod
 * supplied -- so the set of them is a combination rather than a list, and none of them can be a
 * file. The OpenGL backend never has to care: it compiles its own programs and never asks Minecraft
 * anything. A {@code RenderPipeline} cannot do that. It names its shaders by {@link Identifier} and
 * Minecraft resolves them out of the resource packs.
 *
 * <p>So {@link GeneratedShaders} answers for them instead, through a mixin on the cache Minecraft
 * resolves with. This checks the whole arrangement end to end: source is registered under a
 * generated name, a pipeline is built on that name, and the picture it draws is read back.
 *
 * <p>Kept as small as it can be -- a constant colour, an ordinary draw, no compute and nothing
 * indirect -- because everything else here already depends on it. When this fails, it should be
 * unambiguous that the shader never arrived rather than that something drew it wrong.
 */
public final class GeneratedShaderSelfTest {
	private static final int SIZE = 8;
	private static final long TIMEOUT_NS = 5_000_000_000L;

	private static final int R = 90;
	private static final int G = 160;
	private static final int B = 220;

	private static final String VERTEX = """
			#version 460 core

			in vec3 Position;

			void main() {
				gl_Position = vec4(Position, 1.0);
			}
			""";

	/** The colour is baked in, so a fragment shader that did not arrive cannot produce it. */
	private static final String FRAGMENT = """
			#version 460 core

			out vec4 fragColor;

			void main() {
				fragColor = vec4(%f, %f, %f, 1.0);
			}
			""".formatted(R / 255.0f, G / 255.0f, B / 255.0f);

	private GeneratedShaderSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();

		var device = RenderSystem.getDevice();
		CommandEncoder encoder = device.createCommandEncoder();

		Identifier shaders = GeneratedShaders.pipeline("selftest", VERTEX, FRAGMENT);
		lines.add("registered as " + shaders);

		RenderPipeline pipeline = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("flywheel", "pipeline/generated_selftest"))
				.withVertexShader(shaders)
				.withFragmentShader(shaders)
				.withVertexBinding(0, DefaultVertexFormat.POSITION)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withCull(false)
				.build();

		try (GpuBuffer vertices = device.createBuffer(() -> "flywheel generated selftest vertices",
				GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, quad());
				GpuBuffer indices = device.createBuffer(() -> "flywheel generated selftest indices",
						GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, order());
				GpuTexture target = device.createTexture(() -> "flywheel generated selftest target",
						GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_COPY_SRC,
						GpuFormat.RGBA8_UNORM, SIZE, SIZE, 1, 1);
				GpuBuffer readback = device.createBuffer(() -> "flywheel generated selftest readback",
						GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) SIZE * SIZE * 4)) {

			try (GpuTextureView view = device.createTextureView(target);
					RenderPass pass = encoder.createRenderPass(() -> "flywheel generated selftest",
							view, Optional.of(new Vector4f(0.0f, 0.0f, 0.0f, 1.0f)))) {
				pass.setPipeline(pipeline);
				pass.setVertexBuffer(0, vertices.slice());
				pass.setIndexBuffer(indices, IndexType.SHORT);
				pass.drawIndexed(6, 1, 0, 0, 0);
			}

			encoder.copyTextureToBuffer(target, readback, 0, () -> {
			}, 0);

			Compute.backend()
					.flush();
			if (!Compute.backend()
					.awaitGpu(TIMEOUT_NS)) {
				lines.add("the GPU did not signal within " + (TIMEOUT_NS / 1_000_000) + "ms");
				return new Result(false, lines);
			}

			try (GpuBufferSlice.MappedView mapped = readback.map(true, false)) {
				ByteBuffer pixels = mapped.data();
				int at = ((SIZE / 2) * SIZE + SIZE / 2) * 4;

				int r = pixels.get(at) & 0xFF;
				int g = pixels.get(at + 1) & 0xFF;
				int b = pixels.get(at + 2) & 0xFF;

				lines.add("centre pixel: " + r + ", " + g + ", " + b);

				// One off either way: the colour makes a round trip through an 8-bit target, and
				// demanding it back exactly would fail on rounding rather than on anything real.
				if (Math.abs(r - R) > 1 || Math.abs(g - G) > 1 || Math.abs(b - B) > 1) {
					lines.add("expected " + R + ", " + G + ", " + B
							+ " -- the generated source did not reach the pipeline");
					return new Result(false, lines);
				}
			}

			lines.add("a pipeline built from runtime-generated source drew what it was told to");
		} catch (RuntimeException e) {
			lines.add("threw: " + e);
			return new Result(false, lines);
		}

		return new Result(true, lines);
	}

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

	private static ByteBuffer order() {
		short[] indices = { 0, 1, 2, 0, 2, 3 };

		ByteBuffer buffer = ByteBuffer.allocateDirect(indices.length * Short.BYTES)
				.order(ByteOrder.nativeOrder());
		for (short s : indices) {
			buffer.putShort(s);
		}
		return buffer.flip();
	}
}
