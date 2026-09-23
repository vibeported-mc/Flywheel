package dev.engine_room.flywheel.backend.engine.blaze;

import com.mojang.blaze3d.buffers.GpuBufferSlice;

import dev.engine_room.flywheel.lib.math.MoreMath;

/**
 * A {@link StorageBuffer} that knows its element stride, and grows by more than it was asked for.
 *
 * <p>The Blaze3D twin of {@code ResizableStorageArray}, and unchanged from it in every respect that
 * is not a buffer handle: the growth factor exists so that a buffer filling up one element at a
 * time does not reallocate and GPU-copy itself on every single element.
 */
public class StorageArray implements AutoCloseable {
	private static final double DEFAULT_GROWTH_FACTOR = 1.25;

	private final StorageBuffer buffer;
	private final long stride;
	private final double growthFactor;

	private long capacity;

	public StorageArray(String label, long stride) {
		this(label, stride, DEFAULT_GROWTH_FACTOR, 0);
	}

	public StorageArray(String label, long stride, double growthFactor, int extraUsage) {
		if (stride <= 0) {
			throw new IllegalArgumentException("Stride must be positive!");
		}
		if (growthFactor <= 1) {
			throw new IllegalArgumentException("Growth factor must be greater than 1!");
		}

		this.stride = stride;
		this.growthFactor = growthFactor;
		this.buffer = new StorageBuffer(label, extraUsage);
	}

	public GpuBufferSlice slice() {
		return buffer.slice();
	}

	public StorageBuffer buffer() {
		return buffer;
	}

	public long stride() {
		return stride;
	}

	public long capacity() {
		return capacity;
	}

	public long byteCapacity() {
		return buffer.capacity();
	}

	public void ensureCapacity(long capacity) {
		if (capacity > this.capacity) {
			long grown = MoreMath.ceilLong(capacity * growthFactor);
			buffer.ensureCapacity(stride * grown);
			this.capacity = grown;
		}
	}

	@Override
	public void close() {
		buffer.close();
		capacity = 0;
	}
}
