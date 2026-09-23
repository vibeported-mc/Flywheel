package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * The list {@code 0, 1, 2, ...}, as a texel buffer, shared by everything that is not being culled.
 *
 * <p>The generated vertex shader always reads the instance it should draw out of a compacted list,
 * because the cull pass decides what survives and the CPU never learns the answer. An instancer the
 * cull pass did not run for -- its shader would not build, or culling is off -- still has to draw,
 * and this is the list that makes the same shader do it: every instance maps to itself.
 *
 * <p>One buffer for the whole backend rather than one per instancer, grown to the largest instancer
 * anyone asked for. The contents do not depend on who is asking, only on how many.
 */
public class BlazeIdentity implements AutoCloseable {
	private static final int USAGE = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;

	private @Nullable GpuBuffer buffer;
	private int capacity;

	/**
	 * A slice covering at least {@code count} entries, rewriting the buffer if it has to grow.
	 *
	 * @return null when there is nothing to draw
	 */
	public @Nullable GpuBufferSlice upTo(int count) {
		if (count <= 0) {
			return null;
		}

		if (buffer == null || count > capacity) {
			grow(count);
		}

		return buffer.slice(0, capacity * Integer.BYTES);
	}

	@Override
	public void close() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
			capacity = 0;
		}
	}

	private void grow(int count) {
		// Generously, because refilling it means writing every entry again. An instancer growing one
		// instance at a time would otherwise rewrite the whole list on every instance.
		int grown = Math.max(count + 64, (int) (count * 1.6));

		if (buffer != null) {
			buffer.close();
		}

		buffer = RenderSystem.getDevice()
				.createBuffer(() -> "flywheel identity", USAGE, (long) grown * Integer.BYTES);
		capacity = grown;

		ByteBuffer data = ByteBuffer.allocateDirect(grown * Integer.BYTES)
				.order(ByteOrder.nativeOrder());

		for (int i = 0; i < grown; i++) {
			data.putInt(i * Integer.BYTES, i);
		}

		Staging.upload(buffer.slice(), data);
	}
}
