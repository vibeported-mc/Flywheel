package dev.engine_room.flywheel.backend.compute;

import java.util.Map;

/**
 * A compiled compute program.
 *
 * <p>Deliberately not a Blaze3D {@code RenderPipeline}: that type's builder mandates a vertex shader,
 * a fragment shader and a primitive topology, and both its constructor and its builder's constructor
 * are non-public. Compute gets its own type rather than a fight.
 */
public interface ComputePipeline extends AutoCloseable {
	String label();

	/** The workgroup size the shader declared, for sizing dispatches. */
	int localSizeX();

	@Override
	void close();

	/**
	 * How to build one.
	 *
	 * @param label   shown in errors and GPU debug tooling
	 * @param source  GLSL <b>without</b> a {@code #version} line - the backend supplies the prelude,
	 *                which is what lets one source compile for both OpenGL and Vulkan
	 * @param defines preprocessor defines injected after the version line
	 */
	record Description(String label, String source, Map<String, String> defines) {
		public static Description of(String label, String source) {
			return new Description(label, source, Map.of());
		}

		public Description withDefine(String key, String value) {
			return new Description(label, source,
					java.util.stream.Stream.concat(defines.entrySet().stream(), java.util.stream.Stream.of(Map.entry(key, value)))
							.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b)));
		}
	}
}
