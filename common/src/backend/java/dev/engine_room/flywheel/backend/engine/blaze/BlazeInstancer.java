package dev.engine_room.flywheel.backend.engine.blaze;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.instance.InstanceWriter;
import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
import dev.engine_room.flywheel.backend.engine.BaseInstancer;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.lib.math.MoreMath;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;

/**
 * Every instance of one model, in one buffer the vertex shader can read.
 *
 * <p>Built on {@link BaseInstancer}'s dense array rather than the OpenGL indirect backend's paged
 * arena. The pages exist to let the GPU skip holes with a validity bitmask instead of the CPU
 * compacting them, which is worth having and is not worth having first: it is the bulk of that
 * backend's complexity and it is orthogonal to everything being proven here. A dense array with
 * compaction on the frame plan gets the same picture out.
 *
 * <p>The stride is aligned to 16 because the shader reads this as a texel buffer of
 * {@code RGBA32_UINT}, so one texel is 16 bytes. An instance whose stride is not a multiple of that
 * would start partway through a texel and every field would be read from the wrong place.
 */
public class BlazeInstancer<I extends Instance> extends BaseInstancer<I> {
	/**
	 * Storage so the cull pass can read it, texel so the vertex shader can, and copy-dst so either
	 * can be written. The combination is the point: Blaze3D has a bit for the second and the third
	 * and no word at all for the first.
	 */
	private static final int USAGE = FlwBufferUsage.STORAGE
			| GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;

	private final int instanceStride;
	private final InstanceWriter<I> writer;
	private final List<BlazeDraw> draws = new ArrayList<>();

	private @Nullable GpuBuffer buffer;
	private long capacity;

	public BlazeInstancer(InstancerKey<I> key, Recreate<I> recreate) {
		super(key, recreate);
		instanceStride = MoreMath.align16(type.layout()
				.byteSize());
		writer = type.writer();
		boundingSphere = key.model()
				.boundingSphere();
	}


	private final BlazeCull.Resources cull = new BlazeCull.Resources();

	/** The model's bounding sphere, which the cull pass transforms per instance. */
	private final org.joml.Vector4fc boundingSphere;

	/**
	 * Readies this instancer's cull buffers for the frame.
	 *
	 * @return false when there is nothing to cull, in which case nothing is dispatched for it
	 */
	public boolean prepareCull(org.joml.Vector4f[] planes, dev.engine_room.flywheel.api.backend.RenderContext context,
			net.minecraft.core.Vec3i origin) {
		int count = instanceCount();
		int draws = drawCount();

		if (count == 0 || draws == 0 || buffer == null) {
			return false;
		}

		cull.ensure(count, draws);

		// Zeroed every frame, because the cull pass only ever adds to it. A count left over from
		// last frame would draw this frame's survivors and last frame's ghosts together, growing
		// until the buffer ran out.
		Staging.upload(cull.counts.slice(), java.nio.ByteBuffer.allocateDirect(Integer.BYTES)
				.order(java.nio.ByteOrder.nativeOrder()));

		var camera = context.camera().pos;
		Staging.upload(cull.cullParams.slice(), BlazeCull.paramsFor(planes, boundingSphere,
				(float) (camera.x - origin.getX()), (float) (camera.y - origin.getY()),
				(float) (camera.z - origin.getZ()), count));

		java.nio.ByteBuffer params = java.nio.ByteBuffer.allocateDirect(draws * 4 * Integer.BYTES)
				.order(java.nio.ByteOrder.nativeOrder());
		int at = 0;
		for (BlazeDraw draw : draws()) {
			var mesh = draw.mesh();
			params.putInt(at, mesh.indexCount());
			params.putInt(at + 4, mesh.firstIndex());
			params.putInt(at + 8, mesh.baseVertex());
			params.putInt(at + 12, 0);
			at += 16;
		}
		Staging.upload(cull.drawParams.slice(), params);

		return true;
	}

	public boolean hasCullResources() {
		return cull.visible != null && cull.commands != null;
	}

	/** How many meshes this model draws, and so how many commands the apply pass writes. */
	public int drawCount() {
		return draws.size();
	}

	/** The instance data as a storage buffer, which is how the cull pass reads it. */
	public GpuBufferSlice storageSlice() {
		return BlazeCull.sliceOf(buffer);
	}

	public GpuBufferSlice visibleSlice() {
		return BlazeCull.sliceOf(cull.visible);
	}

	public GpuBufferSlice countsSlice() {
		return BlazeCull.sliceOf(cull.counts);
	}

	public GpuBufferSlice commandsSlice() {
		return BlazeCull.sliceOf(cull.commands);
	}

	/**
	 * The commands for a run of consecutive draws, which is what one indirect call consumes.
	 *
	 * <p>Consecutive in {@link #draws()} order, because that is the order the apply pass wrote them
	 * in -- the draw index is the command index, and nothing re-sorts between the two.
	 */
	public GpuBufferSlice commandsSlice(int first, int count) {
		if (cull.commands == null) {
			throw new IllegalStateException("cull resources were not prepared");
		}
		return cull.commands.slice(first * BlazeCull.COMMAND_BYTES, count * BlazeCull.COMMAND_BYTES);
	}

