package dev.engine_room.flywheel.backend.compute;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Does compute actually work on this machine, whichever backend it started with?
 *
 * <p>The same test for OpenGL and Vulkan, because the whole point of the layer beneath it is that
 * there is only one way to ask. It compiles a shader, dispatches it, waits, and reads the answer
 * back: if any of that is going to fail, better on the first frame than three phases later.
 */
public final class ComputeSelfTest {
	/** Enough invocations to span several workgroups, so a dispatch-size bug cannot hide. */
	private static final int VALUES = 1024;
	private static final int LOCAL_SIZE = 64;
	private static final long TIMEOUT_NS = 5_000_000_000L;

	/**
	 * Writes {@code i * 2 + 1} rather than {@code i}.
	 *
	 * <p>Deliberate: a freshly allocated buffer reads as zeros, and {@code i} would make index 0
	 * indistinguishable from "the shader never ran". Every expected value here is non-zero and
	 * every one differs from its index.
	 */
	private static final String SHADER = """
			layout(local_size_x = 64) in;

			layout(std430, IB_SET(0) binding = 0) writeonly buffer Output {
				uint values[];
			};

			void main() {
				uint i = gl_GlobalInvocationID.x;
				values[i] = i * 2u + 1u;
			}
			""";

	private ComputeSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();
		ComputeBackend backend = Compute.backend();

		lines.add("backend: " + backend.name());
		if (!backend.available()) {
			lines.add("this device has no compute; the renderer will fall back");
			return new Result(false, lines);
		}
		lines.add("storage usage bit: " + FlwBufferUsage.STORAGE);

		try (ComputePipeline pipeline = backend.createPipeline(
				ComputePipeline.Description.of("selftest", SHADER))) {

			if (pipeline == null) {
				lines.add("the shader would not build");
				return new Result(false, lines);
			}

			// Readable so the result can be checked, and storage so the shader can write it.
			int usage = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST | FlwBufferUsage.STORAGE;
			try (GpuBuffer buffer = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel compute selftest", usage, (long) VALUES * Integer.BYTES)) {

				try (ComputePass pass = backend.beginPass("selftest")) {
					pass.setPipeline(pipeline);
					pass.bindStorageBuffer(0, buffer.slice());
					pass.dispatch(VALUES / LOCAL_SIZE, 1, 1);
					// CLIENT_MAPPED_BUFFER, not BUFFER_UPDATE: a readable buffer on 26.2 is already
					// persistently mapped, and the wrong scope reads stale memory in silence.
					pass.barrier(BarrierScope.STORAGE | BarrierScope.CLIENT_MAPPED_BUFFER);
				}

				// The barrier orders the writes; it does not say they have landed, and on Vulkan
				// it does not even say they have been submitted.
				backend.flush();
				if (!backend.awaitGpu(TIMEOUT_NS)) {
					lines.add("the GPU did not signal within " + (TIMEOUT_NS / 1_000_000) + "ms");
					return new Result(false, lines);
				}

				try (GpuBufferSlice.MappedView view = buffer.map(true, false)) {
					IntBuffer values = view.data().asIntBuffer();
					for (int i = 0; i < VALUES; i++) {
						int expected = i * 2 + 1;
						int actual = values.get(i);
						if (actual != expected) {
							lines.add("MISMATCH at " + i + ": expected " + expected + ", got " + actual);
							return new Result(false, lines);
						}
					}
				}

				lines.add("readback: all " + VALUES + " values correct");
			}
		} catch (RuntimeException e) {
			lines.add("threw: " + e);
			return new Result(false, lines);
		}

		return new Result(true, lines);
	}
}
