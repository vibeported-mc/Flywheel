package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Which single instance a crumbling draw is for.
 *
 * <p>The ordinary path answers this with the list the cull pass compacted. A crumbling draw is one
 * instance -- the block a player is breaking -- so it needs a list of one, and the obvious way to
 * say that is {@code firstInstance}. On Vulkan that does not work: Blaze3D's device never enables
 * {@code drawIndirectFirstInstance}, and a non-zero one is dropped without a word. So the selection
 * goes where selection already goes, through {@code _flw_visible}, and the shader is the same one
 * either way.
 *
 * <h2>Why the entries are so far apart</h2>
 *
 * <p>Each entry is one {@code uint} and the next begins 256 bytes later, because the binding is a
 * uniform texel buffer and a texel buffer's offset has to satisfy
 * {@code minTexelBufferOffsetAlignment}. The Vulkan specification allows an implementation to
 * require as much as 256, so 256 it is: packing them four bytes apart works on the card this was
 * written on and fails on somebody else's, which is the worst way for it to fail.
 */
public class BlazeCrumbling implements AutoCloseable {
	/** The largest alignment a Vulkan implementation may require of a texel buffer offset. */
	private static final int STRIDE = 256;

	private static final int USAGE = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;

	private @Nullable GpuBuffer buffer;
	private int capacity;

	/**
	 * Writes one entry per instance about to be drawn, in one upload before the render pass opens.
	 *
	 * @param indices the instance index each crumbling draw selects, in draw order
	 */
	public void prepare(int[] indices, int count) {
		if (count == 0) {
			return;
		}

		if (buffer == null || count > capacity) {
			if (buffer != null) {
				buffer.close();
			}
			buffer = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel crumbling selection", USAGE,
							(long) count * STRIDE);
			capacity = count;
		}

		ByteBuffer data = ByteBuffer.allocateDirect(count * STRIDE)
				.order(ByteOrder.nativeOrder());

		for (int i = 0; i < count; i++) {
			data.putInt(i * STRIDE, indices[i]);
		}

		Staging.upload(buffer.slice(), data);
	}

	/** The one-element list for the {@code i}th crumbling draw of this frame. */
	public GpuBufferSlice slice(int i) {
		if (buffer == null) {
			throw new IllegalStateException("crumbling selection was not prepared");
		}
		return buffer.slice(i * STRIDE, Integer.BYTES);
	}

	@Override
	public void close() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
			capacity = 0;
		}
	}
}
