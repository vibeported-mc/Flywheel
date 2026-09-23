package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.backend.engine.LightStorage;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Flywheel's light volume, on the GPU.
 *
 * <p>Minecraft's own lighting reaches a model through its vertices, which is fine for geometry that
 * never moves and wrong for everything this backend draws: a shaft that turns, a belt that scrolls
 * and a contraption that flies all have to be lit by where they <em>are</em>, not by where the
 * block they came from sat when the chunk was meshed.
 *
 * <p>So Flywheel keeps its own copy of the light in the sections around the camera, and a shader
 * looks its own position up in it. Some visuals rely on that entirely -- Create's track implements
 * {@code ShaderLightVisual} and never sets a per-instance light at all, which is why curved track
 * renders black without this.
 *
 * <p>Two buffers: the sections themselves, and a lookup table that turns a section coordinate into
 * an index into them. Both are texel buffers, for the same reason instance data is -- Blaze3D can
 * describe one to a draw and cannot describe a storage buffer at all.
 *
 * <p>The whole arena is re-uploaded whenever the light changed, rather than only the sections that
 * did. That is more traffic than the OpenGL backend's scatter, and it is the honest starting point:
 * the scatter exists because it was measured to be worth it, and measuring it here needs this to
 * work first.
 */
public class BlazeLight implements AutoCloseable {
	private static final int USAGE = GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_COPY_DST;

	private @Nullable GpuBuffer sections;
	private @Nullable GpuBuffer lut;
	private @Nullable GpuBuffer fallback;

	private long sectionsCapacity;
	private long lutCapacity;

	public void flush(LightStorage light) {
		int capacity = light.capacity();
		if (capacity == 0) {
			return;
		}

		uploadSections(light, capacity);

		if (light.checkNeedsLutRebuildAndClear() || lut == null) {
			uploadLut(light.createLut());
		}
	}

	/**
	 * Always a slice, even before there is any light to look up.
	 *
	 * <p>There is no such thing as skipping the binding. 26.2 binds what a pipeline declared and
	 * nothing else, and a declared-but-unbound texel buffer is not an unlit draw -- it reads as zero
	 * and the draw is discarded. An early version of this returned null before the first light
	 * arrived and every instanced model in the world disappeared, because a world with no
	 * light-using visual in it yet still has machines to draw.
	 *
	 * <p>So an empty volume is a small buffer of zeros rather than nothing. The lookup misses
	 * against it and reports the miss, which is exactly what it is for.
	 */
	public GpuBufferSlice sections() {
		return sections != null ? sections.slice() : empty();
	}

	public GpuBufferSlice lut() {
		return lut != null ? lut.slice() : empty();
	}

	@Override
	public void close() {
		if (sections != null) {
			sections.close();
			sections = null;
		}
		if (lut != null) {
			lut.close();
			lut = null;
		}
		if (fallback != null) {
			fallback.close();
			fallback = null;
		}
		sectionsCapacity = 0;
		lutCapacity = 0;
	}

	/**
	 * A few zeros, shared, for when there is no volume yet.
	 *
	 * <p>Sixteen rather than one so a lookup that reads a couple of words past its first cannot run
	 * off the end of the allocation before it has decided it missed.
	 */
	private GpuBufferSlice empty() {
		if (fallback == null) {
			fallback = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel light fallback", USAGE, 16L * Integer.BYTES);
			Staging.upload(fallback.slice(),
					ByteBuffer.allocateDirect(16 * Integer.BYTES)
							.order(ByteOrder.nativeOrder()));
		}
		return fallback.slice();
	}

	private void uploadSections(LightStorage light, int capacity) {
		long needed = (long) capacity * LightStorage.SECTION_SIZE_BYTES;

		if (sections == null || needed > sectionsCapacity) {
			if (sections != null) {
				sections.close();
			}
			sections = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel light sections", USAGE, needed);
			sectionsCapacity = needed;
		}

		// The arena is one contiguous block of section records, so it crosses in one write.
		long bytes = Math.min(needed, light.arena.byteCapacity());
		Staging.upload(sections.slice(0, (int) bytes),
				MemoryUtil.memByteBuffer(light.arena.indexToPointer(0), (int) bytes));
	}

	private void uploadLut(IntArrayList table) {
		long needed = (long) table.size() * Integer.BYTES;
		if (needed == 0) {
			return;
		}

		if (lut == null || needed > lutCapacity) {
			if (lut != null) {
				lut.close();
			}
			lut = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel light lut", USAGE, needed);
			lutCapacity = needed;
		}

		ByteBuffer data = ByteBuffer.allocateDirect((int) needed)
				.order(ByteOrder.nativeOrder());
		for (int i = 0; i < table.size(); i++) {
			data.putInt(i * Integer.BYTES, table.getInt(i));
		}

		Staging.upload(lut.slice(0, (int) needed), data);
	}
}
