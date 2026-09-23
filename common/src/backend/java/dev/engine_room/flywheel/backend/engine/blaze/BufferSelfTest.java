package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.backend.compute.Compute;
import dev.engine_room.flywheel.backend.compute.ComputeBackend;

/**
 * Does a {@link StorageBuffer} keep what was in it when it grows?
 *
 * <p>That one question is the whole of this, because it is the one thing the OpenGL original got
 * for free and the Blaze3D version does not. {@code glCopyNamedBufferSubData} does not care what a
 * buffer was declared for, so {@code ResizableStorageBuffer} creates its storage with usage flags
 * of {@code 0} and copies anyway. Blaze3D checks: a buffer without {@code COPY_SRC} cannot be
 * copied from, one without {@code COPY_DST} cannot be copied into, and the two slices must be the
 * same length. Get any of those wrong and an arena silently loses every object in it the first time
 * it outgrows itself -- which surfaces far away, as machines that stop being drawn once a world has
 * enough of them.
 *
 * <p>The values written are {@code i * 2 + 1} rather than {@code i}, for the same reason the
 * compute self-test uses them: a fresh buffer reads as zeros, so {@code i} would make index 0
 * indistinguishable from a copy that never happened.
 */
public final class BufferSelfTest {
	private static final int VALUES = 4096;
	private static final int BYTES = VALUES * Integer.BYTES;
	private static final long TIMEOUT_NS = 5_000_000_000L;

	private BufferSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();
		CommandEncoder encoder = RenderSystem.getDevice()
				.createCommandEncoder();

		try (StorageBuffer storage = new StorageBuffer("flywheel storage selftest")) {
			storage.ensureCapacity(BYTES);
			lines.add("allocated " + storage.capacity() + " bytes");

			ByteBuffer source = ByteBuffer.allocateDirect(BYTES)
					.order(ByteOrder.nativeOrder());
			for (int i = 0; i < VALUES; i++) {
				source.putInt(i * Integer.BYTES, i * 3 + 7);
			}
			// The direct route first, then the staged one over the top with different values, so
			// both are exercised on every machine rather than only whichever this device prefers.
			// A route that is never taken here is a route first taken on somebody else's GPU.
			Staging.uploadDirect(storage.slice(), source);

			for (int i = 0; i < VALUES; i++) {
				source.putInt(i * Integer.BYTES, i * 2 + 1);
			}
			Staging.uploadStaged(storage.slice(), source);

			// The point of the exercise. Everything written above has to still be there afterwards,
			// in a buffer object that is not the one it was written to.
			storage.ensureCapacity(BYTES * 2L);
			lines.add("grown to " + storage.capacity() + " bytes");

			int readUsage = GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST;
			try (GpuBuffer readback = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel storage selftest readback", readUsage, BYTES)) {

				GpuBuffer grown = storage.buffer();
				if (grown == null) {
					lines.add("the buffer reported no allocation after growing");
					return new Result(false, lines);
				}

				// Equal lengths, which copyToBuffer requires: the readback is the ORIGINAL size,
				// not the grown one, so this reads back exactly the region that was written.
				encoder.copyToBuffer(grown.slice(0, BYTES), readback.slice(0, BYTES));

				// Waited for, so the mapping below reads what the copy wrote rather than whatever
				// was in that memory beforehand. A readable buffer on 26.2 is already persistently
				// mapped, so reading one synchronises nothing by itself.
				//
				// Not through CommandEncoder.createFence(). Blaze3D's fences are submit-granular --
				// GlCommandEncoder keeps two slots and indexes them by submit -- so asking one
				// about work issued in the frame currently being recorded throws outright:
				// "Cannot wait on a fence for the current submit". There is no way to fence a point
				// inside a frame, which is worth knowing well beyond this test: it is why the
				// staging ring cannot reclaim its space the way the OpenGL one does.
				ComputeBackend gpu = Compute.backend();
				gpu.flush();
				if (!gpu.awaitGpu(TIMEOUT_NS)) {
					lines.add("the GPU did not signal within " + (TIMEOUT_NS / 1_000_000) + "ms");
					return new Result(false, lines);
				}

				try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
					IntBuffer values = view.data()
							.asIntBuffer();
					for (int i = 0; i < VALUES; i++) {
						int expected = i * 2 + 1;
						int actual = values.get(i);
						if (actual != expected) {
							lines.add("MISMATCH at " + i + ": expected " + expected + ", got " + actual
									+ " -- the contents did not survive the resize");
							return new Result(false, lines);
						}
					}
				}

				lines.add("readback: all " + VALUES + " values survived the resize");
			}
		} catch (RuntimeException e) {
			lines.add("threw: " + e);
			return new Result(false, lines);
		}

		return new Result(true, lines);
	}
}
