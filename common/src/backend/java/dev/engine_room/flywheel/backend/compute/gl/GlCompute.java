package dev.engine_room.flywheel.backend.compute.gl;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import com.mojang.blaze3d.opengl.GlStateManager;

import dev.engine_room.flywheel.backend.compute.ComputePipeline;

/**
 * The OpenGL compute backend.
 *
 * <h2>Why we compile these ourselves</h2>
 *
 * <p>Blaze3D's shader machinery cannot carry a compute stage and cannot be extended to without a
 * fight: {@code ShaderType} is an enum of exactly {@code VERTEX} and {@code FRAGMENT} with a private
 * {@code TYPES} array and exhaustive switches over it in three classes; {@code GlProgram}'s
 * constructor is private and its {@code link} takes exactly a vertex and a fragment shader; and
 * {@code opengl.Uniform} is a sealed interface permitting only samplers, UBOs and texel buffers, so
 * a storage-buffer variant cannot be added to it at all.
 *
 * <p>None of that has to be fought, because none of it is needed. A compute program is built from
 * {@code GlStateManager}'s public statics, and storage buffers are bound with
 * {@code glBindBufferBase}, which is program-independent global state that Blaze3D's uniform
 * bookkeeping never looks at. What we do owe Blaze3D is telling it that we changed the bound
 * program - see {@link GlComputePass#close()}.
 */
public final class GlCompute {
	/**
	 * GLSL versions worth trying, newest first.
	 *
	 * <p>460 gives {@code gl_DrawID} and {@code gl_BaseInstance} as core; 430 is the floor, because
	 * that is where compute shaders and storage buffers were introduced.
	 */
	private static final int[] CANDIDATE_VERSIONS = {460, 450, 440, 430};

	private static boolean probed;
	private static int glslVersion = -1;

	private GlCompute() {
	}

	/**
	 * The highest GLSL version this context will actually compile a compute shader at, or -1 if it
	 * will not compile one at any version.
	 *
	 * <h2>Why this trial-compiles instead of reading the version string</h2>
	 *
	 * <p>Minecraft asks for a 3.3 core forward-compatible context, and on this hardware
	 * {@code GL_SHADING_LANGUAGE_VERSION} duly reports {@code 3.30} - while
	 * {@code GL_MAX_COMPUTE_WORK_GROUP_SIZE} and friends report real values. The strings and the
	 * capabilities disagree, so neither one on its own answers the only question that matters:
	 * <em>will the driver compile the shader we are about to hand it?</em>
	 *
	 * <p>So we ask it to. This is the project's first real gate, and it runs itself.
	 */
	public static int glslVersion() {
		if (!probed) {
			probed = true;
			glslVersion = probeGlslVersion();
		}
		return glslVersion;
	}

	public static boolean available() {
		GlCaps caps = GlCaps.get();
		return caps != null && caps.computeCaps() != null && caps.storageBuffers() && glslVersion() > 0;
	}

	private static int probeGlslVersion() {
		GlCaps caps = GlCaps.get();
		if (caps == null || caps.computeCaps() == null) {
			return -1;
		}
		for (int version : CANDIDATE_VERSIONS) {
			if (compilesAt(version)) {
				return version;
			}
		}
		return -1;
	}

	private static boolean compilesAt(int version) {
		int shader = GlStateManager.glCreateShader(GL43C.GL_COMPUTE_SHADER);
		if (shader == 0) {
			return false;
		}
		try {
			GlShaders.safeShaderSource(shader, """
					#version %d core
					layout(local_size_x = 1) in;
					void main() {}
					""".formatted(version));
			GlStateManager.glCompileShader(shader);
			return GlStateManager.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_TRUE;
		} finally {
			GlStateManager.glDeleteShader(shader);
		}
	}

	public static @Nullable ComputePipeline createPipeline(ComputePipeline.Description description) {
		if (!available()) {
			return null;
		}
		return GlComputePipeline.compile(description, glslVersion());
	}

	public static GlComputePass beginPass(String label) {
		return new GlComputePass(label);
	}

	/**
	 * Block until everything issued so far has finished on the GPU.
	 *
	 * <h2>Why not Blaze3D's fence</h2>
	 *
	 * <p>{@code CommandEncoder.createFence()} looks like the right tool and is not. {@code GlFence}
	 * records the encoder's <em>submit index</em> - a frame counter - and
	 * {@code GlCommandEncoder.awaitSubmit} throws {@code "Cannot wait on a fence for the current
	 * submit"} when asked about the frame that is still being recorded. It answers "has frame N
	 * finished", never "has the work I just issued finished", so it cannot be used to read back a
	 * dispatch's results inside the call that issued them.
	 *
	 * <p>So we place our own sync object. Note this is a debugging and self-test primitive: the
	 * renderer proper never reads results back to the CPU, because the whole point of culling on the
	 * GPU is that the answer stays there.
	 *
	 * @return false if the wait timed out or failed
	 */
	public static boolean awaitGpu(long timeoutNanos) {
		long sync = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		if (sync == 0L) {
			return false;
		}
		try {
			// GL_SYNC_FLUSH_COMMANDS_BIT, so a driver that has not yet flushed does not deadlock us.
			int status = GL32C.glClientWaitSync(sync, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, timeoutNanos);
			return status == GL32C.GL_ALREADY_SIGNALED || status == GL32C.GL_CONDITION_SATISFIED;
		} finally {
			GL32C.glDeleteSync(sync);
		}
	}
}
