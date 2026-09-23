package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.blaze3dx.buffer.Staging;

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
 * <h2>One buffer each, rather than offsets into one</h2>
 *
 * <p>The first version of this packed every draw's index into a single buffer and bound a slice of
 * it per draw, spaced 256 bytes apart to satisfy the worst texel buffer offset alignment Vulkan
 * permits. Blaze3D does not allow it at all: binding a uniform texel buffer throws <em>"Uniform
 * texel buffers do not support a slice of a buffer, must be entire buffer"</em>, and because the
 * engine answers an exception during a frame by disabling itself, the cost of getting this wrong was
 * every machine in the world disappearing rather than a missing overlay.
 *
 * <p>So each draw gets its own four-byte buffer, bound whole. They are pooled and reused, and there
 * are only ever as many as there are blocks being broken at once.
 */
public class BlazeCrumbling implements AutoCloseable {
	private static final int USAGE = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;

	private final List<GpuBuffer> buffers = new ArrayList<>();

	/**
	 * Writes one buffer per instance about to be drawn, before the render pass opens.
	 *
	 * @param indices the instance index each crumbling draw selects, in draw order
	 */
	public void prepare(int[] indices, int count) {
		while (buffers.size() < count) {
			buffers.add(RenderSystem.getDevice()
					.createBuffer(() -> "flywheel crumbling selection", USAGE, Integer.BYTES));
		}

		for (int i = 0; i < count; i++) {
			ByteBuffer data = ByteBuffer.allocateDirect(Integer.BYTES)
					.order(ByteOrder.nativeOrder());
			data.putInt(0, indices[i]);

			Staging.upload(buffers.get(i)
					.slice(), data);
		}
	}

	/** The one-element list for the {@code i}th crumbling draw of this frame. */
	public GpuBufferSlice slice(int i) {
		return buffers.get(i)
				.slice();
	}

	@Override
	public void close() {
		for (GpuBuffer buffer : buffers) {
			buffer.close();
		}
		buffers.clear();
	}
}
