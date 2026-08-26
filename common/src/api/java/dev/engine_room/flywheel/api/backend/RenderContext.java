package dev.engine_room.flywheel.api.backend;

import org.joml.Matrix4fc;

import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;

public interface RenderContext {
	LevelRenderer renderer();

	ClientLevel level();

	/**
	 * Minecraft 26.2 extracts the level state it renders from before the frame starts, and most of
	 * what used to be readable straight off the level now only lives here.
	 */
	LevelRenderState levelRenderState();

	RenderBuffers buffers();

	Matrix4fc modelView();

	Matrix4fc projection();

	Matrix4fc viewProjection();

	CameraRenderState camera();

	float partialTick();
}
