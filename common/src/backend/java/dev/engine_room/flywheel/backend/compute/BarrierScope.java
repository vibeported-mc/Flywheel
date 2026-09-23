package dev.engine_room.flywheel.backend.compute;

/**
 * What a memory barrier has to make visible.
 *
 * <p>Blaze3D 26.2 has no barrier concept at all - there is not one {@code glMemoryBarrier} in the
 * OpenGL backend, and the Vulkan backend's only barrier covers transfer-to-transfer. So every
 * barrier in a compute pipeline is ours to place, and placing one too few is the classic way to get
 * a renderer that works on one vendor's driver and not another's.
 *
 * <p>These are a mask: OR them together. They name the reads that must observe the writes a
 * dispatch just made, not the writes themselves.
 */
public final class BarrierScope {
	/** A later shader will read these writes through a storage buffer. */
	public static final int STORAGE = 1;
	/** A later draw will read these writes as index data. */
	public static final int INDEX = 1 << 1;
	/** A later draw will read these writes as its indirect draw parameters. */
	public static final int INDIRECT = 1 << 2;
	/** A later draw will read these writes as vertex attributes. */
	public static final int VERTEX_ATTRIB = 1 << 3;
	/** A later shader will sample these writes as a texture. */
	public static final int TEXTURE_FETCH = 1 << 4;
	/** The CPU will read these writes back through {@code glGetBufferSubData} or a fresh mapping. */
	public static final int BUFFER_UPDATE = 1 << 5;
	/**
	 * The CPU will read these writes back through a <b>persistent</b> mapping.
	 *
	 * <p>This is not the same scope as {@link #BUFFER_UPDATE}, and on Minecraft 26.2 it is the one
	 * you almost always want. {@code GlBuffer.Direct} persistently maps every buffer created with
	 * {@code USAGE_MAP_READ} or {@code USAGE_MAP_WRITE} at construction time, so a buffer you can
	 * read is a buffer that is already mapped. Using {@code BUFFER_UPDATE} for it silently reads
	 * stale memory - the readback comes back as whatever the buffer held before, which for a fresh
	 * allocation is zeros, and looks exactly like a dispatch that never ran.
	 *
	 * <p>The barrier alone is still not enough: a persistent mapping performs no synchronisation, so
	 * the CPU must also wait on a fence before trusting what it reads.
	 */
	public static final int CLIENT_MAPPED_BUFFER = 1 << 6;

	/** Everything. Correct, never fast; reach for a narrower scope once a pass works. */
	public static final int ALL = STORAGE | INDEX | INDIRECT | VERTEX_ATTRIB | TEXTURE_FETCH
			| BUFFER_UPDATE | CLIENT_MAPPED_BUFFER;

	private BarrierScope() {
	}
}
