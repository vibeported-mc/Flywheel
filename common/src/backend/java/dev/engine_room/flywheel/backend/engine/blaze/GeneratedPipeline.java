package dev.engine_room.flywheel.backend.engine.blaze;

import com.mojang.blaze3d.pipeline.RenderPipeline;

import dev.engine_room.flywheel.lib.math.MoreMath;
import net.minecraft.resources.Identifier;

/**
 * The pipeline for one instance type, and the shaders it was built from.
 *
 * <p>Cached per instance type rather than per instancer: the shader is generated from the type's
 * layout and the body that type names, so every instancer of a type draws with the same one. A
 * world with a thousand cogwheel instancers builds this once.
 */
public record GeneratedPipeline(RenderPipeline pipeline, Identifier shaders) implements AutoCloseable {
	public static GeneratedPipeline of(BlazeInstancer<?> instancer) throws Exception {
		int stride = MoreMath.align16(instancer.type.layout()
				.byteSize());

		Identifier shaders = BlazeShaders.generate(instancer.type, stride);

		return new GeneratedPipeline(PipelineSelfTest.pipelineFor(shaders), shaders);
	}

	@Override
	public void close() {
		// The pipeline itself is a description rather than a resource; Blaze3D owns whatever it
		// compiled from it. The generated sources are dropped wholesale by GeneratedShaders.clear().
	}
}
