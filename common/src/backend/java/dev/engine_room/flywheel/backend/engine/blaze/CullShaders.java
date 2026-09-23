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
			};

			layout(std430, FLW_SET(0) binding = 2) writeonly buffer Visible {
				uint _flw_visible[];
			};

			layout(std430, FLW_SET(0) binding = 3) readonly buffer CullParams {
				vec4 _flw_frustum[6];
				vec4 _flw_boundingSphere;
				vec4 _flw_cameraPos;
				uint _flw_instanceCount;
			};
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

			void main() {
				uint i = gl_GlobalInvocationID.x;
				if (i >= _flw_instanceCount) {
					return;
				}

				FlwInstance instance = _flw_unpackInstance(i);

				vec3 center = _flw_boundingSphere.xyz;
				float radius = _flw_boundingSphere.w;
				flw_transformBoundingSphere(instance, center, radius);

				if (!_flw_inFrustum(center, radius)) {
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
	public static String generate(InstanceType<?> type, int stride) throws IOException {
		return "layout(local_size_x = 64) in;\n\n"
				+ BUFFERS + "\n"
				+ InstanceGlsl.struct(type.layout()) + "\n"
				+ InstanceGlsl.storageAccessor(stride) + "\n"
				+ InstanceGlsl.unpack(type.layout()) + "\n"
				+ ShaderIncludes.read(type.cullShader()) + "\n"
				+ MAIN;
	}
}
