package dev.engine_room.flywheel.backend.engine.blaze;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.api.model.IndexSequence;
import dev.engine_room.flywheel.api.model.Mesh;
import dev.engine_room.flywheel.backend.InternalVertex;
import dev.engine_room.flywheel.backend.util.ReferenceCounted;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.vertex.VertexView;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;

/**
 * Every mesh in the level, in one vertex buffer and one index buffer.
 *
 * <p>The Blaze3D twin of the OpenGL {@code MeshPool}, and the same idea for the same reason: an
 * indirect command names its geometry by offset rather than by which buffer is bound, so pooling
 * everything into one pair of buffers is what lets a single call draw models that have nothing to
 * do with each other.
 *
 * <p>Indices are pooled by {@link IndexSequence} <b>identity</b> rather than per mesh, which is not
 * a micro-optimisation: nearly every mesh in Create is quads, and quads all share one sequence
 * object. Pooling by identity means one copy of it serves thousands of meshes.
 *
 * <p>Where this differs from the OpenGL original is resizing. A {@code GpuBuffer} has a fixed size,
 * so growing means allocating a new one and dropping the old -- there is no {@code glBufferData} to
 * re-specify storage in place. Both buffers are therefore rebuilt whole on any flush that changed
 * anything, which is the same amount of uploading the original does and simply more honest about
 * the allocation.
 */
public class BlazeMeshPool implements AutoCloseable {
	private final VertexView vertexView = InternalVertex.createVertexView();

	private final Map<Mesh, PooledMesh> meshes = new HashMap<>();
	private final List<PooledMesh> meshList = new ArrayList<>();
	private final List<PooledMesh> recentlyAllocated = new ArrayList<>();

	private final Reference2IntMap<IndexSequence> indexCounts = new Reference2IntOpenHashMap<>();
	private final Reference2IntMap<IndexSequence> firstIndices = new Reference2IntOpenHashMap<>();

	private @Nullable GpuBuffer vertices;
	private @Nullable GpuBuffer indices;

	private boolean dirty;
	private boolean anyToRemove;
	private boolean indicesDirty;

	public BlazeMeshPool() {
		indexCounts.defaultReturnValue(0);
	}

	/** Off the render thread, from instancer creation. Allocates nothing on the GPU. */
	public PooledMesh alloc(Mesh mesh) {
		return meshes.computeIfAbsent(mesh, this::allocInner);
	}

	public @Nullable PooledMesh get(Mesh mesh) {
		return meshes.get(mesh);
	}

	public @Nullable GpuBuffer vertices() {
		return vertices;
	}

	public @Nullable GpuBuffer indices() {
		return indices;
	}

	public boolean isEmpty() {
		return vertices == null || indices == null;
	}

	/** Render thread, once a frame. */
	public void flush() {
		if (!dirty) {
			return;
		}
		dirty = false;

		if (anyToRemove) {
			anyToRemove = false;
			meshList.removeIf(pooled -> {
				boolean deleted = pooled.isDeleted();
				if (deleted) {
					meshes.remove(pooled.mesh);
				}
				return deleted;
			});
		}

		if (!recentlyAllocated.isEmpty()) {
			for (PooledMesh mesh : recentlyAllocated) {
				updateIndexCount(mesh.mesh.indexSequence(), mesh.indexCount());
			}
			recentlyAllocated.clear();
		}

		uploadIndices();
		uploadVertices();
	}

	@Override
	public void close() {
		if (vertices != null) {
			vertices.close();
			vertices = null;
		}
		if (indices != null) {
			indices.close();
			indices = null;
		}
		meshes.clear();
		meshList.clear();
		indexCounts.clear();
		firstIndices.clear();
	}

	private PooledMesh allocInner(Mesh mesh) {
		PooledMesh pooled = new PooledMesh(mesh);
		meshList.add(pooled);
		recentlyAllocated.add(pooled);
		dirty = true;
		return pooled;
	}

	private void updateIndexCount(IndexSequence sequence, int indexCount) {
		int old = indexCounts.getInt(sequence);
		if (indexCount > old) {
			indexCounts.put(sequence, indexCount);
			indicesDirty = true;
		}
	}

