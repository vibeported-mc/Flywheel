package dev.engine_room.flywheel.backend.mixin;

import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.Lighting;

import dev.engine_room.flywheel.backend.engine.uniform.LevelUniforms;

/**
 * The level's diffuse light directions used to be handed to
 * {@code GlStateManager#setupLevelDiffuseLighting}; in 26.2 they are written straight into a uniform
 * buffer by {@link Lighting}, and are no longer transformed into screen space on the way.
 */
@Mixin(value = Lighting.class, remap = false)
abstract class LightingMixin {
	@Inject(method = "updateBuffer", at = @At("HEAD"))
	private void flywheel$onUpdateBuffer(Lighting.Entry entry, Vector3fc light0, Vector3fc light1, CallbackInfo ci) {
		if (entry == Lighting.Entry.LEVEL) {
			LevelUniforms.LIGHT0_DIRECTION.set(light0);
			LevelUniforms.LIGHT1_DIRECTION.set(light1);
		}
	}
}
