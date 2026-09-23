package dev.engine_room.flywheel.backend.compute.gl;

import org.jspecify.annotations.Nullable;

import dev.engine_room.flywheel.backend.compute.ComputeBackend;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;

/** Compute on OpenGL, which is the case Blaze3D can almost support already. */
public final class GlComputeBackend implements ComputeBackend {
	@Override
	public boolean available() {
		return GlCompute.available();
	}

	@Override
	public String name() {
		return "opengl";
	}

	@Override
	public @Nullable ComputePipeline createPipeline(ComputePipeline.Description description) {
		return GlCompute.createPipeline(description);
	}

	@Override
	public ComputePass beginPass(String label) {
		return GlCompute.beginPass(label);
	}

	@Override
	public boolean awaitGpu(long timeoutNanos) {
		return GlCompute.awaitGpu(timeoutNanos);
	}
}
