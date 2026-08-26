package dev.engine_room.flywheel.impl.mixin;

import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.GraphicsResourceAllocator;

import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.FlwImplXplat;
import dev.engine_room.flywheel.impl.event.RenderContextHolder;
import dev.engine_room.flywheel.impl.event.RenderContextImpl;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.joml.Vector4f;

@Mixin(value = LevelRenderer.class, priority = 1001) // Higher priority to go after Sodium
abstract class LevelRendererMixin {
	@Shadow
	@Final
	private RenderBuffers renderBuffers;

	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	/**
	 * Minecraft 26.2 renders the level through a frame graph, so the context is captured here and
	 * the actual draws are dispatched from the platform's render stage hooks.
	 */
	@Inject(method = "render", at = @At("HEAD"))
	private void flywheel$beginRender(GraphicsResourceAllocator resourceAllocator, DeltaTracker deltaTracker, boolean renderOutline, CameraRenderState cameraState, Matrix4fc modelViewMatrix, GpuBufferSlice terrainFog, Vector4f fogColor, boolean shouldRenderSky, CallbackInfo ci) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level == null) {
			return;
		}

		RenderContextImpl context = RenderContextImpl.create((LevelRenderer) (Object) this, level, levelRenderState, renderBuffers, modelViewMatrix, cameraState.projectionMatrix, cameraState, deltaTracker.getGameTimeDeltaPartialTick(false));
		RenderContextHolder.set(context);

		VisualizationManager manager = VisualizationManager.get(level);
		if (manager != null) {
			manager.renderDispatcher()
					.onStartLevelRender(context);
		}
	}

	@Inject(method = "render", at = @At("RETURN"))
	private void flywheel$endRender(CallbackInfo ci) {
		RenderContextHolder.set(null);
	}

	/**
	 * {@code allChanged} became {@code resetLevelRenderData} when chunk rebuilds moved to the level
	 * extractor.
	 */
	@Inject(method = "resetLevelRenderData", at = @At("RETURN"))
	private void flywheel$reload(CallbackInfo ci) {
		ClientLevel level = Minecraft.getInstance().level;
		if (level != null) {
			FlwImplXplat.INSTANCE.dispatchReloadLevelRendererEvent(level);
		}
	}
}
