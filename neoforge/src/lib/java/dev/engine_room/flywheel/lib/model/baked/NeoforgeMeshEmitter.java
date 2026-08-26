package dev.engine_room.flywheel.lib.model.baked;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.QuadInstance;

import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * Minecraft 26.2 no longer tesselates a block once per render type. Instead each {@link BakedQuad}
 * carries the {@link net.minecraft.client.renderer.chunk.ChunkSectionLayer} it belongs to, so rather
 * than being one emitter per layer this routes every quad to the emitter for its own layer.
 */
@ApiStatus.Internal
public class NeoforgeMeshEmitter implements BlockQuadOutput {
	private final MeshEmitterManager<MeshEmitter> emitters;

	private boolean defaultAo;

	NeoforgeMeshEmitter(MeshEmitterManager<MeshEmitter> emitters) {
		this.emitters = emitters;
	}

	/**
	 * Some mods, like FramedBlocks, have custom hooks to determine the default AO. This method is invoked a second time
	 * from within a mixin to {@link ModelBlockRenderer} after the accurate value is computed, so we don't need to
	 * support those custom hooks manually. It is possible that the mixin injector will never run, so we always compute
	 * the value manually beforehand too.
	 */
	public void prepareForModelLayer(boolean defaultAo) {
		this.defaultAo = defaultAo;
	}

	@Override
	public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
		BakedQuad.MaterialInfo materialInfo = quad.materialInfo();

		// MaterialInfo#ambientOcclusion only ever force-disables AO for a quad; whether the block as a
		// whole was lit with AO is what prepareForModelLayer records.
		boolean ao = materialInfo.ambientOcclusion() && defaultAo;

		BufferBuilder bufferBuilder = emitters.getBuffer(materialInfo.layer(), materialInfo.shade(), ao);

		if (bufferBuilder != null) {
			bufferBuilder.putBlockBakedQuad(x, y, z, quad, instance);
		}
	}
}
