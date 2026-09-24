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
	 * Where a draw went, when it did not go to the GPU.
	 *
	 * <p>An instancer counted above with no calls under it is the shape of failure these exist for:
	 * the instances survived culling and then nothing was issued. Four things can do that and from
	 * outside they are identical -- the picture simply lacks the machine.
	 */
	public static volatile int instancersWithNoDraws;

	public static volatile int drawsEmpty;

	public static volatile int drawsWithoutTexture;

	public static volatile int drawsWithoutPipeline;

	/**
	 * Draws issued for the block-breaking overlay in the last frame that had any.
	 *
	 * <p>Not reset with the others, because crumbling is drawn in its own pass and most frames have
	 * none -- zeroing it every frame would mean a test could only ever read it by winning a race.
	 */
	public static volatile int crumblingCalls;

	/**
	 * Whether the OpenGL indirect backend entered its order-independent chain on the last frame.
	 *
	 * <p>Lives here, in the other backend's package, because it answers a question about this one:
	 * comparing the two pictures says nothing about order independence unless the backend that has
	 * it actually used it. Read by the transparency tests.
	 *
	 * <p>Sticky rather than per-frame. A test samples it over RPC at some arbitrary moment, and the
	 * chain runs only on frames that have order-independent draws in view -- so a per-frame flag
	 * reads false whenever the sample lands on a frame where the fluid was off screen, which is a
	 * race a test cannot win and has nothing to do with what it is asking.
	 */
	public static volatile boolean oitChainRan;

	/** How many levels the depth pyramid was built with last frame, or zero if it was not. */
	public static volatile int depthPyramidLevels;

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
		instancersWithNoDraws = 0;
		drawsEmpty = 0;
		drawsWithoutTexture = 0;
		drawsWithoutPipeline = 0;
		directCalls = 0;
	}
}
