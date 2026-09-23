package dev.engine_room.flywheel.backend.compute;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

/**
 * A scope in which compute work is recorded.
 *
 * <p><b>Always use this in try-with-resources.</b> On OpenGL, {@link #close()} is what puts back the
 * state Blaze3D believes it left bound - in particular the program it caches and skips rebinding.
 * Leaking a pass does not leak memory; it makes the next draw call render with a compute program
 * bound, which looks like geometry silently vanishing rather than like an error.
 */
public interface ComputePass extends AutoCloseable {
	void setPipeline(ComputePipeline pipeline);

	/**
	 * Bind a buffer range to a {@code layout(std430, binding = N)} block.
	 *
	 * <p>Storage buffer binding points are a namespace of their own, disjoint from the uniform
	 * buffer binding points Blaze3D hands out, so these indices cannot collide with vanilla's.
	 */
	void bindStorageBuffer(int binding, GpuBufferSlice slice);

	/** Launch {@code x * y * z} workgroups. */
	void dispatch(int x, int y, int z);

	/**
	 * Make this pass's writes visible to the reads named by {@code scopes}.
	 *
	 * @param scopes a mask of {@link BarrierScope} values
	 */
	void barrier(int scopes);

	@Override
	void close();
}
