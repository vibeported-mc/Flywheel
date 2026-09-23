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
