package dev.engine_room.flywheel.impl.event;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

/**
 * Minecraft 26.2 builds its level render out of frame graph passes, so the points at which Flywheel
 * needs to draw are no longer reachable from a single mixin. The context is captured when the level
 * render begins and read back by the platform's render stage handlers.
 */
@ApiStatus.Internal
public final class RenderContextHolder {
	@Nullable
	private static RenderContextImpl context;

	private RenderContextHolder() {
	}

	public static void set(@Nullable RenderContextImpl context) {
		RenderContextHolder.context = context;
	}

	@Nullable
	public static RenderContextImpl get() {
		return context;
	}
}
