package dev.engine_room.flywheel.impl.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Minecraft 26.2 builds its level render out of frame graph passes, so the points at which Flywheel
 * needs to draw are no longer reachable from a single mixin. The context is captured when the level
 * render begins and read back by the platform's render stage handlers.
 */
@ApiStatus.Internal
public final class RenderContextHolder {
	@Nullable
	private static RenderContextImpl context;

	private static final Matrix4f projection = new Matrix4f();
	private static boolean hasProjection;

	private RenderContextHolder() {
	}

	public static void set(@Nullable RenderContextImpl context) {
		RenderContextHolder.context = context;
	}

	@Nullable
	public static RenderContextImpl get() {
		return context;
	}

	/**
	 * The projection the level is drawn with, captured just before the level render begins.
	 * <p>
	 * View bobbing and the portal skew live only here - they are folded into a copy of the camera's
	 * projection and uploaded straight to the GPU, so the camera's own matrix is the one from before
	 * they were applied.
	 */
	public static void setProjection(Matrix4fc captured) {
		projection.set(captured);
		hasProjection = true;
	}

	/**
	 * @param fallback The camera's own projection, used until a frame has been captured.
	 */
	public static Matrix4fc projectionOr(Matrix4fc fallback) {
		return hasProjection ? projection : fallback;
	}
}
