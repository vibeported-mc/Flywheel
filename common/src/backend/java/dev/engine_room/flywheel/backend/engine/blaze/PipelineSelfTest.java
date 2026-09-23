package dev.engine_room.flywheel.backend.engine.blaze;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.RenderSystem;

import dev.engine_room.flywheel.api.instance.InstanceType;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.math.MoreMath;
import net.minecraft.resources.Identifier;

/**
 * Does the shader Flywheel assembles for a real instance type actually compile?
 *
 * <p>Every instance type gets its own vertex shader, built from its layout and a body a mod
 * supplied, and none of them exists until the game is running. So "does it compile" is not a
 * build-time question here the way it is for a shader that ships as a file -- it is a runtime one,
 * and the answer differs per backend: OpenGL compiles the GLSL directly, while Vulkan runs it
 * through shaderc to SPIR-V and then rebinds it against the declared bind group layout.
 *
 * <p>Checked against Flywheel's own instance types rather than a made-up one, because the shapes
 * that matter are the ones mods actually use: a mat4 pose, normalised colour bytes, signed overlay
 * shorts, unsigned light shorts, a quaternion, and -- in the oriented type -- an include of
 * {@code flywheel:util/quaternion.glsl} that has to be resolved and pasted before any of it
 * compiles.
 *
 * <p>This is where a generated-shader mistake should surface. A pipeline that will not compile
 * draws nothing, and nothing is what a dozen unrelated faults also look like.
 */
public final class PipelineSelfTest {
	/**
	 * Everything a generated shader can name.
	 *
	 * <p>26.2 binds a uniform block or a sampler only if the pipeline declared it, and an
	 * undeclared one reads as zero rather than failing -- so this list is not documentation, it is
	 * the binding itself.
	 */
	public static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
			.withUniform(BlazeUniforms.BLOCK_NAME, UniformType.UNIFORM_BUFFER)
			.withUniform(BlazeEnvironments.BLOCK_NAME, UniformType.UNIFORM_BUFFER)
			.withUniform("_flw_instances", UniformType.TEXEL_BUFFER, GpuFormat.RGBA32_UINT)
			// R32, not RGBA32: both hold flat arrays of uints, so one texel has to be one uint. With
			// four components a lookup past the first would read from four times the right offset.
			.withUniform("_flw_visible", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
			.withUniform("_flw_lightSections", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
			.withUniform("_flw_lightLut", UniformType.TEXEL_BUFFER, GpuFormat.R32_UINT)
			.withSampler("Sampler0")
			.withSampler("Sampler2")
			.build();

	private PipelineSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();

		List<InstanceType<?>> types = List.of(
				InstanceTypes.TRANSFORMED,
				InstanceTypes.POSED,
				InstanceTypes.ORIENTED,
				InstanceTypes.SHADOW);

		for (InstanceType<?> type : types) {
			String name = type.vertexShader()
					.toString();

			try {
				int stride = MoreMath.align16(type.layout()
						.byteSize());
				Identifier shaders = BlazeShaders.generate(type, stride, CutoutShaders.ONE_TENTH.source(),
						FogShaders.LINEAR.source());

				RenderPipeline pipeline = pipelineFor(shaders);
				CompiledRenderPipeline compiled = RenderSystem.getDevice()
						.precompilePipeline(pipeline);

				if (!compiled.isValid()) {
					lines.add("WOULD NOT COMPILE: " + name);
					lines.add("source:\n" + GeneratedShaders.get(shaders,
							com.mojang.blaze3d.shaders.ShaderType.VERTEX));
					return new Result(false, lines);
				}

				lines.add("compiled " + name + " at stride " + stride);
			} catch (Exception e) {
				lines.add("THREW for " + name + ": " + e);
				return new Result(false, lines);
			}
		}

		return new Result(true, lines);
	}

	public static RenderPipeline pipelineFor(Identifier shaders) {
		return pipelineFor(shaders, new BlazeMaterials.Key(
				dev.engine_room.flywheel.api.material.Transparency.OPAQUE,
				dev.engine_room.flywheel.api.material.DepthTest.LEQUAL,
				dev.engine_room.flywheel.api.material.WriteMask.COLOR_DEPTH, true, false,
				FogShaders.LINEAR.source(), CutoutShaders.ONE_TENTH.source()));
	}

	/** The same pipeline, with a material's fixed-function state baked into it. */
	public static RenderPipeline pipelineFor(Identifier shaders, BlazeMaterials.Key material) {
		return RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("flywheel",
						"pipeline/" + shaders.getPath() + "/" + material.describe()))
				.withVertexShader(shaders)
				.withFragmentShader(shaders)
				.withVertexBinding(0, BlazeVertex.FORMAT)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withBindGroupLayout(LAYOUT)
				// DEFAULT is GREATER_THAN_OR_EQUAL, which is right rather than backwards: 26.2's
				// depth buffer is reversed, near at 1 and far at 0. A pipeline naming no depth state
				// gets no depth attachment at all and draws over everything.
				.withDepthStencilState(BlazeMaterials.depthStencil(material))
				.withColorTargetState(BlazeMaterials.colorTarget(material))
				.withCull(material.backfaceCulling())
				.build();
	}
}
