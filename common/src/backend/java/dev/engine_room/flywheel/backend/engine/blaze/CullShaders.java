package dev.engine_room.flywheel.backend.engine.blaze;

import java.io.IOException;

import dev.engine_room.flywheel.api.instance.InstanceType;

/**
 * The compute shader that decides which instances are worth drawing.
 *
 * <p>Built from the same layout as the vertex shader and from a second body the mod supplies:
 * {@code flw_transformBoundingSphere}, which moves a model's bounding sphere the way that instance
 * type moves its geometry. Create's rotating shader widens the sphere to cover the sweep of a
 * spinning part; its scrolling one only translates. Without it a culler would test where the model
 * sits in its own space rather than where the instance put it, and machines would wink out while
 * plainly on screen.
 *
 * <p>The instance data is read through the same generated unpacking the draw uses, from a storage
 * buffer rather than a texel buffer -- which is what {@code InstanceGlsl}'s two accessors are for.
 * One description of the layout, two ways of reaching it, so the culler and the draw cannot come to
 * different conclusions about where an instance is.
 */
public final class CullShaders {
	/**
	 * The buffers, declared before anything that reads them.
	 *
	 * <p>Separate from the body because the generated accessor names {@code _flw_instances}, and
	 * GLSL will not accept a name before it is declared -- the first version of this put the
	 * declarations last and every cull shader failed with "undeclared identifier" for a buffer
	 * plainly in the file.
	 */
	private static final String BUFFERS = """
			layout(std430, FLW_SET(0) binding = 0) readonly buffer Instances {
				uint _flw_instances[];
			};

			layout(std430, FLW_SET(0) binding = 1) buffer Counts {
				uint _flw_visibleCount;

				// Where instances drop out, which is the only way to tell a test that culls nothing
				// from a test that is never reached. Every one of these is incremented at the point
				// the instance stops, so they add up to the number tested.
				uint _flw_tested;
				uint _flw_outOfFrustum;
				uint _flw_tooClose;
				uint _flw_offScreen;
				uint _flw_occludedCount;

				// Diagnostic: the largest value each side of the occlusion comparison ever took,
				// scaled by a million because atomicMax wants an integer. Reads of zero for the
				// first mean the pyramid is not reaching the shader at all.
				uint _flw_maxFurthest;
				uint _flw_maxHiZ;
			};

			layout(std430, FLW_SET(0) binding = 2) writeonly buffer Visible {
				uint _flw_visible[];
			};

			layout(std430, FLW_SET(0) binding = 3) readonly buffer CullParams {
				vec4 _flw_frustum[6];
				vec4 _flw_boundingSphere;
				vec4 _flw_cameraPos;
				mat4 _flw_viewProjection;
				// Where this instancer's space currently is: identity in the world, and the
				// contraption's pose for anything riding one.
				mat4 _flw_environmentPose;
				// xy: the size of the pyramid's first level, z: how many levels, w: whether to use
				// it at all. Zero in w turns occlusion culling off without a second shader.
				vec4 _flw_pyramid;
				uint _flw_instanceCount;
			};

			// The depth of what was drawn last frame, as a mip chain. Sampled rather than read out
			// of a buffer, which is not a stylistic choice: copyTextureToBuffer does not deliver on
			// 26.2, so an image binding is the only way a compute shader can see this at all.
			layout(FLW_SET(0) binding = 6) uniform sampler2D _flw_depthPyramid;
			""";

