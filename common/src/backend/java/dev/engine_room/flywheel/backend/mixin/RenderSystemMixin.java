package dev.engine_room.flywheel.backend.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.backend.gl.GlCompat;

@Mixin(value = RenderSystem.class, remap = false)
abstract class RenderSystemMixin {
	@Inject(method = "initRenderer(Lcom/mojang/blaze3d/systems/GpuDevice;)V", at = @At("RETURN"))
	private static void flywheel$onInitRenderer(GpuDevice device, CallbackInfo ci) {
		GlCompat.init();
	}

	// The setShaderFog* setters this used to hook are gone: fog is extracted into
	// CameraRenderState#fogData once per frame, so FogUniforms is updated from Uniforms#update.
}
