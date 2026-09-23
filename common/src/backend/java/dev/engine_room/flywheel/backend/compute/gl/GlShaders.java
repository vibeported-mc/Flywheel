package dev.engine_room.flywheel.backend.compute.gl;

import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Shader-compilation helpers that work around specific drivers. */
public final class GlShaders {
	private GlShaders() {
	}

	/**
	 * {@code glShaderSource} that tells the driver nothing about the string's length.
	 *
	 * <p>Some AMD drivers mis-handle the explicit length and read past the end of the string, which
	 * surfaces as an access violation inside the driver rather than as a compile error. Passing a
	 * null length pointer forces them to rely on the null terminator instead.
	 *
	 * <p>Found by Flywheel, which got it from Canvas, which got it from fewizz.
	 */
	public static void safeShaderSource(int shader, CharSequence source) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			var encoded = MemoryUtil.memUTF8(source, true);
			PointerBuffer pointers = stack.mallocPointer(1);
			pointers.put(encoded);
			GL20C.nglShaderSource(shader, 1, pointers.address0(), 0);
			MemoryUtil.memFree(encoded);
		}
	}
}
