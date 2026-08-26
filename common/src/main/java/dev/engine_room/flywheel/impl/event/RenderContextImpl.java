package dev.engine_room.flywheel.impl.event;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

public record RenderContextImpl(LevelRenderer renderer, ClientLevel level, LevelRenderState levelRenderState,
								RenderBuffers buffers, Matrix4fc modelView, Matrix4fc projection,
								Matrix4fc viewProjection, CameraRenderState camera,
								float partialTick) implements RenderContext {
	public static RenderContextImpl create(LevelRenderer renderer, ClientLevel level, LevelRenderState levelRenderState, RenderBuffers buffers, Matrix4fc modelView, Matrix4fc projection, CameraRenderState camera, float partialTick) {
		Matrix4f viewProjection = new Matrix4f(projection);
		viewProjection.mul(modelView);

		return new RenderContextImpl(renderer, level, levelRenderState, buffers, modelView, projection, viewProjection, camera, partialTick);
	}
}
