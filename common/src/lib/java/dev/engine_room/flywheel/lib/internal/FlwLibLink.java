package dev.engine_room.flywheel.lib.internal;

import java.util.Map;

import org.slf4j.Logger;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import dev.engine_room.flywheel.api.internal.DependencyInjection;
import dev.engine_room.flywheel.lib.transform.PoseTransformStack;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

public interface FlwLibLink {
	FlwLibLink INSTANCE = DependencyInjection.load(FlwLibLink.class, "dev.engine_room.flywheel.impl.FlwLibLinkImpl");

	Logger getLogger();

	PoseTransformStack getPoseTransformStackOf(PoseStack stack);

	Map<String, ModelPart> getModelPartChildren(ModelPart part);

	void compileModelPart(ModelPart part, PoseStack.Pose pose, VertexConsumer consumer, int light, int overlay, int color);

	/**
	 * Minecraft 26.2 moved culling information off of {@link Entity} and onto its renderer, where
	 * both queries are protected.
	 */
	AABB getBoundingBoxForCulling(Entity entity);

	boolean isAffectedByCulling(Entity entity);

	boolean isIrisLoaded();

	boolean isOptifineInstalled();

	boolean isShaderPackInUse();

	boolean isRenderingShadowPass();
}
