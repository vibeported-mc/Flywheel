package dev.engine_room.flywheel.backend.engine.blaze;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.blaze3dx.shader.GeneratedShaders;

import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.lib.math.MoreMath;
import net.minecraft.resources.Identifier;

/**
 * The pipeline for one instance type, and the shaders it was built from.
 *
 * <p>Cached per instance type rather than per instancer: the shader is generated from the type's
 * layout and the body that type names, so every instancer of a type draws with the same one. A
 * world with a thousand cogwheel instancers builds this once.
 */
public record GeneratedPipeline(RenderPipeline pipeline, Identifier shaders) {
	/**
	 * Builds and compiles the pipeline, or returns null having said why.
	 *
	 * <p>Compiled here rather than left to the first draw, and that is the whole point of this
	 * method existing. Blaze3D compiles lazily, so an invalid pipeline is not discovered until
	 * {@code setPipeline} throws in the middle of a frame -- which the engine answers by disabling
	 * the entire backend, so one instance type with a bad shader takes every other one down with
	 * it and the log says only "falling back".
	 *
	 * <p>Failing here instead costs one instance type. The rest of the world still draws, and the
	 * generated source goes in the log next to the driver's complaint about it, which is the only
	 * way to read an error reported as a line number in a file that exists nowhere.
	 */
	public static @Nullable GeneratedPipeline of(BlazeInstancer<?> instancer,
			BlazeMaterials.Key material, boolean lightingScene) {
		return of(instancer, material, false, lightingScene);
	}

	/** @param crumbling the block-breaking variant of the same instance type */
	public static @Nullable GeneratedPipeline of(BlazeInstancer<?> instancer,
			BlazeMaterials.Key material, boolean crumbling, boolean lightingScene) {
		int stride = MoreMath.align16(instancer.type.layout()
				.byteSize());

		Identifier shaders;
		try {
			shaders = BlazeShaders.generate(instancer.type, stride, material.fog(),
					material.cutout(), material.light(), material.ambientOcclusion(),
					material.cardinalLighting(), material.useLight(),
					instancer.environment.matrixIndex() != 0, crumbling, lightingScene);
		} catch (Exception e) {
			FlwBackend.LOGGER.error("Could not assemble a shader for {}", instancer.type, e);
			return null;
		}

		RenderPipeline pipeline = PipelineSelfTest.pipelineFor(shaders, material, crumbling);

		if (!RenderSystem.getDevice()
				.precompilePipeline(pipeline)
				.isValid()) {

			FlwBackend.LOGGER.error("""
					Generated shader for {} did not compile, so nothing of that type will draw.
					The driver reports line numbers against this source:
					{}""", instancer.type, numbered(GeneratedShaders.get(shaders, ShaderType.VERTEX)));

			return null;
		}

		return new GeneratedPipeline(pipeline, shaders);
	}

	/** Line numbers, because the error is reported as one and the file exists nowhere to look it up in. */
	private static String numbered(@Nullable String source) {
		if (source == null) {
			return "(the source is not registered, which should not be possible here)";
		}

		StringBuilder out = new StringBuilder();
		String[] lines = source.split("\n", -1);

		for (int i = 0; i < lines.length; i++) {
			out.append(i + 1)
					.append(": ")
					.append(lines[i])
					.append('\n');
		}

		return out.toString();
	}
}
