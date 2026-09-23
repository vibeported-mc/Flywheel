package dev.engine_room.flywheel.backend.compute.gl;

/**
 * Which OpenGL implementation we are talking to.
 *
 * <p>This is not trivia. Every entry below except {@link #UNKNOWN} has a known defect that has to be
 * worked around by name, because there is no capability bit that reports "this driver is wrong":
 *
 * <ul>
 * <li>{@link #AMD} mis-handles {@code glShaderSource} when given an explicit length, and reads past
 *     the end of the string. Sources must be null-terminated and the length passed as null.
 * <li>{@link #INTEL} renders garbage from multi-draw indirect. Indirect draws have to be issued one
 *     at a time with a base-draw uniform instead.
 * <li>{@link #MESA} reports a subgroup size of 64 by convention when it does not expose
 *     {@code GL_KHR_shader_subgroup}.
 * </ul>
 *
 * <p>All three are inherited from Flywheel, which found them the hard way.
 */
public enum Driver {
	NVIDIA,
	AMD,
	INTEL,
	MESA,
	UNKNOWN;

	public static Driver fromVendorString(String vendor) {
		// The vendor string on AMD hardware has historically read "ATI Technologies Inc."
		if (vendor.contains("ATI") || vendor.contains("AMD")) {
			return AMD;
		} else if (vendor.contains("NVIDIA")) {
			return NVIDIA;
		} else if (vendor.contains("Intel")) {
			return INTEL;
		} else if (vendor.contains("Mesa")) {
			return MESA;
		}
		return UNKNOWN;
	}

	/** Whether {@code glMultiDrawElementsIndirect} can be trusted on this driver. */
	public boolean multiDrawIndirectWorks() {
		return this != INTEL;
	}
}
