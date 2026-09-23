package dev.engine_room.flywheel.backend.engine.blaze;

import java.io.IOException;

import dev.engine_room.flywheel.api.instance.InstanceType;
import net.minecraft.resources.Identifier;

/**
 * Assembles the vertex and fragment shaders for one instance type.
 *
 * <p>The generated half of Flywheel's shader ABI. A mod writes one function --
 * {@code flw_instanceVertex(in FlwInstance)} -- against names it never declares: the struct, the
 * vertex globals it assigns to, {@code flw_renderSeconds}. Everything around that function is built
 * here, so the body a mod shipped for the OpenGL backend compiles unchanged for this one.
 *
 * <p>That is the whole reason this backend can exist without Create changing: Create's eight
 * instance shaders are function bodies, not programs.
 */
public final class BlazeShaders {
	/**
	 * The vertex globals a mod's body reads and writes.
	 *
	 * <p>Assigned from the vertex attributes before the body runs and consumed after it, so a body
	 * that ignores one leaves it at the vertex's own value -- which is what
	 * {@code flw_vertexColor *= instance.color} depends on.
	 */
	private static final String GLOBALS = """
			vec4 flw_vertexPos;
			vec4 flw_vertexColor;
			vec2 flw_vertexTexCoord;
			vec2 flw_vertexLight;
			vec3 flw_vertexNormal;
			ivec2 flw_vertexOverlay;
			""";

	private static final String ATTRIBUTES = """
			in vec3 flw_position;
			in vec4 flw_color;
			in vec2 flw_texCoord;
			in ivec2 flw_overlay;
			in uvec2 flw_light;
			in vec3 flw_normal;
			""";

	private static final String BUFFERS = """
			uniform usamplerBuffer _flw_instances;
			""";

	private static final String VARYINGS = """
			out vec4 v_color;
			out vec2 v_texCoord;
			out vec2 v_light;
			""";

	private static final String MAIN = """
			void main() {
				// Straight by instance id, for now. Once the cull pass is wired this becomes two
				// hops -- gl_InstanceID counts survivors, a compacted list turns that into the real
				// instance -- which is the arrangement CullSelfTest already draws correctly.
				FlwInstance instance = _flw_unpackInstance(uint(gl_InstanceID));

				flw_vertexPos = vec4(flw_position, 1.0);
				flw_vertexColor = flw_color;
				flw_vertexTexCoord = flw_texCoord;
				flw_vertexOverlay = flw_overlay;
				// Divided by 256, which is the scale a mod's body expects -- Create's rotating
				// shader maxes this against `vec2(instance.light) / 256.`.
				flw_vertexLight = vec2(flw_light) / 256.0;
				flw_vertexNormal = flw_normal;

				flw_instanceVertex(instance);

				// Two different origins meet here, and getting it wrong does not fail, it throws the
				// geometry across the sky. Instance positions are relative to Flywheel's render
				// origin -- a block position that follows the player -- while Minecraft's
				// view-projection expects positions relative to the camera. flw_cameraPos is the
				// camera in render-origin space, so subtracting it converts between the two.
				gl_Position = flw_viewProjection * vec4(flw_vertexPos.xyz - flw_cameraPos.xyz, 1.0);

				v_color = flw_vertexColor;
				v_texCoord = flw_vertexTexCoord;
				v_light = flw_vertexLight;
			}
			""";

	private static final String FRAGMENT = """
			#version 460 core

			in vec4 v_color;
			in vec2 v_texCoord;
			in vec2 v_light;

			uniform sampler2D Sampler0;
			uniform sampler2D Sampler2;

			out vec4 fragColor;

			void main() {
				vec4 texel = texture(Sampler0, v_texCoord) * v_color;

				if (texel.a < 0.01) {
					discard;
				}

				fragColor = texel * texture(Sampler2, clamp(v_light, 0.5 / 16.0, 15.5 / 16.0));
			}
			""";

	private BlazeShaders() {
	}

	/**
	 * Builds both shaders for an instance type and registers them under a name derived from it.
	 *
	 * @param stride the instance stride the CPU writes at, texel-aligned
	 * @return the identifier a pipeline should name
	 * @throws IOException when the mod's shader body, or anything it includes, cannot be read
	 */
	public static Identifier generate(InstanceType<?> type, int stride) throws IOException {
		String body = ShaderIncludes.read(type.vertexShader());

		String vertex = "#version 460 core\n\n"
				+ ATTRIBUTES + "\n"
				+ BlazeUniforms.GLSL + "\n"
				+ BUFFERS + "\n"
				+ InstanceGlsl.struct(type.layout()) + "\n"
				+ InstanceGlsl.texelAccessor(stride) + "\n"
				+ InstanceGlsl.unpack(type.layout()) + "\n"
				+ GLOBALS + "\n"
				+ VARYINGS + "\n"
				+ body + "\n"
				+ MAIN;

		// Named after the shader it was built from, so two instance types cannot collide and the
		// same type does not regenerate under a new name every frame.
		String name = type.vertexShader()
				.getNamespace() + "_"
				+ type.vertexShader()
						.getPath()
						.replace('/', '_')
						.replace('.', '_');

		return GeneratedShaders.pipeline(name, vertex, FRAGMENT);
	}
}
