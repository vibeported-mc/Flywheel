package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.FloatBuffer;

import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;

import dev.engine_room.flywheel.backend.compute.Compute;
import dev.engine_room.flywheel.backend.compute.ComputeBackend;
import net.minecraft.client.Minecraft;

/**
 * Can this backend build a depth pyramid at all?
 *
 * <p>Occlusion culling needs one: a mip chain where each texel holds the farthest depth of the
 * region below it, so the cull pass can ask "is anything in this rectangle nearer than my bounding
 * sphere" with a single read. Building it is where 26.2 gets in the way, and this test exists to
 * find out which of the obstacles are real before any culling is written against it.
 *
 * <p>Two questions, and neither can be answered by reading:
 *
 * <ol>
 * <li><b>Is the level's depth texture sampleable?</b> A pyramid is built from it, and a texture that
 * was not created with {@code USAGE_TEXTURE_BINDING} cannot be bound to a shader at all. Minecraft
 * creates the main render target itself and nothing promises what it asked for.
 * <li><b>Can the reduction run as a fragment pass?</b> Blaze3D has no compute-writable images, so a
 * pyramid cannot be reduced the usual way -- each level has to be a small full-screen draw into the
 * next mip. That means a pipeline whose colour target is one mip of a texture it is also sampling
 * another mip of, which is legal and is the sort of thing a driver can still refuse.
 * </ol>
 *
 * <p>The answer comes back as numbers rather than a picture: the smallest mip is copied into a
 * buffer and read. One texel, holding the farthest depth in the whole frame.
 *
 * <p>26.2's depth buffer is reversed -- near is 1, far is 0 -- so "farthest" is the <em>minimum</em>
 * and the reduction takes {@code min}. Getting that backwards does not fail; it builds a pyramid of
 * near depths, and occlusion culling against it hides everything the player can actually see.
 *
 * <h2>This currently fails, and on the reading rather than the building</h2>
 *
 * <p>The first two questions are answered, both yes: the level's depth texture carries
 * {@code USAGE_TEXTURE_BINDING} and can be sampled, and the mip chain reduces through fragment
 * passes without complaint -- ten levels from a 1280x720 depth buffer, on Vulkan, with no error.
 *
 * <p>What is not answered is whether the values it produced are right, because nothing has managed
 * to read them back. {@code copyTextureToBuffer} is asynchronous and reports completion only through
 * a callback, and that callback has never fired here -- checked explicitly, it comes back false --
 * so every read returns memory the copy never wrote, which arrives as NaN. Starting the copy inside
 * the frame and reading it many frames later makes no difference.
 *
 * <p>So the pyramid is left built but unverified, and occlusion culling is <em>not</em> wired to it.
 * That is deliberate. A reduction that is subtly wrong -- a {@code max} where a {@code min} belongs,
 * a level offset by one -- produces a pyramid that looks plausible and makes occlusion culling hide
 * the world, and building the consumer before the producer is trusted is how that ships.
 */
public final class DepthPyramidSelfTest {
	private static final long TIMEOUT_NS = 2_000_000_000L;

	private DepthPyramidSelfTest() {
	}

	public record Result(boolean passed, List<String> lines) {
	}

