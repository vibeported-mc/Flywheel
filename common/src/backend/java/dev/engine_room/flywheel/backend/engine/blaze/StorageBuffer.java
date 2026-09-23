package dev.engine_room.flywheel.backend.engine.blaze;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
import dev.engine_room.flywheel.lib.memory.FlwMemoryTracker;

/**
 * A GPU-only buffer that can be resized, built on Blaze3D rather than on raw OpenGL.
 *
 * <p>The Blaze3D twin of {@code ResizableStorageBuffer}, and the reason the indirect renderer can
 * exist on Vulkan at all: everything here goes through {@link GpuBuffer}, which both graphics
 * backends implement, where the original holds an OpenGL buffer name that Vulkan has no equivalent
 * of.
 *
 * <p>As there, the only way data gets in or out is a GPU copy. Nothing is mapped.
 */
public class StorageBuffer implements AutoCloseable {
	/**
	 * Storage, because that is the point; both copy directions, because growing is a copy.
	 *
	 * <p>{@code COPY_SRC} and {@code COPY_DST} are needed whatever the shaders do with the buffer.
	 * The OpenGL original passes flags of {@code 0} and copies anyway, because
	 * {@code glCopyNamedBufferSubData} does not care what a buffer was declared for. Blaze3D does
	 * care, and a buffer missing either bit fails its own assertion the first time it grows.
	 *
	 * <p>{@link FlwBufferUsage#STORAGE} is Flywheel's own: Blaze3D 26.2 has no storage-buffer usage
	 * bit because it has no concept of one.
	 */
	private static final int BASE_USAGE =
			FlwBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_SRC | GpuBuffer.USAGE_COPY_DST;

	private final String label;
	private final int usage;

	private @Nullable GpuBuffer buffer;
	private long capacity = 0;

	public StorageBuffer(String label) {
		this(label, 0);
	}

	/**
	 * @param extraUsage usage bits beyond storage and copying, such as
	 *                   {@link GpuBuffer#USAGE_INDIRECT_PARAMETERS} for a buffer a compute shader
	 *                   writes and the draw path then consumes as draw commands
	 */
	public StorageBuffer(String label, int extraUsage) {
		this.label = label;
		this.usage = BASE_USAGE | extraUsage;
	}

	public long capacity() {
		return capacity;
	}

	/**
	 * The buffer as it stands, or null before anything has been reserved.
	 *
	 * <p>Callers must not hold on to this across {@link #ensureCapacity}. Growing allocates a new
	 * {@link GpuBuffer} and closes the old one, where the OpenGL version could keep one wrapper
	 * object and swap the name inside it -- so a cached reference here is a use-after-free rather
	 * than a stale integer.
	 */
	public @Nullable GpuBuffer buffer() {
		return buffer;
	}

	/** The whole buffer as a slice, for binding. */
	public GpuBufferSlice slice() {
		GpuBuffer current = buffer;
		if (current == null) {
			throw new IllegalStateException(
					"storage buffer '" + label + "' has no capacity yet; call ensureCapacity first");
		}
		return current.slice(0, (int) capacity);
	}

	public void ensureCapacity(long capacity) {
		if (capacity <= this.capacity) {
			return;
		}

		FlwMemoryTracker._freeGpuMemory(this.capacity);

		GpuBuffer grown = RenderSystem.getDevice()
				.createBuffer(() -> label, usage, capacity);

		GpuBuffer old = buffer;
		if (old != null) {
			// Contents survive a resize, which callers rely on: an arena grows with live objects in
			// it. copyToBuffer requires the two slices be the same length, so this copies the old
			// capacity rather than the new one.
			RenderSystem.getDevice()
					.createCommandEncoder()
					.copyToBuffer(old.slice(0, (int) this.capacity), grown.slice(0, (int) this.capacity));
			old.close();
		}

		this.buffer = grown;
		this.capacity = capacity;
		FlwMemoryTracker._allocGpuMemory(capacity);
	}

	@Override
	public void close() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
		}
		FlwMemoryTracker._freeGpuMemory(capacity);
		capacity = 0;
	}
}
