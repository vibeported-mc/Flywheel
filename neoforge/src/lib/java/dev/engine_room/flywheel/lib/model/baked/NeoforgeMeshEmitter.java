package dev.engine_room.flywheel.lib.model.baked;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.PoseStack;
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

	@org.jetbrains.annotations.UnknownNullability
	private PoseStack poseStack;

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

	void setPoseStack(PoseStack poseStack) {
		this.poseStack = poseStack;
	}

	/**
	 * Quads go in through {@code putBakedQuad} rather than {@code putBlockBakedQuad}: the block form
	 * takes a bare translation and writes neither a normal nor an overlay, while this one applies the
	 * caller's whole pose and carries the quad's own baked normals - which is what models loaded from
	 * OBJ actually supply.
	 */
	@Override
	public void put(float x, float y, float z, BakedQuad quad, QuadInstance instance) {
		BakedQuad.MaterialInfo materialInfo = quad.materialInfo();

		// MaterialInfo#ambientOcclusion only ever force-disables AO for a quad; whether the block as a
		// whole was lit with AO is what prepareForModelLayer records.
		boolean ao = materialInfo.ambientOcclusion() && defaultAo;

		BufferBuilder bufferBuilder = emitters.getBuffer(materialInfo.layer(), materialInfo.shade(), ao);

		if (bufferBuilder == null) {
			return;
		}

		if (x != 0.0F || y != 0.0F || z != 0.0F) {
			poseStack.pushPose();
			poseStack.translate(x, y, z);
			bufferBuilder.putBakedQuad(poseStack.last(), quad, instance);
			poseStack.popPose();
		} else {
			bufferBuilder.putBakedQuad(poseStack.last(), quad, instance);
		}
	}
}
