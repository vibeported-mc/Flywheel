package dev.engine_room.flywheel.backend.compute.vk;

import java.nio.ByteBuffer;
import java.util.Map;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

/**
 * GLSL to SPIR-V, for compute.
 *
 * <p>Blaze3D already carries shaderc and already compiles its own shaders with it, but its
 * {@code GlslCompiler} decides the shader kind with {@code type == FRAGMENT ? 1 : 0} - there is no
 * third case, because {@code ShaderType} has no third constant. Rather than extend that enum and fix
 * every exhaustive switch over it, compute goes straight to shaderc with the kind it needs.
 */
public final class VkSpirv {
	/** shaderc_compute_shader. */
	private static final int COMPUTE_KIND = 2;

	private VkSpirv() {
	}

	/**
	 * Compile a compute shader.
	 *
	 * @return SPIR-V words in a native buffer the caller must free with {@link MemoryUtil#memFree}
	 * @throws IllegalStateException with the compiler's own message when the source does not compile
	 */
	public static ByteBuffer compile(String label, String source, Map<String, String> defines) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		if (compiler == 0L) {
			throw new IllegalStateException("could not start shaderc");
		}

		long options = Shaderc.shaderc_compile_options_initialize();
		if (options == 0L) {
			Shaderc.shaderc_compiler_release(compiler);
			throw new IllegalStateException("could not create shaderc options");
		}

		try {
			// Vulkan 1.3. Blaze3D targets the same, so our modules sit alongside its own.
			Shaderc.shaderc_compile_options_set_target_env(options,
					Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_3);
			Shaderc.shaderc_compile_options_set_optimization_level(options,
					Shaderc.shaderc_optimization_level_performance);

			defines.forEach((key, value) ->
					Shaderc.shaderc_compile_options_add_macro_definition(options, key, value));

			long result = Shaderc.shaderc_compile_into_spv(compiler, source, COMPUTE_KIND,
					label, "main", options);
			if (result == 0L) {
				throw new IllegalStateException("shaderc returned nothing for " + label);
			}

			try {
				if (Shaderc.shaderc_result_get_compilation_status(result)
						!= Shaderc.shaderc_compilation_status_success) {
					throw new IllegalStateException("compute shader " + label + " failed to compile:\n"
							+ Shaderc.shaderc_result_get_error_message(result));
				}

				ByteBuffer spirv = Shaderc.shaderc_result_get_bytes(result);
				if (spirv == null) {
					throw new IllegalStateException("shaderc produced no SPIR-V for " + label);
				}

				// Copied out because the result is released below and the buffer belongs to it.
				ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
				MemoryUtil.memCopy(spirv, copy);
				return copy;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}
}
