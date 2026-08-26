package dev.engine_room.flywheel.backend.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LevelTargetBundle;

@Mixin(LevelRenderer.class)
public interface LevelRendererAccessor {
	/**
	 * Minecraft 26.2 moved the level render targets into a frame graph, so the item entity target is
	 * no longer reachable through a getter.
	 */
	@Accessor("targets")
	LevelTargetBundle flywheel$targets();
}