	public static Result run() {
		List<String> lines = new ArrayList<>();

		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTexture depth = target.getDepthTexture();

		if (depth == null) {
			lines.add("the main render target has no depth texture, so there is nothing to reduce");
			return new Result(false, lines);
		}

		lines.add("main depth texture is " + depth.getWidth(0) + "x" + depth.getHeight(0)
				+ ", usage bits " + Integer.toBinaryString(depth.usage()));

		// The one flag the whole approach rests on. Reported rather than asserted, because a backend
		// that cannot sample the depth buffer needs a different design rather than a failing test.
		boolean sampleable = (depth.usage() & GpuTexture.USAGE_TEXTURE_BINDING) != 0;
		lines.add("depth texture sampleable: " + sampleable);

		if (!sampleable) {
			lines.add("NOT SAMPLEABLE -- a pyramid cannot be built from it by a fragment pass, and "
					+ "occlusion culling would need the depth copied out some other way first");
			return new Result(false, lines);
		}

		// The pyramid the renderer built during its own frame, not one built here.
		//
		// This is the whole point of reading it rather than making one: Minecraft clears the depth
		// buffer every frame, and this method runs between frames, so a pyramid built here reduces a
		// cleared buffer and every texel comes back at the far plane. The first version of this did
		// exactly that and reported a scene of pure sky, which is indistinguishable from a reduction
		// that never sampled anything.
		DepthPyramid pyramid = pyramidFromRenderer();

		if (pyramid == null || pyramid.levels() == 0) {
			lines.add("the renderer has not built a pyramid, so there is nothing to read -- either "
					+ "the Blaze3D backend is not the one running, or no frame has been drawn yet");
			return new Result(false, lines);
		}

		try {
			lines.add("pyramid is " + pyramid.width() + "x" + pyramid.height() + " with "
					+ pyramid.levels() + " levels");

			// A fine level, and the reason is the whole character of a depth pyramid.
			//
			// Every texel holds the *farthest* depth beneath it, so the coarser the level the more
			// certainly it is the far plane: one texel of level five covers thirty-two pixels square,
			// and almost any such region of a real scene contains a scrap of sky. Reading a coarse
			// level and asking "is anything nearer than the far plane" therefore answers no on a
			// perfectly good pyramid -- which is what the first version of this did, and it looked
			// exactly like a reduction that had sampled nothing.
			//
			// Level two covers four pixels square, which is fine enough that solid ground in front of
			// the camera shows up as ground.
			int level = pyramid.sampledLevel();
			Stats stats = read(pyramid, lines);
			if (stats == null) {
				return new Result(false, lines);
			}

			lines.add("level " + level + " (" + stats.texels + " texels): min " + stats.min + ", max "
					+ stats.max + ", " + stats.nearerThanFar + " nearer than the far plane");

			// A depth is a depth: outside [0, 1] means the reduction read something that was not the
			// depth buffer, which is the failure this is really looking for.
			if (!(stats.min >= 0.0f && stats.max <= 1.0f)) {
				lines.add("OUT OF RANGE -- that is not a depth, so the pass sampled the wrong thing");
				return new Result(false, lines);
			}

			// A fraction rather than "any", because one stray texel proves nothing and the scene this
			// runs in has ground across the bottom half of the screen.
			float fraction = (float) stats.nearerThanFar / stats.texels;
			lines.add("fraction nearer than the far plane: " + fraction);

			if (fraction < 0.05f) {
				lines.add("ALL AT THE FAR PLANE -- almost every texel is the far plane, so either the "
						+ "scene really is empty sky or nothing was sampled at all. Occlusion culling "
						+ "against this would hide the world, so it is a failure either way");
				return new Result(false, lines);
			}

			return new Result(true, lines);
		} catch (Exception e) {
			lines.add("THREW while reading the pyramid: " + e);
			return new Result(false, lines);
		}
	}

	private record Stats(int texels, float min, float max, int nearerThanFar) {
	}

	/**
	 * Reads the level the renderer copied out during its own frame.
	 *
	 * <p>Nothing is copied here. {@code copyTextureToBuffer} is asynchronous and reports completion
	 * only through a callback, so a copy started and read in the same breath reads whatever was in
	 * that memory -- which came back as NaN, and the callback confirmed it had never fired. The
	 * renderer starts one every frame instead, and by the time this runs an earlier one has landed.
	 */
	private static @Nullable Stats read(DepthPyramid pyramid, List<String> lines) {
		GpuBuffer sample = pyramid.sample();
		int texels = pyramid.sampleTexels();

		if (sample == null || texels == 0) {
			lines.add("the renderer has not copied a level out yet");
			return null;
		}

		try (GpuBufferSlice.MappedView view = sample.map(true, false)) {
			FloatBuffer values = view.data()
					.asFloatBuffer();

			float min = Float.MAX_VALUE;
			float max = -Float.MAX_VALUE;
			int nearer = 0;

			for (int i = 0; i < texels; i++) {
				float v = values.get(i);
				min = Math.min(min, v);
				max = Math.max(max, v);

				// Reversed depth: the far plane is zero, so anything above it is real geometry.
				if (v > 1.0e-6f) {
					nearer++;
				}
			}

			return new Stats(texels, min, max, nearer);
		} catch (Exception e) {
			lines.add("THREW while reading the sampled level back: " + e);
			return null;
		}
	}

	private static @Nullable DepthPyramid pyramidFromRenderer() {
		// Zero means the renderer never got as far as building one this session, so there is nothing
		// to read and saying so is more useful than reading a texture full of nothing.
		if (BlazeStats.depthPyramidLevels == 0) {
			return null;
		}

		return BlazeEngine.lastDrawManager() == null ? null
				: BlazeEngine.lastDrawManager()
						.depthPyramid();
	}
}
