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
	 * <p>Priority 750 needs no special-casing to do the right thing. On OpenGL the proven indirect
	 * backend is 1000 and still wins; on Vulkan it reports unsupported, because there is no GL
	 * context for GlCompat to ask about, and this takes over. Raise it past 1000 once this one is
	 * the better of the two on the same scene.
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
