package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Getting bytes from here to the GPU, by whichever route this device is faster at.
 *
 * <p>Flywheel's OpenGL staging buffer is a 16 MB persistently-mapped ring that accumulates a
 * frame's uploads and reclaims its space with fences. That design does not survive the move to
 * Blaze3D, and not for want of trying: {@code CommandEncoder.createFence()} is submit-granular --
 * {@code GlCommandEncoder} keeps two slots and indexes them by submit -- and asking one about work
 * issued in the frame being recorded throws {@code Cannot wait on a fence for the current submit}.
 * There is no fencing a point inside a frame, so there is no reclaiming ring space inside one
 * either.
 *
 * <p>Blaze3D already solves that, and better: {@link CommandEncoder#transientMemory()} is a
 * persistently-mapped ring that rotates per submit, so the space bookkeeping belongs to the game
 * rather than to us. What is left for this class is the choice of route.
 */
public final class Staging {
	private Staging() {
	}

	/**
	 * Writes {@code data} into {@code destination}.
	 *
	 * <p>Two routes, and the device is asked which to take.
	 * {@code HintsAndWorkarounds.writeToBufferIsSlow} exists because on some drivers a direct write
	 * is a stall, and Blaze3D reports it rather than guessing -- so where it says so, the bytes go
	 * through the transient ring and reach the destination as a GPU copy instead.
	 *
	 * <p>The destination needs {@code COPY_DST} either way; on the staged route it is a real buffer
	 * copy, and on the direct one {@code writeToBuffer} requires the same bit.
	 */
	public static void upload(GpuBufferSlice destination, ByteBuffer data) {
		if (writeToBufferIsSlow()) {
			uploadStaged(destination, data);
		} else {
			uploadDirect(destination, data);
		}
	}

	/** Straight at the buffer. */
	public static void uploadDirect(GpuBufferSlice destination, ByteBuffer data) {
		RenderSystem.getDevice()
				.createCommandEncoder()
				.writeToBuffer(destination, data);
	}

	/**
	 * Through the transient ring, arriving as a GPU copy.
	 *
	 * <p>Exposed separately from {@link #upload} rather than hidden behind the hint, so that both
	 * routes can be tested on any machine. Left to the hint alone, whichever one this device does
	 * not prefer would never run here and would first be exercised on somebody else's.
	 */
	public static void uploadStaged(GpuBufferSlice destination, ByteBuffer data) {
		CommandEncoder encoder = RenderSystem.getDevice()
				.createCommandEncoder();

		// uploadStaging copies into the ring and hands back where it landed, so the only work left
		// is moving it across -- and copyToBuffer wants both slices the same length.
		GpuBufferSlice staged = encoder.transientMemory()
				.uploadStaging(data, 1L, GpuBuffer.USAGE_COPY_SRC);

		encoder.copyToBuffer(staged, destination);
	}

	private static boolean writeToBufferIsSlow() {
		return RenderSystem.getDevice()
				.getDeviceInfo()
				.hintsAndWorkarounds()
				.writeToBufferIsSlow();
	}
}
