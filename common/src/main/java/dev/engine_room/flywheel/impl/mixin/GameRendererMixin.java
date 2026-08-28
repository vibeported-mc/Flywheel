package dev.engine_room.flywheel.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;

import dev.engine_room.flywheel.impl.event.RenderContextHolder;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;

/**
 * Captures the projection matrix the level is actually drawn with.
 * <p>
 * View bobbing and the portal/nausea skew are not part of the camera's own matrices in 26.2: they
 * are multiplied into a copy of the projection here and handed to the GPU through
 * {@code RenderSystem.setProjectionMatrix}, while {@code LevelRenderer.render} is passed the plain
 * view rotation. {@code CameraRenderState.projectionMatrix} is therefore the matrix before all of
 * that, and building Flywheel's view-projection from it leaves everything Flywheel draws as the only
 * thing in the level that does not bob.
 */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "renderLevel", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/LevelRenderer;render(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lnet/minecraft/client/DeltaTracker;ZLnet/minecraft/client/renderer/state/level/CameraRenderState;Lorg/joml/Matrix4fc;Lcom/mojang/blaze3d/buffers/GpuBufferSlice;Lorg/joml/Vector4f;Z)V"))
	private void flywheel$captureProjection(DeltaTracker deltaTracker, CallbackInfo ci, @Local Matrix4f projectionMatrix) {
		RenderContextHolder.setProjection(projectionMatrix);
	}
}