	/**
	 * The frustum test, and the compaction.
	 *
	 * <p>Survivors claim slots with an atomic, so the list comes out packed rather than sparse and
	 * the draw can walk it with {@code gl_InstanceID}. Their order is whatever order the GPU
	 * happened to run them in, which does not matter: they are all the same model with the same
	 * material, and nothing downstream cares which came first.
	 */
	private static final String MAIN = """
			/**
			 * Moves a bounding sphere by a matrix, growing it by the matrix's largest axis scale.
			 *
			 * The largest rather than an average: a sphere that no longer contains its geometry culls
			 * things that are plainly on screen, where erring large only costs a draw.
			 */
			void _flw_transformBoundingSphere(mat4 pose, inout vec3 center, inout float radius) {
				center = (pose * vec4(center, 1.0)).xyz;

				float x = length(pose[0].xyz);
				float y = length(pose[1].xyz);
				float z = length(pose[2].xyz);

				radius *= max(max(x, y), z);
			}

			bool _flw_inFrustum(vec3 center, float radius) {
				for (int i = 0; i < 6; i++) {
					// Positions are relative to the render origin and the planes are relative to the
					// camera, which is the same space the draw works in.
					if (dot(_flw_frustum[i].xyz, center - _flw_cameraPos.xyz) + _flw_frustum[i].w
							< -radius) {
						return false;
					}
				}
				return true;
			}

			/**
			 * Whether everything already drawn in front of this sphere hides it.
			 *
			 * The pyramid holds, per texel, the range of depths beneath it -- .g being the farthest
			 * surface drawn there. If the nearest point of the sphere is further away than the
			 * farthest thing already drawn across the whole rectangle it covers, nothing of it can
			 * be seen.
			 *
			 * Deliberately conservative at every step: a sphere rather than the real shape, its
			 * whole screen rectangle rather than its silhouette, and a level coarse enough that the
			 * rectangle spans about a texel. Every one of those errs toward "visible", and a wrong
			 * "visible" costs only the work of drawing something hidden -- where a wrong "hidden"
			 * takes machinery off the screen in front of the player.
			 */
			bool _flw_occluded(vec3 center, float radius) {
				if (_flw_pyramid.w < 0.5) {
					return false;
				}
			#ifdef FLW_CULL_EVERYTHING
				// Diagnostic: claims everything is hidden. If the machinery does not vanish, the
				// pyramid never reached this shader and the test logic is not what is wrong.
				return true;
			#endif

				// The same space the draw works in: positions are relative to the render origin and
				// the view-projection expects them relative to the camera.
				vec3 rel = center - _flw_cameraPos.xyz;

				vec3 lo = vec3(1e30);
				vec3 hi = vec3(-1e30);

				for (int corner = 0; corner < 8; corner++) {
					vec3 offset = vec3(
							(corner & 1) == 0 ? -radius : radius,
							(corner & 2) == 0 ? -radius : radius,
							(corner & 4) == 0 ? -radius : radius);

					vec4 clip = _flw_viewProjection * vec4(rel + offset, 1.0);

					// Straddling the camera plane: the projection is meaningless there, and
					// something that close is not hidden by anything.
					if (clip.w <= 0.0001) {
						atomicAdd(_flw_tooClose, 1u);
						return false;
					}

					vec3 ndc = clip.xyz / clip.w;
					lo = min(lo, ndc);
					hi = max(hi, ndc);
				}

				vec2 uvLo = lo.xy * 0.5 + 0.5;
				vec2 uvHi = hi.xy * 0.5 + 0.5;

				// Partly off screen counts as visible; the frustum test has already had its say and
				// the pyramid says nothing about what lies outside it.
				if (any(lessThan(uvLo, vec2(0.0))) || any(greaterThan(uvHi, vec2(1.0)))) {
					atomicAdd(_flw_offScreen, 1u);
					return false;
				}

				// Coarse enough that the rectangle is about one texel across, so four reads cover it.
				vec2 spanInTexels = (uvHi - uvLo) * _flw_pyramid.xy;
				float level = clamp(ceil(log2(max(max(spanInTexels.x, spanInTexels.y), 1.0))),
						0.0, _flw_pyramid.z - 1.0);

				int lod = int(level);
				ivec2 size = textureSize(_flw_depthPyramid, lod);
				ivec2 a = clamp(ivec2(uvLo * vec2(size)), ivec2(0), size - 1);
				ivec2 b = clamp(ivec2(uvHi * vec2(size)), ivec2(0), size - 1);

				// The .r channel, which is the smallest depth under each texel -- and on a reversed
				// buffer the smallest is the *farthest*. Taking the minimum of four of them gives
				// the farthest surface drawn anywhere in the rectangle.
				float furthestDrawn = min(
						min(texelFetch(_flw_depthPyramid, a, lod).r,
								texelFetch(_flw_depthPyramid, ivec2(b.x, a.y), lod).r),
						min(texelFetch(_flw_depthPyramid, ivec2(a.x, b.y), lod).r,
								texelFetch(_flw_depthPyramid, b, lod).r));

				// Reversed depth: larger is nearer, so the sphere's nearest point is the largest of
				// its projected depths, and it is hidden when even that is behind everything drawn.
				//
				// The magnitudes here mislead. With the near plane a twentieth of a block away, z
				// works out as near/distance, so a whole ordinary scene lives between about 0.002
				// and 0.03 -- which reads as "small numbers, therefore near is zero" and is exactly
				// backwards. The tell is the sky: it comes back as precisely 0.0, and the sky is the
				// far plane.
				atomicMax(_flw_maxFurthest, uint(max(furthestDrawn, 0.0) * 1000000.0));
					atomicMax(_flw_maxHiZ, uint(max(hi.z, 0.0) * 1000000.0));

					return hi.z < furthestDrawn;
			}

			void main() {
				uint i = gl_GlobalInvocationID.x;
				if (i >= _flw_instanceCount) {
					return;
				}

				FlwInstance instance = _flw_unpackInstance(i);

				vec3 center = _flw_boundingSphere.xyz;
				float radius = _flw_boundingSphere.w;
				flw_transformBoundingSphere(instance, center, radius);

				// And then out of this instancer's space into the world, which is the space both the
				// frustum planes and the depth pyramid are in. Identity for anything not riding a
				// contraption, so this costs a matrix multiply and changes nothing.
				_flw_transformBoundingSphere(_flw_environmentPose, center, radius);

				atomicAdd(_flw_tested, 1u);

				if (!_flw_inFrustum(center, radius)) {
					atomicAdd(_flw_outOfFrustum, 1u);
					return;
				}

				if (_flw_occluded(center, radius)) {
					atomicAdd(_flw_occludedCount, 1u);
					return;
				}

				_flw_visible[atomicAdd(_flw_visibleCount, 1u)] = i;
			}
			""";

