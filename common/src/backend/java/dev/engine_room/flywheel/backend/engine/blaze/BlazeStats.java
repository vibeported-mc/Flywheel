package dev.engine_room.flywheel.backend.engine.blaze;

/**
 * What the last frame actually did, so a test can tell the fast path from the fallback.
 *
 * <p>This backend degrades quietly on purpose: an instance type whose cull shader will not build
 * still draws, through {@code drawIndexed} over the identity list, and the picture is identical. That
 * is the right behaviour in a game and the wrong behaviour in a test, where a screenshot of a
 * correctly drawn world would then pass while the entire point of the backend -- the GPU deciding
 * what to draw -- was never exercised.
 *
 * <p>So the counts are published. {@link #indirectInstancers} being zero on a frame with machines in
 * it means every one of them fell back, which no screenshot would ever show.
 *
 * <p>Plain volatile fields rather than a snapshot object: they are written on the render thread once
 * a frame and read over RPC from a test, and a torn read of one of four ints is not worth allocating
 * against. Nothing in the renderer reads them.
 */
public final class BlazeStats {
	/** Instancers whose draws came from commands a compute pass wrote. */
	public static volatile int indirectInstancers;

	/** Instancers drawn the plain way, because their cull shader is unavailable. */
	public static volatile int directInstancers;

	/** Indirect calls issued, which is fewer than the draws when consecutive ones batch. */
	public static volatile int indirectCalls;

	public static volatile int directCalls;

	/**
	 * The fog ranges the last frame's uniforms were written with, in blocks.
	 *
	 * <p>Published because fog is invisible in the scenes worth testing in: at ten blocks in the
	 * overworld the filter runs and changes nothing, so a correct screenshot and a fragment shader
	 * that ignores fog entirely look exactly alike. These are the numbers the shader read, rather
	 * than the numbers vanilla holds -- a backend that fills its uniform block wrongly would agree
	 * with vanilla and still draw no fog.
	 */
	public static volatile float fogEnvironmentalStart;

	public static volatile float fogEnvironmentalEnd;

	public static volatile float fogRenderDistanceStart;

	public static volatile float fogRenderDistanceEnd;

	private BlazeStats() {
	}

	static void reset() {
		indirectInstancers = 0;
		directInstancers = 0;
		indirectCalls = 0;
		directCalls = 0;
	}
}