	private void uploadIndices() {
		if (!indicesDirty) {
			return;
		}
		indicesDirty = false;
		firstIndices.clear();

		long total = 0;
		for (int count : indexCounts.values()) {
			total += count;
		}
		if (total == 0) {
			return;
		}

		MemoryBlock block = MemoryBlock.malloc(total * Integer.BYTES);
		try {
			int firstIndex = 0;
			for (Reference2IntMap.Entry<IndexSequence> entry : indexCounts.reference2IntEntrySet()) {
				IndexSequence sequence = entry.getKey();
				int count = entry.getIntValue();

				firstIndices.put(sequence, firstIndex);
				// Mesh-local indices, counted from zero. The draw command's vertexOffset rebases
				// them, which is what lets one sequence serve every mesh that shares its shape.
				sequence.fill(block.ptr() + (long) firstIndex * Integer.BYTES, count);
				firstIndex += count;
			}

			replaceIndices(block, total * Integer.BYTES);
		} finally {
			block.free();
		}
	}

	private void uploadVertices() {
		long needed = 0;
		for (PooledMesh mesh : meshList) {
			needed += mesh.byteSize();
		}
		if (needed == 0) {
			return;
		}

		MemoryBlock block = MemoryBlock.malloc(needed);
		try {
			long byteIndex = 0;
			int baseVertex = 0;

			for (PooledMesh mesh : meshList) {
				mesh.baseVertex = baseVertex;

				// One view, re-pointed at each mesh's slice. The mesh writes itself through the
				// setters; nothing here knows what shape it is.
				vertexView.ptr(block.ptr() + byteIndex);
				vertexView.vertexCount(mesh.vertexCount());
				mesh.mesh.write(vertexView);

				byteIndex += mesh.byteSize();
				baseVertex += mesh.vertexCount();
			}

			replaceVertices(block, needed);
		} finally {
			block.free();
		}
	}

	private void replaceVertices(MemoryBlock block, long size) {
		if (vertices != null) {
			vertices.close();
		}
		vertices = RenderSystem.getDevice()
				.createBuffer(() -> "flywheel mesh pool vertices",
						GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST,
						MemoryUtil.memByteBuffer(block.ptr(), (int) size));
	}

	private void replaceIndices(MemoryBlock block, long size) {
		if (indices != null) {
			indices.close();
		}
		indices = RenderSystem.getDevice()
				.createBuffer(() -> "flywheel mesh pool indices",
						GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST,
						MemoryUtil.memByteBuffer(block.ptr(), (int) size));
	}

	/**
	 * One mesh's place in the pool, in the terms an indirect command asks for.
	 *
	 * <p>Reference counted because a mesh outlives any one draw: several instancers can share a
	 * model, and the pool must not drop geometry another draw is still naming.
	 */
	public class PooledMesh extends ReferenceCounted {
		public static final int INVALID_BASE_VERTEX = -1;

		private final Mesh mesh;
		private int baseVertex = INVALID_BASE_VERTEX;

		private PooledMesh(Mesh mesh) {
			this.mesh = mesh;
		}

		public int vertexCount() {
			return mesh.vertexCount();
		}

		public long byteSize() {
			return (long) mesh.vertexCount() * InternalVertex.STRIDE;
		}

		public int indexCount() {
			return mesh.indexCount();
		}

		/** Counted in vertices, because that is what the command's vertexOffset field means. */
		public int baseVertex() {
			return baseVertex;
		}

		/** Counted in indices, likewise. */
		public int firstIndex() {
			return BlazeMeshPool.this.firstIndices.getInt(mesh.indexSequence());
		}

		public boolean isInvalid() {
			return mesh.vertexCount() == 0 || baseVertex == INVALID_BASE_VERTEX || isDeleted();
		}

		@Override
		protected void _delete() {
			BlazeMeshPool.this.dirty = true;
			BlazeMeshPool.this.anyToRemove = true;
		}
	}
}
