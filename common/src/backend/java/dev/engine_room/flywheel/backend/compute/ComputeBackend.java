package dev.engine_room.flywheel.backend.compute;

import org.jspecify.annotations.Nullable;

/**
 * Compute, for one graphics backend.
 *
 * <p>Minecraft 26.2's Blaze3D has no compute of any kind, so this is the seam where each backend
 * says how it does it - and where a backend that cannot say anything reports so honestly rather
 * than failing at the first dispatch.
 *
 * <p>Consumers never pick an implementation. They ask {@link Compute#backend()}, which resolves once
 * against whichever device the game actually started with.
 */
public interface ComputeBackend {
	/** False when this device cannot run compute at all; callers must have a path for that. */
	boolean available();

	/** Short name for status output: {@code opengl}, {@code vulkan}, {@code none}. */
	String name();

	/** @return null when compute is unavailable, or the shader could not be built */
	@Nullable
	ComputePipeline createPipeline(ComputePipeline.Description description);

	/**
	 * Begin recording compute work.
	 *
	 * <p>Always use in try-with-resources: closing is what submits the work and restores whatever
	 * state the graphics backend assumes it still owns.
	 */
	ComputePass beginPass(String label);

	/**
	 * Submit whatever has been recorded but not yet sent to the GPU.
	 *
	 * <p>Nothing on OpenGL, where commands go out as they are issued. On Vulkan a compute pass is
	 * recorded into a command buffer that is queued and submitted with the rest of the frame, so
	 * work that has been "issued" has not necessarily run - which matters only to a caller that
	 * wants to read the result back in the same breath. The renderer never does; a self-test does.
	 */
	default void flush() {
	}

	/**
	 * Block until everything issued so far has finished.
	 *
	 * <p>A debugging and self-test primitive. The renderer never waits: the whole point of deciding
	 * a draw count on the GPU is that the answer stays there.
	 *
	 * @return false on timeout or failure
	 */
	boolean awaitGpu(long timeoutNanos);
}
