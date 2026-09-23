package dev.engine_room.flywheel.backend.compute.gl;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.KHRShaderSubgroup;

import dev.engine_room.flywheel.backend.compute.ComputeCaps;

/**
 * What the live OpenGL context supports.
 *
 * <h2>Why this probes instead of raising the context version</h2>
 *
 * <p>Minecraft asks GLFW for a <b>3.3 core forward-compatible</b> context
 * ({@code GlBackend.setWindowHints}), and the version string it reports back says {@code 3.3.0}.
 * Taken at face value that would rule out compute shaders entirely, since those are 4.3.
 *
 * <p>It does not, because desktop WGL and GLX return the highest core version compatible with the
 * request, and LWJGL picks up the 4.x entry points and ARB extensions that come with it. Measured:
 * Flywheel's indirect backend and Voxy's compute copies both run in exactly this context. So the
 * right move is to ask the driver what it can do rather than to re-hint the window and disturb every
 * other mod's assumptions.
 *
 * <p>Drivers that honour 3.3 literally do exist - Mesa and Intel are the usual ones, and macOS caps
 * at 4.1 with no compute at any version. Those report {@code computeCaps() == null} and take the
 * fallback path.
 */
public final class GlCaps {
	private static boolean probed;
	private static @Nullable GlCaps instance;

	private final Driver driver;
	private final String vendor;
	private final String renderer;
	private final String version;
	private final String glslVersion;
	private final boolean storageBuffers;
	private final boolean multiDrawIndirect;
	private final boolean shaderDrawParameters;
	private final @Nullable ComputeCaps computeCaps;

	private GlCaps(Driver driver, String vendor, String renderer, String version, String glslVersion,
			boolean storageBuffers, boolean multiDrawIndirect, boolean shaderDrawParameters,
			@Nullable ComputeCaps computeCaps) {
		this.driver = driver;
		this.vendor = vendor;
		this.renderer = renderer;
		this.version = version;
		this.glslVersion = glslVersion;
		this.storageBuffers = storageBuffers;
		this.multiDrawIndirect = multiDrawIndirect;
		this.shaderDrawParameters = shaderDrawParameters;
		this.computeCaps = computeCaps;
	}

	/**
	 * Probe the context, once. Must first be called on the render thread with a current context.
	 *
	 * @return null when there is no OpenGL context at all - which happens under VulkanMod, where
	 *         {@link GL#getCapabilities()} throws rather than returning null.
	 */
	public static @Nullable GlCaps get() {
		if (!probed) {
			probed = true;
			instance = probe();
		}
		return instance;
	}

	private static @Nullable GlCaps probe() {
		GLCapabilities caps;
		try {
			caps = GL.getCapabilities();
		} catch (IllegalStateException e) {
			// No GL context on this thread. VulkanMod puts us here.
			return null;
		}
		if (caps == null) {
			return null;
		}

		String vendor = safeString(GL20C.GL_VENDOR);
		String renderer = safeString(GL20C.GL_RENDERER);
		String version = safeString(GL20C.GL_VERSION);
		String glslVersion = safeString(GL20C.GL_SHADING_LANGUAGE_VERSION);

		boolean storageBuffers = caps.OpenGL43 || caps.GL_ARB_shader_storage_buffer_object;
		boolean compute = caps.OpenGL43 || caps.GL_ARB_compute_shader;

		ComputeCaps computeCaps = compute ? probeCompute(caps, storageBuffers) : null;

		return new GlCaps(Driver.fromVendorString(vendor), vendor, renderer, version, glslVersion,
				storageBuffers,
				caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect,
				caps.OpenGL46 || caps.GL_ARB_shader_draw_parameters,
				computeCaps);
	}

	private static ComputeCaps probeCompute(GLCapabilities caps, boolean storageBuffers) {
		// Compute without storage buffers would be useless to us, but the limits are still worth
		// reporting so that a status readout can say exactly which half is missing.
		int alignment = storageBuffers
				? GL43C.glGetInteger(GL43C.GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT)
				: 0;
		int bindings = storageBuffers
				? GL43C.glGetInteger(GL43C.GL_MAX_COMPUTE_SHADER_STORAGE_BLOCKS)
				: 0;

		return new ComputeCaps(
				GL43C.glGetInteger(GL43C.GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS),
				indexedExtent(GL43C.GL_MAX_COMPUTE_WORK_GROUP_COUNT),
				indexedExtent(GL43C.GL_MAX_COMPUTE_WORK_GROUP_SIZE),
				alignment,
				subgroupSize(caps),
				bindings,
				GL43C.glGetInteger(GL43C.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE));
	}

	private static ComputeCaps.Extent indexedExtent(int pname) {
		return new ComputeCaps.Extent(
				GL30C.glGetIntegeri(pname, 0),
				GL30C.glGetIntegeri(pname, 1),
				GL30C.glGetIntegeri(pname, 2));
	}

	/**
	 * The number of threads that execute in lockstep.
	 *
	 * <p>Asked for directly when the driver will say, and otherwise guessed from the vendor - AMD
	 * and Mesa wavefronts are 64 wide, everyone else is 32. Guessing wrong is not a correctness
	 * problem, only an occupancy one.
	 */
	private static int subgroupSize(GLCapabilities caps) {
		if (caps.GL_KHR_shader_subgroup) {
			return GL43C.glGetInteger(KHRShaderSubgroup.GL_SUBGROUP_SIZE_KHR);
		}
		Driver driver = Driver.fromVendorString(safeString(GL20C.GL_VENDOR));
		return switch (driver) {
			case AMD, MESA -> 64;
			default -> 32;
		};
	}

	private static String safeString(int pname) {
		String value = GL20C.glGetString(pname);
		return value == null ? "unknown" : value;
	}

	public Driver driver() {
		return driver;
	}

	public String vendor() {
		return vendor;
	}

	public String renderer() {
		return renderer;
	}

	public String version() {
		return version;
	}

	public String glslVersion() {
		return glslVersion;
	}

	public boolean storageBuffers() {
		return storageBuffers;
	}

	public boolean multiDrawIndirect() {
		return multiDrawIndirect;
	}

	public boolean shaderDrawParameters() {
		return shaderDrawParameters;
	}

	/** Null means this device cannot run compute shaders at all. */
	public @Nullable ComputeCaps computeCaps() {
		return computeCaps;
	}

	/** Everything the indirect path needs, in one question. */
	public boolean supportsIndirect() {
		return computeCaps != null && storageBuffers && multiDrawIndirect && shaderDrawParameters;
	}
}
