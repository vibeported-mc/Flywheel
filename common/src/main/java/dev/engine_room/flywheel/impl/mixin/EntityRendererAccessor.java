package dev.engine_room.flywheel.impl.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

@Mixin(EntityRenderer.class)
public interface EntityRendererAccessor {
	@Invoker("getBoundingBoxForCulling")
	AABB flywheel$getBoundingBoxForCulling(Entity entity);

	@Invoker("affectedByCulling")
	boolean flywheel$affectedByCulling(Entity entity);
}
