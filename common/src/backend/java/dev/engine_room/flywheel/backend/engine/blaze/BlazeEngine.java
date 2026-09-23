package dev.engine_room.flywheel.backend.engine.blaze;

import java.util.List;

import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.DrawManager;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import net.minecraft.world.level.LevelAccessor;

/**
 * {@link EngineImpl} with the two OpenGL-only steps taken out of its frame.
 *
 * <p>Everything an engine does other than those two is already backend-agnostic -- origins, light
 * sections, the frame plan, instancer lookup -- so this inherits all of it and overrides only
 * {@code render} and {@code renderCrumbling}.
 *
 * <p>What it cannot inherit is how the level's render target gets bound.
 * {@code EngineImpl.render} calls {@code GlStateTracker.getRestoreState()} and
 * {@code GlRenderTargets.bind(main())}, both of which reach into the OpenGL backend: the first
 * shadows bound-object state so Flywheel's own draws can be undone afterwards, the second finds the
 * framebuffer through {@code GlDevice}'s FBO cache. Under Vulkan there is no GL context for either
 * to work with. Neither is needed here: {@link BlazeDrawManager} opens a real {@code RenderPass}
 * against the main target's texture views, and pipeline state belongs to the pipeline rather than
 * to a global the next caller has to have restored for them.
 */
public class BlazeEngine extends EngineImpl {
	/** The most recent draw manager of this kind, for readers that run between frames. */
	private static @org.jspecify.annotations.Nullable BlazeDrawManager last;

	public static @org.jspecify.annotations.Nullable BlazeDrawManager lastDrawManager() {
		return last;
	}

	public BlazeEngine(LevelAccessor level, DrawManager<? extends AbstractInstancer<?>> drawManager,
			int maxOriginDistance) {
		super(level, drawManager, maxOriginDistance);
	}

	@Override
	public void render(RenderContext context) {
		try {
			// A draw manager is not handed the context, and this one needs it: the camera and the
			// render origin are what its uniform block is built from.
			if (drawManager() instanceof BlazeDrawManager blaze) {
				// Remembered so anything outside the frame can reach what this frame built -- the
				// depth pyramid in particular, which only holds a real scene while a frame is being
				// drawn and is meaningless to rebuild from outside one.
				last = blaze;
				blaze.prepare(context, renderOrigin());
			}

			environmentStorage().flush();
			drawManager().render(lightStorage(), environmentStorage());
		} catch (Exception e) {
			// Same contract as the OpenGL engine: a backend that throws mid-frame takes itself out
			// rather than throwing again every frame after. Falling back to `off` renders Create's
			// moving parts as ordinary block entities, which is slow and correct.
			FlwBackend.LOGGER.error("Falling back", e);
			drawManager().triggerFallback();
		}
	}

	/**
	 * Reduces this frame's depth into the pyramid the next frame will cull against.
	 *
	 * <p>Here rather than in {@link #render}, and that is forced rather than chosen: during the level
	 * render the depth texture is the attachment of the pass in progress, and sampling an attachment
	 * reads as zero. Afterwards it is an ordinary texture again.
	 *
	 * <p>Which means the pyramid is always a frame behind, and that is how Hi-Z occlusion culling is
	 * normally done anyway -- culling against the previous frame's depth costs a little accuracy when
	 * the camera moves fast and nothing at all when it does not.
	 */
	@Override
	public void afterLevelRender(RenderContext context) {
		try {
			if (drawManager() instanceof BlazeDrawManager blaze) {
				blaze.buildDepthPyramid();
			}
		} catch (Exception e) {
			// An optimisation failing should not take the renderer with it.
			FlwBackend.LOGGER.error("Could not build the depth pyramid", e);
		}
	}

	@Override
	public void renderCrumbling(RenderContext context, List<CrumblingBlock> crumblingBlocks) {
		try {
			drawManager().renderCrumbling(crumblingBlocks);
		} catch (Exception e) {
			FlwBackend.LOGGER.error("Falling back", e);
			drawManager().triggerFallback();
		}
	}
}
