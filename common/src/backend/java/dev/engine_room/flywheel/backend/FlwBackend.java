package dev.engine_room.flywheel.backend;

import org.jetbrains.annotations.UnknownNullability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dev.blaze3dx.buffer.GpuMemoryListener;

import dev.engine_room.flywheel.api.Flywheel;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;

public final class FlwBackend {
	public static final Logger LOGGER = LoggerFactory.getLogger(Flywheel.ID + "/backend");
	@UnknownNullability
	private static BackendConfig config;

	private FlwBackend() {
	}

	public static BackendConfig config() {
		return config;
	}

	public static void init(BackendConfig config) {
		FlwBackend.config = config;

		// blaze3dx owns the storage buffers this backend allocates, and it counts nothing itself --
		// a library shared with another mod should not have to adopt one consumer's bookkeeping.
		// Without this its buffers are simply missing from the memory readout, which is the sort of
		// gap that gets noticed as "the numbers do not add up" long after the cause is forgotten.
		GpuMemoryListener.install(FlwMemoryTracker::_allocGpuMemory, FlwMemoryTracker::_freeGpuMemory);

		Backends.init();
	}
}
