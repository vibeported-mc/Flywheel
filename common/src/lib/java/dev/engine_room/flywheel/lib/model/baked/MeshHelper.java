package dev.engine_room.flywheel.lib.model.baked;

import java.nio.ByteBuffer;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.MeshData;

import org.joml.Matrix4fc;
import org.joml.Vector3f;

import dev.engine_room.flywheel.lib.math.DataPacker;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.vertex.NoOverlayVertexView;
import dev.engine_room.flywheel.lib.vertex.VertexView;

final class MeshHelper {
	/**
	 * Position, color, texture and light, in that order, are the start of every block vertex whatever
	 * a mod has appended to the format, and are laid out exactly as {@link NoOverlayVertexView} lays
	 * them out. In Minecraft 26.2 they are also the whole of the vanilla format.
	 */
	private static final long SHARED_PREFIX_BYTES = 28;

	private static final long NORMAL_OFFSET = 28;

	private MeshHelper() {
	}

	public static SimpleQuadMesh blockVerticesToMesh(MeshData data, @Nullable Matrix4fc transform, @Nullable String meshDescriptor) {
		MeshData.DrawState drawState = data.drawState();
		int vertexCount = drawState.vertexCount();
		long srcStride = drawState.format().getVertexSize();

		VertexView vertexView = new NoOverlayVertexView();
		long dstStride = vertexView.stride();

		ByteBuffer src = data.vertexBuffer();
		MemoryBlock dst = MemoryBlock.mallocTracked((long) vertexCount * dstStride);
		long srcPtr = MemoryUtil.memAddress(src);
		long dstPtr = dst.ptr();
		long bytesToCopy = Math.min(dstStride, SHARED_PREFIX_BYTES);

		for (int i = 0; i < vertexCount; i++) {
			MemoryUtil.memCopy(srcPtr + srcStride * i, dstPtr + dstStride * i, bytesToCopy);
		}

		// 26.2's block tesselator takes a translation rather than a pose, so anything else the caller
		// asked for - the rotation that aims a partial model down its block's axis, most of all - has
		// to be applied to the finished vertices here.
		if (transform != null) {
			transformPositions(dstPtr, dstStride, vertexCount, transform);
		}

		// 26.2 dropped the normal from the block vertex format - the shading it used to drive is baked
		// into the vertex colour now - but Flywheel lights its own draws and still needs one, so it is
		// recovered from the geometry the way a face normal is derived from a quad's diagonals. Doing
		// it after the transform means the normals come out rotated with the geometry for free.
		writeFaceNormals(dstPtr, dstStride, vertexCount);

		vertexView.ptr(dstPtr);
		vertexView.vertexCount(vertexCount);
		vertexView.nativeMemoryOwner(dst);

		return new SimpleQuadMesh(vertexView, meshDescriptor);
	}

	private static void transformPositions(long ptr, long stride, int vertexCount, Matrix4fc transform) {
		Vector3f scratch = new Vector3f();

		for (int i = 0; i < vertexCount; i++) {
			long vertex = ptr + stride * i;
			scratch.set(MemoryUtil.memGetFloat(vertex), MemoryUtil.memGetFloat(vertex + 4), MemoryUtil.memGetFloat(vertex + 8));
			transform.transformPosition(scratch);
			MemoryUtil.memPutFloat(vertex, scratch.x);
			MemoryUtil.memPutFloat(vertex + 4, scratch.y);
			MemoryUtil.memPutFloat(vertex + 8, scratch.z);
		}
	}

	private static void writeFaceNormals(long ptr, long stride, int vertexCount) {
		for (int quad = 0; quad + 4 <= vertexCount; quad += 4) {
			long v0 = ptr + stride * quad;
			long v1 = v0 + stride;
			long v2 = v1 + stride;
			long v3 = v2 + stride;

			// The diagonals of the quad, whose cross product is the face normal.
			float ax = MemoryUtil.memGetFloat(v2) - MemoryUtil.memGetFloat(v0);
			float ay = MemoryUtil.memGetFloat(v2 + 4) - MemoryUtil.memGetFloat(v0 + 4);
			float az = MemoryUtil.memGetFloat(v2 + 8) - MemoryUtil.memGetFloat(v0 + 8);
			float bx = MemoryUtil.memGetFloat(v3) - MemoryUtil.memGetFloat(v1);
			float by = MemoryUtil.memGetFloat(v3 + 4) - MemoryUtil.memGetFloat(v1 + 4);
			float bz = MemoryUtil.memGetFloat(v3 + 8) - MemoryUtil.memGetFloat(v1 + 8);

			float nx = ay * bz - az * by;
			float ny = az * bx - ax * bz;
			float nz = ax * by - ay * bx;

			float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (length < 1.0E-6F) {
				// A degenerate quad has no facing to speak of. Point it at the viewer rather than
				// leaving a zero normal, which would light it as though it faced nothing at all.
				nx = 0;
				ny = 0;
				nz = 1;
			} else {
				nx /= length;
				ny /= length;
				nz /= length;
			}

			byte packedX = DataPacker.packNormI8(nx);
			byte packedY = DataPacker.packNormI8(ny);
			byte packedZ = DataPacker.packNormI8(nz);

			for (int vertex = 0; vertex < 4; vertex++) {
				long normal = ptr + stride * (quad + vertex) + NORMAL_OFFSET;
				MemoryUtil.memPutByte(normal, packedX);
				MemoryUtil.memPutByte(normal + 1, packedY);
				MemoryUtil.memPutByte(normal + 2, packedZ);
			}
		}
	}
}
