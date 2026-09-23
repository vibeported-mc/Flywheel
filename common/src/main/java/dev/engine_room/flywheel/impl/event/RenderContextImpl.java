package dev.engine_room.flywheel.impl.event;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import dev.engine_room.flywheel.api.backend.RenderContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.state.level.BlockBreakingRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * @param blockBreaking what was being broken when this frame started drawing, copied rather than
 *            referenced -- see {@link #create}
 */
public record RenderContextImpl(LevelRenderer renderer, ClientLevel level, LevelRenderState levelRenderState,
								RenderBuffers buffers, Matrix4fc modelView, Matrix4fc projection,
								Matrix4fc viewProjection, CameraRenderState camera,
								List<BlockBreakingRenderState> blockBreaking,
								float partialTick) implements RenderContext {
	/**
	 * Captures everything a frame's draws need, at the head of the level render.
	 *
	 * <p>The block-breaking states are <em>copied</em> here, and that is not defensive tidiness. The
	 * list on the level render state is cleared and refilled by {@code LevelExtractor} every time it
	 * extracts, and 26.2 extracts the next frame while this one is still being drawn. Vanilla is
	 * unaffected because it draws its own block-breaking early; Flywheel's crumbling hook runs at the
	 * end of the frame, by which point the list has been emptied for the frame after this one.
	 *
	 * <p>Holding the reference instead is what made the crack overlay vanish on every backend: the
	 * list read at the hook was empty on every frame of a dig that ran all the way to the block
	 * breaking, while the same list read here held the block being mined.
	 */
	public static RenderContextImpl create(LevelRenderer renderer, ClientLevel level, LevelRenderState levelRenderState, RenderBuffers buffers, Matrix4fc modelView, Matrix4fc projection, CameraRenderState camera, float partialTick) {
		Matrix4f viewProjection = new Matrix4f(projection);
		viewProjection.mul(modelView);

		// Copied even when empty, which is almost every frame, because List.copyOf of an empty list
		// is a shared constant and costs nothing.
		List<BlockBreakingRenderState> blockBreaking = List.copyOf(levelRenderState.blockBreakingRenderStates);

		return new RenderContextImpl(renderer, level, levelRenderState, buffers, modelView, projection, viewProjection, camera, blockBreaking, partialTick);
	}
}
