package dev.engine_room.flywheel.impl.mixin.visualmanage;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import dev.engine_room.flywheel.impl.FlwImplXplat;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

@Mixin(LevelExtractor.class)
abstract class LevelExtractorMixin {
	@Shadow
	@Nullable
	private ClientLevel level;

	/**
	 * This gets called when a block is marked for rerender by vanilla.
	 */
	@Inject(method = "setBlockDirty(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;)V", at = @At("TAIL"))
	private void flywheel$checkUpdate(BlockPos pos, BlockState oldState, BlockState newState, CallbackInfo ci) {
		VisualizationManager manager = VisualizationManager.get(level);
		if (manager == null) {
			return;
		}

		BlockEntity blockEntity = level.getBlockEntity(pos);
		if (blockEntity == null) {
			return;
		}

		var blockEntities = manager.blockEntities();
		if (oldState != newState) {
			blockEntities.queueRemove(blockEntity);
			blockEntities.queueAdd(blockEntity);
		} else {
			// I don't think this is possible to reach in vanilla
			blockEntities.queueUpdate(blockEntity);
		}
	}

	/**
	 * The successor to 1.21.1's {@code LevelRenderer.allChanged}, which is the hook Flywheel reloads
	 * on.
	 *
	 * <h2>26.2 note</h2>
	 * <p>This was put on {@code LevelRenderer.resetLevelRenderData} on the reasoning that
	 * {@code allChanged} had moved there. It had not: {@code LevelExtractor.allChanged} is still the
	 * method, and {@code resetLevelRenderData} is a different, deferred one that also runs for
	 * {@code setLevel} -- {@code setLevel} only raises {@code shouldResetLevelRenderData}, and the
	 * reset happens in the next {@code extract}, a frame later.
	 *
	 * <p>Reloading there tears the visualization manager down <i>after</i> the first chunks have
	 * arrived and queued their block entities into it, and a fresh manager re-queues loaded entities
	 * but not loaded block entities -- so every block entity present at that moment was lost. The
	 * symptom was silent and total: nothing on the instancing backends rendered its moving parts,
	 * while anything placed afterwards was fine.
	 */
	@Inject(method = "allChanged()V", at = @At("RETURN"))
	private void flywheel$reload(CallbackInfo ci) {
		ClientLevel level = Minecraft.getInstance().level;

		if (level != null) {
			FlwImplXplat.INSTANCE.dispatchReloadLevelRendererEvent(level);
		}
	}
}
