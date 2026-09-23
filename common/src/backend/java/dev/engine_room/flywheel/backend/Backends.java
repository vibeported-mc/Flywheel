package dev.engine_room.flywheel.backend;

import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.backend.compile.IndirectPrograms;
import dev.engine_room.flywheel.backend.compute.Compute;
import dev.engine_room.flywheel.backend.compile.InstancingPrograms;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.blaze.BlazeDrawManager;
import dev.engine_room.flywheel.backend.engine.blaze.BlazeEngine;
import dev.engine_room.flywheel.backend.engine.indirect.IndirectDrawManager;
import dev.engine_room.flywheel.backend.engine.instancing.InstancedDrawManager;
import dev.engine_room.flywheel.backend.gl.Driver;
import dev.engine_room.flywheel.backend.gl.GlCompat;
import dev.engine_room.flywheel.lib.backend.SimpleBackend;
import dev.engine_room.flywheel.lib.util.ResourceUtil;
import dev.engine_room.flywheel.lib.util.ShadersModHelper;

public final class Backends {
	/**
	 * Use GPU instancing to render everything.
	 */
	public static final Backend INSTANCING = SimpleBackend.builder()
			.engineFactory(level -> new EngineImpl(level, new InstancedDrawManager(InstancingPrograms.get()), 256))
			.priority(500)
			.supported(() -> GlCompat.SUPPORTS_INSTANCING && InstancingPrograms.allLoaded() && !ShadersModHelper.isShaderPackInUse())
			.register(ResourceUtil.rl("instancing"));

	/**
	 * Use Compute shaders to cull instances.
	 */
	public static final Backend INDIRECT = SimpleBackend.builder()
			.engineFactory(level -> new EngineImpl(level, new IndirectDrawManager(IndirectPrograms.get()), 256))
			.priority(() -> {
				// Read from GlCompat in these provider because loading GlCompat
				// at the same time the backends are registered causes GlCapabilities to be null.
				if (GlCompat.DRIVER == Driver.INTEL) {
					// Intel has very poor performance with indirect rendering, and on top of that has graphics bugs
					return 1;
				} else {
					return 1000;
				}
			})
			.supported(() -> GlCompat.SUPPORTS_INDIRECT && IndirectPrograms.allLoaded() && !ShadersModHelper.isShaderPackInUse())
			.register(ResourceUtil.rl("indirect"));

	/**
	 * Draw through Blaze3D rather than raw OpenGL, so the same code runs on Vulkan.
	 *
	 * <p>Priority decides nothing on Vulkan. {@link GlCompat} cannot read capabilities without a GL
	 * context, so both older backends report unsupported there and the choice is this or
	 * {@code off}. What the number settles is OpenGL alone.
	 *
	 * <p>And on Intel it is already settled: {@link #INDIRECT} drops itself to 1 there, so 750 beats
	 * it and {@link #INSTANCING}'s 500 as well. This is the default on Intel OpenGL today. Only
	 * non-Intel OpenGL still takes the older backend.
	 *
	 * <p>750 is deliberately below {@code indirect}'s 1000 despite being the faster of the two where
	 * both run: on one scene in one client, backend swapped underneath with nothing rebuilt between,
	 * 10,000 gear trains read 1345 frames a second here against 854 and 907 either side of it. The
	 * older backend's two readings bracket the rival rather than closing on it, so the gap is the
	 * backend and not warm-up -- the confound this scene invites.
	 *
	 * <p>Part of that margin is occlusion culling, which the older backend does not do at all, so it
	 * is not evidence that indirect drawing through Blaze3D is half again as fast. It is evidence
	 * that the whole path is, on this scene.
	 *
	 * <p>Left at 750 pending a decision, not because promoting it broke anything. Raising it to
	 * 1250 was tried and the suite failed {@code TrainCircuitTest} both times -- but so did the
	 * control run at 750, which is what that reading was missing. The test is flaky under six
	 * clients sharing one GPU and passes alone at either priority; the one run where it passed was
	 * the outlier, and two runs against one is not a signal.
	 */
	public static final Backend INDIRECT_BLAZE3D = SimpleBackend.builder()
			.engineFactory(level -> new BlazeEngine(level, new BlazeDrawManager(), 256))
			.priority(750)
			.supported(() -> Compute.available() && !ShadersModHelper.isShaderPackInUse())
			.register(ResourceUtil.rl("indirect_blaze3d"));

	private Backends() {
	}

	public static void init() {
	}
}
