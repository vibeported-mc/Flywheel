package dev.engine_room.flywheel.backend.compute;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.opengl.GlDevice;
import com.mojang.blaze3d.vulkan.VulkanDevice;

import dev.engine_room.flywheel.backend.compute.gl.GlComputeBackend;
import dev.engine_room.flywheel.backend.compute.vk.VkComputeBackend;

/**
 * Which compute backend is in play, decided once against the live device.
 *
 * <p>Resolved from the device rather than probed by trial: asking OpenGL questions on a Vulkan
 * device throws, and asking Vulkan questions on an OpenGL device is worse. {@link Blaze3dx} already
 * has to reach the concrete backend for other reasons, so it can simply be asked what it is.
 */
public final class Compute {
	private static boolean resolved;
	private static @Nullable ComputeBackend backend;

	private Compute() {
	}

	public static ComputeBackend backend() {
		if (!resolved) {
			resolved = true;
			backend = resolve();
		}
		return backend == null ? NoCompute.INSTANCE : backend;
	}

	/** Convenience: the common question, without a null check at every call site. */
	public static boolean available() {
		return backend().available();
	}

	private static @Nullable ComputeBackend resolve() {
		Object concrete = Blaze3dx.backend();
		if (concrete instanceof GlDevice) {
			return new GlComputeBackend();
		}
		if (concrete instanceof VulkanDevice vulkan) {
			return VkComputeBackend.createIfSupported(vulkan);
		}
		return null;
	}

	/** For a device that cannot compute. Every method is a truthful no. */
	private static final class NoCompute implements ComputeBackend {
		static final NoCompute INSTANCE = new NoCompute();

		@Override
		public boolean available() {
			return false;
		}

		@Override
		public String name() {
			return "none";
		}

		@Override
		public @Nullable ComputePipeline createPipeline(ComputePipeline.Description description) {
			return null;
		}

		@Override
		public ComputePass beginPass(String label) {
			throw new UnsupportedOperationException("no compute backend; check available() first");
		}

		@Override
		public boolean awaitGpu(long timeoutNanos) {
			return true;
		}
	}
}
