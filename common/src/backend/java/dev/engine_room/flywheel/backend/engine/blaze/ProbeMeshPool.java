package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.RenderSystem;

/**
 * Fixed geometry for the self-tests: every mesh in one vertex buffer and one index buffer.
 *
 * <p>This is what lets a single draw call cover models that have nothing to do with each other. An
 * indirect command names its geometry by offset -- {@code firstIndex}, {@code indexCount},
 * {@code vertexOffset} -- rather than by which buffer is bound, so as long as all the geometry
 * lives in one pair of buffers, the GPU can be handed a list of commands spanning many models and
 * left to get on with it. Bound once, drawn many times, with the CPU naming nothing.
 *
 * <p>Deliberately immutable once built. Growing a pool mid-frame means reallocating the buffers the
 * draw is about to read, and the real engine will want an arena with free lists; this is the piece
 * that proves the offsets and the draw path, and it is easier to be sure of when it cannot change
 * underneath anything.
 */
public final class ProbeMeshPool implements AutoCloseable {
	private final @Nullable GpuBuffer vertices;
	private final @Nullable GpuBuffer indices;
	private final List<Mesh> meshes;

	private ProbeMeshPool(@Nullable GpuBuffer vertices, @Nullable GpuBuffer indices, List<Mesh> meshes) {
		this.vertices = vertices;
		this.indices = indices;
		this.meshes = meshes;
	}

	public static Builder builder() {
		return new Builder();
	}

	public GpuBuffer vertices() {
		if (vertices == null) {
			throw new IllegalStateException("mesh pool is empty");
		}
		return vertices;
	}

	public GpuBuffer indices() {
		if (indices == null) {
			throw new IllegalStateException("mesh pool is empty");
		}
		return indices;
	}

	public Mesh mesh(int index) {
		return meshes.get(index);
	}

	public int size() {
		return meshes.size();
	}

	@Override
	public void close() {
		if (vertices != null) {
			vertices.close();
		}
		if (indices != null) {
			indices.close();
		}
	}

	/**
	 * Where one model sits in the pool, in exactly the terms an indirect command asks for.
	 *
	 * @param firstIndex   index of this mesh's first index within the shared index buffer
	 * @param indexCount   how many indices it uses
	 * @param vertexOffset added to every index before it is looked up, so a mesh's indices can be
	 *                     written from zero regardless of where its vertices landed
	 */
	public record Mesh(int firstIndex, int indexCount, int vertexOffset) {
	}

	public static final class Builder {
		private final List<float[]> positions = new ArrayList<>();
		private final List<short[]> indices = new ArrayList<>();

		private Builder() {
		}

		/**
		 * @param xyz     vertex positions, three floats each
		 * @param order   indices into this mesh's own vertices, counted from zero
		 */
		public Builder add(float[] xyz, short[] order) {
			positions.add(xyz);
			indices.add(order);
			return this;
		}

		public ProbeMeshPool build(String label) {
			if (positions.isEmpty()) {
				return new ProbeMeshPool(null, null, List.of());
			}

			List<Mesh> meshes = new ArrayList<>(positions.size());

			int vertexCount = 0;
			int indexCount = 0;
			for (int i = 0; i < positions.size(); i++) {
				int verticesHere = positions.get(i).length / 3;
				short[] order = indices.get(i);

				// vertexOffset counts vertices, not bytes, and firstIndex counts indices. Getting
				// either in bytes draws whatever happens to be at that address -- geometry from the
				// wrong model, or garbage, with no error either way.
				meshes.add(new Mesh(indexCount, order.length, vertexCount));

				vertexCount += verticesHere;
				indexCount += order.length;
			}

			ByteBuffer vertexData = ByteBuffer.allocateDirect(vertexCount * 3 * Float.BYTES)
					.order(ByteOrder.nativeOrder());
			for (float[] xyz : positions) {
				for (float f : xyz) {
					vertexData.putFloat(f);
				}
			}
			vertexData.flip();

			ByteBuffer indexData = ByteBuffer.allocateDirect(indexCount * Short.BYTES)
					.order(ByteOrder.nativeOrder());
			for (short[] order : indices) {
				// Written unmodified: the command's vertexOffset does the rebasing on the GPU, so
				// the same mesh can be pooled anywhere without rewriting its indices.
				for (short s : order) {
					indexData.putShort(s);
				}
			}
			indexData.flip();

			var device = RenderSystem.getDevice();

			return new ProbeMeshPool(
					device.createBuffer(() -> label + " vertices",
							GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST, vertexData),
					device.createBuffer(() -> label + " indices",
							GpuBuffer.USAGE_INDEX | GpuBuffer.USAGE_COPY_DST, indexData),
					List.copyOf(meshes));
		}
	}
}
