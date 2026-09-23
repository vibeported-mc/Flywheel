package dev.engine_room.flywheel.backend.compute;

/**
 * What the current graphics device can actually do with compute.
 *
 * <p>Minecraft 26.2's {@code DeviceFeatures} and {@code DeviceLimits} are records, so there is
 * nowhere to add a compute flag or a storage-buffer alignment to them. This is the side table that
 * carries what they cannot.
 *
 * <p>A {@code null} {@code ComputeCaps} anywhere in this codebase means "this device has no compute
 * at all" - not "not probed yet". Callers branch on that and fall back.
 */
public record ComputeCaps(
		/** Largest {@code local_size_x * local_size_y * local_size_z} a single workgroup may declare. */
		int maxWorkGroupInvocations,
		/** Per-axis limit on the number of workgroups one dispatch may launch. */
		Extent maxWorkGroupCount,
		/** Per-axis limit on a single workgroup's local size. */
		Extent maxWorkGroupSize,
		/** Required alignment for a storage buffer binding's offset, in bytes. */
		int storageBufferOffsetAlignment,
		/**
		 * Threads that execute in lockstep on this hardware.
		 *
		 * <p>Probed rather than assumed: it decides the workgroup size of passes that do one item
		 * per invocation, and guessing it wrong costs occupancy on every dispatch.
		 */
		int subgroupSize,
		/** How many storage buffers may be bound to one compute stage at once. */
		int maxStorageBufferBindings,
		/** Bytes of {@code shared} memory one workgroup may declare. */
		int maxSharedMemoryBytes) {

	public record Extent(int x, int y, int z) {
		@Override
		public String toString() {
			return x + "x" + y + "x" + z;
		}
	}

	@Override
	public String toString() {
		return "invocations=" + maxWorkGroupInvocations
				+ " count=" + maxWorkGroupCount
				+ " size=" + maxWorkGroupSize
				+ " ssboAlign=" + storageBufferOffsetAlignment
				+ " subgroup=" + subgroupSize
				+ " ssboBindings=" + maxStorageBufferBindings
				+ " shared=" + maxSharedMemoryBytes + "B";
	}
}
