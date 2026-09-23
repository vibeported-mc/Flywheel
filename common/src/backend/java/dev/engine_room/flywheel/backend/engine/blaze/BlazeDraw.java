package dev.engine_room.flywheel.backend.engine.blaze;

import dev.engine_room.flywheel.api.material.Material;

/**
 * One mesh of one model, drawn for every instance of it.
 *
 * <p>A draw is not a command. It is what a command gets built from, once a frame, on the GPU: the
 * geometry's place in the pool, how many instances there are, and the material that decides which
 * pipeline draws it. The command itself is written by a compute shader and read by the GPU, and the
 * CPU never sees one.
 *
 * <p>Holds a reference on its mesh for as long as it exists, because several instancers can share a
 * model and the pool must not drop geometry a draw is still naming.
 */
public class BlazeDraw {
	private final BlazeInstancer<?> instancer;
	private final Material material;
	private final BlazeMeshPool.PooledMesh mesh;
	private final int bias;
	private final int indexOfMeshInModel;

	public BlazeDraw(BlazeInstancer<?> instancer, Material material, BlazeMeshPool.PooledMesh mesh,
			int bias, int indexOfMeshInModel) {
		this.instancer = instancer;
		this.material = material;
		this.mesh = mesh;
		this.bias = bias;
		this.indexOfMeshInModel = indexOfMeshInModel;

		mesh.acquire();
	}

	public BlazeInstancer<?> instancer() {
		return instancer;
	}

	public Material material() {
		return material;
	}

	public BlazeMeshPool.PooledMesh mesh() {
		return mesh;
	}

	public int bias() {
		return bias;
	}

	public int indexOfMeshInModel() {
		return indexOfMeshInModel;
	}

	/** Whether there is anything to draw: no instances, or geometry that never made it into the pool. */
	public boolean isEmpty() {
		return instancer.instanceCount() == 0 || mesh.isInvalid();
	}

	public void delete() {
		mesh.release();
	}
}
