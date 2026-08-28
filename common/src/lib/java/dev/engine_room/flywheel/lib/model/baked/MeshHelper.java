package dev.engine_room.flywheel.lib.model.baked;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.MeshData;

import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import dev.engine_room.flywheel.lib.vertex.VertexView;

final class MeshHelper {
	/**
	 * Block models are buffered through the entity format rather than the block one, because that is
	 * the format that still carries a normal and an overlay in 26.2 - the block format dropped both.
	 * Its first 35 bytes are laid out exactly as {@link FullVertexView} lays them out, whatever a mod
	 * has appended to the format beyond that.
	 */
	private static final long SHARED_PREFIX_BYTES = 35;

	private MeshHelper() {
	}

	public static SimpleQuadMesh blockVerticesToMesh(MeshData data, @Nullable String meshDescriptor) {
		MeshData.DrawState drawState = data.drawState();
		int vertexCount = drawState.vertexCount();
		long srcStride = drawState.format()
				.getVertexSize();

		VertexView vertexView = new FullVertexView();
		long dstStride = vertexView.stride();

		ByteBuffer src = data.vertexBuffer();
		MemoryBlock dst = MemoryBlock.mallocTracked((long) vertexCount * dstStride);
		long srcPtr = MemoryUtil.memAddress(src);
		long dstPtr = dst.ptr();
		long bytesToCopy = Math.min(dstStride, SHARED_PREFIX_BYTES);

		for (int i = 0; i < vertexCount; i++) {
			MemoryUtil.memCopy(srcPtr + srcStride * i, dstPtr + dstStride * i, bytesToCopy);
		}

		vertexView.ptr(dstPtr);
		vertexView.vertexCount(vertexCount);
		vertexView.nativeMemoryOwner(dst);

		return new SimpleQuadMesh(vertexView, meshDescriptor);
	}
}
