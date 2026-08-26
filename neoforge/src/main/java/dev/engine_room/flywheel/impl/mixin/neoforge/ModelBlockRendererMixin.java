package dev.engine_room.flywheel.impl.mixin.neoforge;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import dev.engine_room.flywheel.lib.model.baked.NeoforgeMeshEmitter;
import net.minecraft.client.renderer.block.BlockQuadOutput;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Minecraft 26.2 decides ambient occlusion once per block inside
 * {@link ModelBlockRenderer#tesselateBlock}, then dispatches to one of two private methods. Which of
 * the two runs is exactly the answer Flywheel needs, and catching it here means mods with custom AO
 * hooks are handled without having to reimplement them.
 */
@Mixin(ModelBlockRenderer.class)
abstract class ModelBlockRendererMixin {
	@Inject(method = "tesselateAmbientOcclusion", at = @At("HEAD"), require = 0)
	private void flywheel$onTesselateAmbientOcclusion(BlockQuadOutput output, float x, float y, float z, List<BlockStateModelPart> parts, BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfo ci) {
		if (output instanceof NeoforgeMeshEmitter meshEmitter) {
			meshEmitter.prepareForModelLayer(true);
		}
	}

	@Inject(method = "tesselateFlat", at = @At("HEAD"), require = 0)
	private void flywheel$onTesselateFlat(BlockQuadOutput output, float x, float y, float z, List<BlockStateModelPart> parts, BlockAndTintGetter level, BlockState state, BlockPos pos, CallbackInfo ci) {
		if (output instanceof NeoforgeMeshEmitter meshEmitter) {
			meshEmitter.prepareForModelLayer(false);
		}
	}
}
