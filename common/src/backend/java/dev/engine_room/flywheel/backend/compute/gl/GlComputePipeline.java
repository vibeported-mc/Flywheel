package dev.engine_room.flywheel.backend.compute.gl;

import java.util.Map;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import com.mojang.blaze3d.opengl.GlStateManager;

import dev.engine_room.flywheel.backend.compute.ComputePipeline;

/** A linked OpenGL compute program. */
public final class GlComputePipeline implements ComputePipeline {
	private final String label;
	private final int program;
	private final int localSizeX;
	private boolean closed;

	private GlComputePipeline(String label, int program, int localSizeX) {
		this.label = label;
		this.program = program;
		this.localSizeX = localSizeX;
	}

	static GlComputePipeline compile(Description description, int glslVersion) {
		int shader = GlStateManager.glCreateShader(GL43C.GL_COMPUTE_SHADER);
		if (shader == 0) {
			throw new IllegalStateException("could not create a compute shader object for " + description.label());
		}

		try {
			GlShaders.safeShaderSource(shader, prelude(glslVersion, description.defines()) + description.source());
			GlStateManager.glCompileShader(shader);

			if (GlStateManager.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) != GL11C.GL_TRUE) {
				throw new IllegalStateException("compute shader " + description.label() + " failed to compile:\n"
						+ GlStateManager.glGetShaderInfoLog(shader, 32768));
			}

			int program = GlStateManager.glCreateProgram();
			if (program == 0) {
				throw new IllegalStateException("could not create a program object for " + description.label());
			}

			GlStateManager.glAttachShader(program, shader);
			GlStateManager.glLinkProgram(program);

			if (GlStateManager.glGetProgrami(program, GL20C.GL_LINK_STATUS) != GL11C.GL_TRUE) {
				String log = GlStateManager.glGetProgramInfoLog(program, 32768);
				GlStateManager.glDeleteProgram(program);
				throw new IllegalStateException("compute program " + description.label() + " failed to link:\n" + log);
			}

			int[] workGroupSize = new int[3];
			GL20C.glGetProgramiv(program, GL43C.GL_COMPUTE_WORK_GROUP_SIZE, workGroupSize);

			return new GlComputePipeline(description.label(), program, workGroupSize[0]);
		} finally {
			// The shader object is reference counted by the program, so deleting it here means it
			// goes away with the program rather than leaking until the context dies.
			GlStateManager.glDeleteShader(shader);
		}
	}

	/**
	 * The version line and defines the source deliberately does not carry.
	 *
	 * <p>Shaders are authored without a {@code #version} so that one source can be compiled for
	 * OpenGL and, later, for Vulkan, where the version, the {@code set = } half of every binding
	 * layout, and the {@code gl_VertexIndex} spelling all differ. Keeping that difference in a
	 * generated prelude is what stops it being duplicated into every shader file.
	 */
	private static String prelude(int glslVersion, Map<String, String> defines) {
		StringBuilder out = new StringBuilder(256);
		out.append("#version ").append(glslVersion).append(" core\n");

		// On OpenGL a binding layout carries no descriptor set. On Vulkan it will.
		out.append("#define FLW_GL 1\n");
		out.append("#define FLW_SET(n)\n");

		defines.forEach((key, value) -> out.append("#define ").append(key).append(' ').append(value).append('\n'));

		// So that a compile error points at the author's line 1, not ours.
		out.append("#line 1\n");
		return out.toString();
	}

	int program() {
		return program;
	}

	@Override
	public String label() {
		return label;
	}

	@Override
	public int localSizeX() {
		return localSizeX;
	}

	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;
		GlStateManager.glDeleteProgram(program);
	}
}