	/**
	 * Turns the count the cull pass arrived at into draw commands.
	 *
	 * <p>One command per mesh of the model, all drawing the same survivors, because every mesh of
	 * one model is drawn for every instance of it. {@code firstInstance} is always zero: Blaze3D's
	 * Vulkan device never enables {@code drawIndirectFirstInstance}, and a command carrying a
	 * non-zero one is dropped in silence with its counts still reading back correctly.
	 */
	public static final String APPLY = """
			layout(local_size_x = 1) in;

			layout(std430, FLW_SET(0) binding = 0) writeonly buffer Commands {
				uint _flw_command[];
			};

			// x: indexCount, y: firstIndex, z: vertexOffset
			layout(std430, FLW_SET(0) binding = 1) readonly buffer DrawParams {
				uvec4 _flw_params[];
			};

			layout(std430, FLW_SET(0) binding = 2) readonly buffer Counts {
				uint _flw_visibleCount;
			};

			void main() {
				uint i = gl_GlobalInvocationID.x;
				uvec4 p = _flw_params[i];

				_flw_command[i * 5u + 0u] = p.x;
				_flw_command[i * 5u + 1u] = _flw_visibleCount;
				_flw_command[i * 5u + 2u] = p.y;
				_flw_command[i * 5u + 3u] = p.z;
				_flw_command[i * 5u + 4u] = 0u;
			}
			""";

	private CullShaders() {
	}

	/** @param stride the instance stride the CPU writes at */
	/**
	 * Makes the occlusion test claim everything is hidden.
	 *
	 * <p>For telling "the pyramid never reached the shader" from "the test never returns true",
	 * which look identical from outside: in both cases nothing is culled. With this on the machinery
	 * disappears, which is how the plumbing was confirmed working before the test itself was
	 * trusted.
	 */
	private static final boolean CULL_EVERYTHING = false;

	public static String generate(InstanceType<?> type, int stride) throws IOException {
		return (CULL_EVERYTHING ? "#define FLW_CULL_EVERYTHING\n" : "")
				+ "layout(local_size_x = 64) in;\n\n"
				+ BUFFERS + "\n"
				+ InstanceGlsl.struct(type.layout()) + "\n"
				+ InstanceGlsl.storageAccessor(stride) + "\n"
				+ InstanceGlsl.unpack(type.layout()) + "\n"
				+ ShaderIncludes.read(type.cullShader()) + "\n"
				+ MAIN;
	}
}
