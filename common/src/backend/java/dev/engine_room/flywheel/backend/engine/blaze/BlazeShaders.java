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
			in vec3 _flw_a_position;
			in vec4 _flw_a_color;
			in vec2 _flw_a_texCoord;
			in ivec2 _flw_a_overlay;
			in uvec2 _flw_a_light;
			in vec3 _flw_a_normal;
			""";

	private static final String BUFFERS = """
			uniform usamplerBuffer _flw_instances;
			uniform usamplerBuffer _flw_visible;
			uniform usamplerBuffer _flw_lightSections;
			uniform usamplerBuffer _flw_lightLut;
			""";

	private static final String VARYINGS = """
			out vec4 v_color;
			out vec2 v_texCoord;
			out vec2 v_light;
			out float v_shade;
			// How far this vertex is from the camera, measured the two ways 26.2's two fogs measure
			// it. Computed here rather than in the fragment shader because the position it is
			// measured from is the one the projection was applied to, and recovering that from a
			// depth value is both harder and wrong at the edges.
			out vec2 v_fogDistance;
			""";

	/**
	 * Vanilla's cardinal face shading: top full, bottom half, north/south 0.8, east/west 0.6.
	 *
	 * <p>The blocky form rather than a smooth dot product, because Create's models are blocky and
	 * this is the shading their textures were drawn to sit under. A smooth one makes every cog look
	 * subtly plastic.
	 */
	private static final String DIFFUSE = """
			float flw_diffuse(vec3 normal) {
				if (normal.y > 0.5) {
					return 1.0;
				}
				if (normal.y < -0.5) {
					return 0.5;
				}
				return abs(normal.z) > abs(normal.x) ? 0.8 : 0.6;
			}
			""";

	/**
	 * The two functions {@code light_lut.glsl} declares and leaves to the backend.
	 *
	 * <p>The OpenGL backend answers them from storage buffers. Here they come from texel buffers,
	 * which is the only shape Blaze3D can describe to a draw -- the lookup logic itself is shared,
	 * unchanged, so the two backends cannot disagree about how a light is found.
	 */
	private static final String LIGHT_ACCESSORS = """
			uint _flw_indexLut(uint index) {
				return texelFetch(_flw_lightLut, int(index)).r;
			}
			
			uint _flw_indexLight(uint index) {
				return texelFetch(_flw_lightSections, int(index)).r;
			}
			""";

	private static final String MAIN = """
			void main() {
				// Two hops, because what gets drawn was decided on the GPU. gl_InstanceID counts
				// survivors of the cull; the compacted list turns that into the real instance. Going
				// straight to the instance buffer would draw the right *number* of things with the
				// wrong data, which looks close enough to right to survive a careless glance.
				uint _flw_index = texelFetch(_flw_visible, gl_InstanceID).r;
				FlwInstance instance = _flw_unpackInstance(_flw_index);

				flw_vertexPos = vec4(_flw_a_position, 1.0);
				flw_vertexColor = _flw_a_color;
				flw_vertexTexCoord = _flw_a_texCoord;
				flw_vertexOverlay = _flw_a_overlay;
				// Divided by 256, which is the scale a mod's body expects -- Create's rotating
				// shader maxes this against `vec2(instance.light) / 256.`.
				flw_vertexLight = vec2(_flw_a_light) / 256.0;
				flw_vertexNormal = _flw_a_normal;

				flw_instanceVertex(instance);

				// The environment's transform, after the body and before the projection, which is
				// where Flywheel's own shaders apply it. Everything mounted on a Create contraption
				// is positioned in the contraption's space rather than the world's, so without this
				// the parts draw where the contraption was assembled while the contraption itself
				// flies away empty. For anything unembedded this is an identity.
				flw_vertexPos = _flw_pose * flw_vertexPos;
				flw_vertexNormal = mat3(_flw_normalA.xyz, _flw_normalB.xyz, _flw_normalC.xyz)
						* flw_vertexNormal;

				// Two different origins meet here, and getting it wrong does not fail, it throws the
				// geometry across the sky. Instance positions are relative to Flywheel's render
				// origin -- a block position that follows the player -- while Minecraft's
				// view-projection expects positions relative to the camera. flw_cameraPos is the
				// camera in render-origin space, so subtracting it converts between the two.
				gl_Position = flw_viewProjection * vec4(flw_vertexPos.xyz - flw_cameraPos.xyz, 1.0);

				v_color = flw_vertexColor;
				v_texCoord = flw_vertexTexCoord;
				v_light = flw_vertexLight;

				// Flywheel's light volume, consulted after the body so a visual that sets its own
				// per-instance light still wins where it is brighter -- which is what a mod's
				// `max(vec2(instance.light) / 256., flw_vertexLight)` is written against.
				//
				// Some visuals have nothing else: Create's track implements ShaderLightVisual and
				// never sets an instance light at all, so without this every curve renders black.
				FlwLightAo _flw_volume;
				if (flw_light(flw_vertexPos.xyz, flw_vertexNormal, _flw_volume)) {
					v_light = max(v_light, _flw_volume.light);
				}

				// Minecraft's per-face brightness, which is what stops a blocky model reading as a
				// flat silhouette. Chunk geometry gets this baked into its vertex colour by the
				// mesher; an instanced model is transformed after that happens, so its shading has
				// to be computed here from the normal the mod's body left behind.
				v_shade = flw_diffuse(normalize(flw_vertexNormal));

				// Spherical for the environmental fog and cylindrical for the render distance one,
				// which is what makes the far edge of the loaded world a flat wall rather than a
				// dome -- the two are not interchangeable and vanilla uses both.
				vec3 _flw_relative = flw_vertexPos.xyz - flw_cameraPos.xyz;
				v_fogDistance = vec2(length(_flw_relative),
						max(length(_flw_relative.xz), abs(_flw_relative.y)));
			}
			""";

	/**
	 * Everything in the fragment shader that is the same whatever the material is.
	 *
	 * <p>The two names it leaves undefined -- {@code flw_discardPredicate} and
	 * {@code flw_fogFilter} -- are the mod-supplied half, exactly as the vertex shader's
	 * {@code flw_instanceVertex} is, and they come from the material rather than the instance type.
	 */
	private static final String FRAGMENT_PREAMBLE = """
			in vec4 v_color;
			in vec2 v_texCoord;
			in vec2 v_light;
			in float v_shade;
			in vec2 v_fogDistance;

			uniform sampler2D Sampler0;
			uniform sampler2D Sampler2;

			out vec4 fragColor;

			// The names the fog shaders are written against. Globals rather than parameters because
			// that is the shape `flw_fogFilter` was given long before this backend existed.
			float flw_distance;
			float flw_cylindricalDistance;
			""";

	/**
	 * The order the steps come in, which is not interchangeable.
	 *
	 * <p>Taken from Flywheel's own {@code common.frag}: the cutout test runs on the plain sampled
	 * colour, before shading and before the lightmap, and the fog filter runs last on everything.
	 * Testing after the lightmap instead would discard a cutout edge in a dark room and keep it in
	 * a lit one, and fogging before the lightmap would light the fog.
	 */
	private static final String FRAGMENT_MAIN = """
			void main() {
				flw_distance = v_fogDistance.x;
				flw_cylindricalDistance = v_fogDistance.y;

				vec4 color = texture(Sampler0, v_texCoord) * v_color;

				if (flw_discardPredicate(color)) {
					discard;
				}

				color.rgb *= v_shade;
				color *= texture(Sampler2, clamp(v_light, 0.5 / 16.0, 15.5 / 16.0));

				fragColor = flw_fogFilter(color);
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
	public static Identifier generate(InstanceType<?> type, int stride, Identifier fog,
			Identifier cutout) throws IOException {
		String body = ShaderIncludes.read(type.vertexShader());

		String vertex = "#version 460 core\n\n"
				+ ATTRIBUTES + "\n"
				+ BlazeUniforms.GLSL + "\n"
				+ BlazeEnvironments.GLSL + "\n"
				+ BUFFERS + "\n"
				+ InstanceGlsl.struct(type.layout()) + "\n"
				+ InstanceGlsl.texelAccessor(stride) + "\n"
				+ InstanceGlsl.unpack(type.layout()) + "\n"
				+ GLOBALS + "\n"
				// The struct the lookup hands back, which lives in a different file to the lookup.
				+ "struct FlwLightAo { vec2 light; float ao; };\n"
				+ LIGHT_ACCESSORS + "\n"
				// Flywheel's own lookup, pasted in rather than reimplemented: it is the definition
				// of how a light is found, and a second copy of that would drift from this one.
				+ ShaderIncludes.read(Identifier.fromNamespaceAndPath("flywheel",
						"internal/light_lut.glsl")) + "\n"
				+ DIFFUSE + "\n"
				+ VARYINGS + "\n"
				+ body + "\n"
				+ MAIN;

		String fragment = "#version 460 core\n\n"
				+ BlazeUniforms.GLSL + "\n"
				+ FRAGMENT_PREAMBLE + "\n"
				+ ShaderIncludes.read(cutout) + "\n"
				+ ShaderIncludes.read(fog) + "\n"
				+ FRAGMENT_MAIN;

		// Named after everything it was built from, so two instance types cannot collide and the
		// same type does not regenerate under a new name every frame. The fog and cutout shaders are
		// in the name because they are compiled into the fragment shader: leaving them out gave every
		// material of one instance type the first material's fog, which in the overworld is no
		// difference at all and in the nether is every machine standing out of a red wall.
		String name = flatten(type.vertexShader()) + "__" + flatten(cutout) + "__" + flatten(fog);

		return GeneratedShaders.pipeline(name, vertex, fragment);
	}

	private static String flatten(Identifier id) {
		return id.getNamespace() + "_" + id.getPath()
				.replace('/', '_')
				.replace('.', '_');
	}
}
