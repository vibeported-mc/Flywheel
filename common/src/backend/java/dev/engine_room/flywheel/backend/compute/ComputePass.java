package dev.engine_room.flywheel.backend.compute;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;

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

	/**
	 * Binds a texture for the shader to sample.
	 *
	 * <p>The binding must be at least {@link ComputePipeline#FIRST_IMAGE_BINDING}: a descriptor set
	 * has to say ahead of time which of its slots are buffers and which are images, so the two live
	 * in fixed ranges rather than being mixed freely.
	 *
	 * <p>This exists because a compute shader cannot otherwise read a texture at all, and the one
	 * thing that has to be read that way is the depth pyramid. Copying it into a buffer instead
	 * would be the obvious alternative and does not work: {@code copyTextureToBuffer} never delivers
	 * its data on 26.2.
	 */
	void bindTexture(int binding, GpuTextureView view, GpuSampler sampler);

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