	public GpuBufferSlice drawParamsSlice() {
		return BlazeCull.sliceOf(cull.drawParams);
	}

	public GpuBufferSlice cullParamsSlice() {
		return BlazeCull.sliceOf(cull.cullParams);
	}

	public int instanceStride() {
		return instanceStride;
	}

	public List<BlazeDraw> draws() {
		return draws;
	}

	public void addDraw(BlazeDraw draw) {
		draws.add(draw);
	}

	public @Nullable GpuBufferSlice slice() {
		GpuBuffer current = buffer;
		if (current == null || capacity == 0) {
			return null;
		}
		return current.slice(0, (int) capacity);
	}

	/**
	 * Writes everything that changed since the last frame.
	 *
	 * <p>Render thread. Two routes, as the OpenGL original has: a buffer that has outgrown itself
	 * is rebuilt whole, and otherwise only the runs of instances that actually changed are written.
	 * Most frames are the second -- a world of spinning cogs changes every instance every frame,
	 * but a world of belts and vaults changes almost none.
	 */
	public void upload() {
		if (changed.isEmpty()) {
			return;
		}

		long needed = (long) instanceStride * instances.size();
		if (needed == 0) {
			changed.clear();
			return;
		}

		if (buffer == null || needed > capacity) {
			grow(needed);
			writeAll();
		} else {
			writeChanged();
		}

		changed.clear();
	}

	@Override
	public void delete() {
		if (buffer != null) {
			buffer.close();
			buffer = null;
			capacity = 0;
		}

		for (BlazeDraw draw : draws) {
			draw.delete();
		}
		draws.clear();
		cull.close();
	}

	private void grow(long needed) {
		// Deliberately more than asked for. An instancer filling up one instance at a time would
		// otherwise reallocate on every single one, and on Blaze3D a reallocation is a new buffer
		// object rather than a resize in place.
		long grown = Math.max(needed + (long) instanceStride * 16, (long) (needed * 1.6));

		if (buffer != null) {
			buffer.close();
		}

		buffer = RenderSystem.getDevice()
				.createBuffer(() -> "flywheel instances", USAGE, grown);
		capacity = grown;
	}

	private void writeAll() {
		MemoryBlock block = MemoryBlock.malloc((long) instanceStride * instances.size());
		try {
			long ptr = block.ptr();
			for (I instance : instances) {
				writer.write(ptr, instance);
				ptr += instanceStride;
			}

			Staging.upload(buffer.slice(0, (int) block.size()),
					MemoryUtil.memByteBuffer(block.ptr(), (int) block.size()));
		} finally {
			block.free();
		}
	}

	private void writeChanged() {
		changed.forEachSetSpan((startInclusive, endInclusive) -> {
			if (startInclusive >= instances.size()) {
				return;
			}

			int end = Math.min(endInclusive, instances.size() - 1);
			int count = end - startInclusive + 1;

			MemoryBlock block = MemoryBlock.malloc((long) instanceStride * count);
			try {
				long ptr = block.ptr();
				for (int i = startInclusive; i <= end; i++) {
					writer.write(ptr, instances.get(i));
					ptr += instanceStride;
				}

				int offset = startInclusive * instanceStride;
				Staging.upload(buffer.slice(offset, (int) block.size()),
						MemoryUtil.memByteBuffer(block.ptr(), (int) block.size()));
			} finally {
				block.free();
			}
		});
	}

	/**
	 * Compaction, ported unchanged from the OpenGL instancing backend.
	 *
	 * <p>Runs off the render thread, one thread per instancer, during the frame plan. Deleted
	 * instances leave holes; this shifts the survivors down over them and tells each moved handle
	 * its new index -- which is the part that cannot be skipped, since a handle whose index still
	 * points at where it used to be will mark the wrong instance changed forever after.
	 */
	@Override
	public void parallelUpdate() {
		if (deleted.isEmpty()) {
			return;
		}

		final int oldSize = instances.size();
		int removeCount = deleted.cardinality();

		if (oldSize == removeCount) {
			clear();
			return;
		}

		final int newSize = oldSize - removeCount;

		int writePos = deleted.nextSetBit(0);

		if (writePos < newSize) {
			// Everything from here up is about to be shifted into, so all of it is changed.
			changed.set(writePos, newSize);
		}

		// And nothing past the new end is, or the upload would read instances that no longer exist.
		changed.clear(newSize, oldSize);

		for (int scanPos = writePos; (scanPos < oldSize) && (writePos < newSize); scanPos++, writePos++) {
			scanPos = deleted.nextClearBit(scanPos);

			if (scanPos != writePos) {
				var handle = handles.get(scanPos);
				I instance = instances.get(scanPos);

				handles.set(writePos, handle);
				instances.set(writePos, instance);

				handle.index = writePos;
			}
		}

		deleted.clear();
		instances.subList(newSize, oldSize)
				.clear();
		handles.subList(newSize, oldSize)
				.clear();
	}
}
