package dev.engine_room.flywheel.lib.model.baked;

import java.util.Iterator;

import org.jetbrains.annotations.Nullable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.engine_room.flywheel.lib.model.SimpleModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockModelLighter;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

final class BakedModelBufferer {
	private static final ThreadLocal<ThreadLocalObjects> THREAD_LOCAL_OBJECTS = ThreadLocal.withInitial(ThreadLocalObjects::new);

	private BakedModelBufferer() {
	}

	public static SimpleModel bufferModel(BlockStateModel model, BlockPos pos, BlockAndTintGetter level, BlockState state, @Nullable PoseStack poseStack, BlockMaterialFunction blockMaterialFunction) {
		ThreadLocalObjects objects = THREAD_LOCAL_OBJECTS.get();
		if (poseStack == null) {
			poseStack = objects.identityPoseStack;
		}
		MeshEmitterManager<MeshEmitter> emitters = objects.emitters;
		NeoforgeMeshEmitter output = objects.output;

		emitters.prepare(blockMaterialFunction);

		ModelBlockRenderer blockRenderer = newBlockRenderer();
		output.prepareForModelLayer(useAmbientOcclusion(level, pos, state));

		// tesselateBlock bakes the block position into the emitted vertices rather than reading a
		// PoseStack, so the caller's transform is applied by feeding it the translation directly.
		var pose = poseStack.last()
				.pose();
		blockRenderer.tesselateBlock(output, pose.m30(), pose.m31(), pose.m32(), level, pos, state, model, state.getSeed(pos));

		return emitters.end();
	}

	public static SimpleModel bufferBlocks(Iterator<BlockPos> posIterator, BlockAndTintGetter level, @Nullable PoseStack poseStack, boolean renderFluids, BlockMaterialFunction blockMaterialFunction) {
		ThreadLocalObjects objects = THREAD_LOCAL_OBJECTS.get();
		if (poseStack == null) {
			poseStack = objects.identityPoseStack;
		}
		MeshEmitterManager<MeshEmitter> emitters = objects.emitters;
		NeoforgeMeshEmitter output = objects.output;
		TransformingVertexConsumer transformingWrapper = objects.transformingWrapper;
		PoseStack fluidPoseStack = poseStack;

		emitters.prepare(blockMaterialFunction);

		ModelBlockRenderer blockRenderer = newBlockRenderer();
		var modelManager = Minecraft.getInstance()
				.getModelManager();
		var blockStateModels = modelManager.getBlockStateModelSet();
		FluidRenderer fluidRenderer = new FluidRenderer(modelManager.getFluidStateModelSet());
		BlockModelLighter.enableCaching();

		var origin = poseStack.last()
				.pose();
		float originX = origin.m30();
		float originY = origin.m31();
		float originZ = origin.m32();

		while (posIterator.hasNext()) {
			BlockPos pos = posIterator.next();
			BlockState state = level.getBlockState(pos);

			emitters.prepareForBlock();

			if (renderFluids) {
				FluidState fluidState = state.getFluidState();

				if (!fluidState.isEmpty()) {
					FluidRenderer.Output fluidOutput = layer -> fluidBuffer(emitters, transformingWrapper, fluidPoseStack, layer);

					poseStack.pushPose();
					poseStack.translate(pos.getX() - (pos.getX() & 0xF), pos.getY() - (pos.getY() & 0xF), pos.getZ() - (pos.getZ() & 0xF));
					fluidRenderer.tesselate(level, pos, fluidOutput, state, fluidState);
					poseStack.popPose();
				}
			}

			if (state.getRenderShape() == RenderShape.MODEL) {
				BlockStateModel model = blockStateModels.get(state);

				output.prepareForModelLayer(useAmbientOcclusion(level, pos, state));

				blockRenderer.tesselateBlock(output, originX + pos.getX(), originY + pos.getY(), originZ + pos.getZ(), level, pos, state, model, state.getSeed(pos));
			}
		}

		BlockModelLighter.clearCache();
		transformingWrapper.clear();
		return emitters.end();
	}

	@Nullable
	private static VertexConsumer fluidBuffer(MeshEmitterManager<MeshEmitter> emitters, TransformingVertexConsumer transformingWrapper, PoseStack poseStack, ChunkSectionLayer layer) {
		var bufferBuilder = emitters.getBuffer(layer, true, false);

		if (bufferBuilder == null) {
			return null;
		}

		transformingWrapper.prepare(bufferBuilder, poseStack);
		return transformingWrapper;
	}

	/**
	 * Mirrors the default branch of {@link ModelBlockRenderer#tesselateBlock}; per-part overrides are
	 * picked up by the mixin that calls {@link NeoforgeMeshEmitter#prepareForModelLayer}.
	 */
	private static boolean useAmbientOcclusion(BlockAndTintGetter level, BlockPos pos, BlockState state) {
		return ambientOcclusionEnabled() && state.getLightEmission(level, pos) == 0;
	}

	private static ModelBlockRenderer newBlockRenderer() {
		return new ModelBlockRenderer(ambientOcclusionEnabled(), false, Minecraft.getInstance()
				.getBlockColors());
	}

	private static boolean ambientOcclusionEnabled() {
		return Minecraft.getInstance().options.ambientOcclusion()
				.get();
	}

	private static class ThreadLocalObjects {
		public final PoseStack identityPoseStack = new PoseStack();
		public final RandomSource random = RandomSource.create();

		public final MeshEmitterManager<MeshEmitter> emitters = new MeshEmitterManager<>(MeshEmitter::new);
		public final NeoforgeMeshEmitter output = new NeoforgeMeshEmitter(emitters);
		public final TransformingVertexConsumer transformingWrapper = new TransformingVertexConsumer();
	}
}
