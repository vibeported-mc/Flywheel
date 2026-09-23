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

	/** Only in the crumbling variant, so the ordinary shader carries no varying it never reads. */
	private static final String CRUMBLING_VARYING = """
			out vec2 v_crumblingTexCoord;
			""";

	/** Likewise, and in the fragment shader, where the breaking texture is the extra sampler. */
	private static final String CRUMBLING_FRAGMENT_PREAMBLE = """
			in vec2 v_crumblingTexCoord;

			uniform sampler2D Sampler1;
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
			// The position and normal the mod's body left behind, because lighting is decided per
			// fragment. A mod's light shader is written against `flw_vertexPos` and
			// `flw_vertexNormal` in the fragment stage, so those are what has to arrive there.
			out vec3 v_pos;
			out vec3 v_normal;
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

	/**
	 * Where on the breaking texture a fragment of a model lands.
	 *
	 * <p>Vanilla's block-breaking overlay is a texture projected flat onto the world along whichever
	 * axis a face most nearly points down, so the cracks line up across the faces of a block and do
	 * not swim when it turns. Taken from Flywheel's own {@code common.vert} rather than reinvented,
	 * because a projection that differs by a sign gives cracks that slide across a rotating cog.
	 */
	private static final String CRUMBLING_TEX_COORD = """
			vec2 _flw_crumblingCoord(vec3 pos, vec3 normal) {
				vec3 a = abs(normal);

				if (a.y > a.x && a.y > a.z) {
					return normal.y > 0.0 ? vec2(pos.x, pos.z) : vec2(pos.x, -pos.z);
				}
				if (a.x > a.z) {
					return normal.x > 0.0 ? vec2(pos.z, -pos.y) : vec2(-pos.z, -pos.y);
				}
				return normal.z > 0.0 ? vec2(pos.x, -pos.y) : vec2(-pos.x, -pos.y);
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

			#ifdef FLW_CRUMBLING
				// Before the environment transform and after the body, which is where Flywheel's own
				// vertex shader computes it -- the cracks are projected in the space the model was
				// placed in, so a contraption carries its own cracks around with it rather than
				// dragging them through a projection fixed to the world.
				v_crumblingTexCoord = _flw_crumblingCoord(flw_vertexPos.xyz, flw_vertexNormal);
			#endif

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

				// The light volume is consulted in the fragment shader rather than here, because
				// which lookup to do is the material's choice and its three answers are written
				// against the fragment stage. Doing it here also meant doing it one way for
				// everything, so a material asking for flat lighting got smooth and one asking for
				// smooth-only-when-embedded got it everywhere.
				v_pos = flw_vertexPos.xyz;
				v_normal = flw_vertexNormal;

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
			in vec3 v_pos;
			in vec3 v_normal;

			uniform sampler2D Sampler0;
			uniform sampler2D Sampler2;

			out vec4 fragColor;

			// The names the fog shaders are written against. Globals rather than parameters because
			// that is the shape `flw_fogFilter` was given long before this backend existed.
			float flw_distance;
			float flw_cylindricalDistance;

			// And the names the light shaders are written against, which are the vertex globals
			// again -- a light shader runs in the fragment stage but reads the position and normal
			// the mod's vertex body left behind.
			vec4 flw_vertexPos;
			vec3 flw_vertexNormal;
			vec2 flw_fragLight;
			vec4 flw_fragColor;

			// The one field of Flywheel's material struct that a light shader reads. Declared as a
			// struct rather than a bare bool because `flw_material.ambientOcclusion` is the spelling
			// the shipped light shaders use, and those are pasted in unchanged.
			struct FlwMaterial {
				bool ambientOcclusion;
			};

			FlwMaterial flw_material;
			""";

	/**
	 * The order the steps come in, which is not interchangeable.
	 *
	 * <p>Taken from Flywheel's own {@code common.frag}: the cutout test runs on the plain sampled
	 * colour, before shading and before the lightmap, and the fog filter runs last on everything.
	 * Testing after the lightmap instead would discard a cutout edge in a dark room and keep it in
	 * a lit one, and fogging before the lightmap would light the fog.
	 */
	/**
	 * The block-breaking overlay, which is a different fragment shader rather than a material swap.
	 *
	 * <p>It takes the model's alpha and the breaking texture's colour, and nothing else: no diffuse
	 * shading, no lightmap, no fog. The overlay is meant to read as cracks drawn *on* the surface,
	 * and a lit, shaded, fogged copy of it reads as a second object floating just above one.
	 *
	 * <p>The alpha is multiplied rather than replaced so the cracks stop where the model does --
	 * without that, a cog's breaking overlay is an opaque square.
	 */
	private static final String CRUMBLING_FRAGMENT_MAIN = """
			void main() {
				vec4 color = texture(Sampler0, v_texCoord) * v_color;
				vec4 cracks = texture(Sampler1, v_crumblingTexCoord);

				color.rgb = cracks.rgb;
				color.a *= cracks.a;

				if (flw_discardPredicate(color)) {
					discard;
				}

				fragColor = color;
			}
			""";

	private static final String FRAGMENT_MAIN = """
			void main() {
				flw_distance = v_fogDistance.x;
				flw_cylindricalDistance = v_fogDistance.y;
				flw_vertexPos = vec4(v_pos, 1.0);
				flw_vertexNormal = v_normal;

				flw_fragColor = texture(Sampler0, v_texCoord) * v_color;
				flw_fragLight = v_light;
				flw_material.ambientOcclusion = FLW_AMBIENT_OCCLUSION;

				// The material's own light lookup, which is one of three and is the material's choice
				// rather than the backend's. It takes a max against the light the instance already
				// carried, so a visual that sets its own light keeps it wherever that is brighter --
				// and a visual that sets none, like Create's track, gets all of its light from here.
				//
				// It can also darken the colour, which is what ambient occlusion is.
				flw_shaderLight();

				vec4 color = flw_fragColor;

				if (flw_discardPredicate(color)) {
					discard;
				}

				color.rgb *= v_shade;
				color *= texture(Sampler2, clamp(flw_fragLight, 0.5 / 16.0, 15.5 / 16.0));

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
		return generate(type, stride, fog, cutout,
				Identifier.fromNamespaceAndPath("flywheel", "light/smooth.glsl"), true, false, false);
	}

	/**
	 * @param light the material's light lookup, one of Flywheel's three, pasted in unchanged
	 * @param ambientOcclusion whether that lookup is allowed to darken the colour
	 * @param embedded whether these instances carry their own coordinate space, which is the one
	 *            thing {@code smooth_when_embedded} asks about
	 * @param crumbling the block-breaking variant, which projects the breaking texture over the model
	 *            and drops the lighting and fog that would make it read as a separate object
	 */
	public static Identifier generate(InstanceType<?> type, int stride, Identifier fog,
			Identifier cutout, Identifier light, boolean ambientOcclusion, boolean embedded,
			boolean crumbling) throws IOException {
		String body = ShaderIncludes.read(type.vertexShader());

		String vertex = "#version 460 core\n\n"
				+ (crumbling ? "#define FLW_CRUMBLING\n" + CRUMBLING_VARYING + CRUMBLING_TEX_COORD
						+ "\n" : "")
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
				// A compile-time flag because that is how the shipped shader asks the question, and
				// the shipped shader is pasted in unchanged. It costs one extra pipeline per
				// instance type at worst: embedded is a property of the instancer, not the draw.
				+ (embedded ? "#define FLW_EMBEDDED\n" : "")
				+ "#define FLW_AMBIENT_OCCLUSION " + ambientOcclusion + "\n"
				+ BlazeUniforms.GLSL + "\n"
				+ FRAGMENT_PREAMBLE + "\n"
				+ (crumbling ? CRUMBLING_FRAGMENT_PREAMBLE + "\n" : "")
				+ BUFFERS + "\n"
				+ "struct FlwLightAo { vec2 light; float ao; };\n"
				+ LIGHT_ACCESSORS + "\n"
				+ ShaderIncludes.read(Identifier.fromNamespaceAndPath("flywheel",
						"internal/light_lut.glsl")) + "\n"
				+ ShaderIncludes.read(light) + "\n"
				+ ShaderIncludes.read(cutout) + "\n"
				+ ShaderIncludes.read(fog) + "\n"
				+ (crumbling ? CRUMBLING_FRAGMENT_MAIN : FRAGMENT_MAIN);

		// Named after everything it was built from, so two instance types cannot collide and the
		// same type does not regenerate under a new name every frame. The fog and cutout shaders are
		// in the name because they are compiled into the fragment shader: leaving them out gave every
		// material of one instance type the first material's fog, which in the overworld is no
		// difference at all and in the nether is every machine standing out of a red wall.
		String name = flatten(type.vertexShader()) + "__" + flatten(cutout) + "__" + flatten(fog)
				+ "__" + flatten(light) + (ambientOcclusion ? "_ao" : "")
				+ (embedded ? "__embedded" : "") + (crumbling ? "__crumbling" : "");

		return GeneratedShaders.pipeline(name, vertex, fragment);
	}

	private static String flatten(Identifier id) {
		return id.getNamespace() + "_" + id.getPath()
				.replace('/', '_')
				.replace('.', '_');
	}
}
