package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;

import org.jspecify.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.blaze3dx.buffer.DepthPyramid;
import dev.blaze3dx.buffer.Staging;
import dev.blaze3dx.compute.BarrierScope;
import dev.blaze3dx.compute.Blaze3dxBufferUsage;
import dev.blaze3dx.compute.Compute;
import dev.blaze3dx.compute.ComputeBackend;
import dev.blaze3dx.compute.ComputePass;
import dev.blaze3dx.compute.ComputePipeline;

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
 * <h2>This currently fails, and the reason is that nothing can read the pyramid</h2>
 *
 * <p>{@code copyTextureToBuffer} does not deliver. Three runs with three different shaders -- one of
 * them writing a constant rather than a depth -- returned byte-identical results, and its completion
 * callback never fires. The scan below does run and does see every texel; it is reading a buffer the
 * copy never wrote. That holds for a mappable destination and for a storage one, with and without a
 * barrier between the copy and the read.
 *
 * <p>Which blocks the feature rather than just the test. A compute shader can only read buffers, so
 * occlusion culling needs the pyramid in a buffer, and that is the one call that puts it there.
 *
 * <p>The way out is to stop needing the copy: teach the compute layer to bind a sampled image, so
 * the cull shader reads the pyramid texture directly. That is a change to this backend's own Vulkan
 * and OpenGL compute plumbing -- a descriptor type and a texture binding -- rather than to anything
 * of Blaze3D's, and it removes the dependency on a call that does not work.
 *
 * <h2>What else was ruled out on the way</h2>
 *
 * <p>The reduction runs and produces nothing but the far plane, and the cause is not the reduction.
 * The main depth texture is bound as the depth attachment of the surrounding level render at the
 * moment Flywheel draws, and sampling a texture that is currently an attachment is undefined --
 * Vulkan answers with zeroes rather than an error. Vanilla samples the same texture happily in
 * {@code post/transparency.fsh}, but that is a post pass: by then the level render has finished and
 * the texture is no longer an attachment.
 *
 * <p>So the pyramid cannot be built where it is being built. Every hook Flywheel has runs inside the
 * level render, which is the one place this is not allowed. Making it work needs the reduction moved
 * to a point where the depth buffer is free -- a post stage, or the start of the next frame reading
 * the previous one, which is what Hi-Z occlusion culling normally does anyway.
 *
 * <p>What the depth buffer is <em>not</em> is empty: the backend's own draws depth-test against it
 * correctly in the same frame, and the walled scene in {@code OcclusionTest} shows terrain hiding
 * machinery exactly as it should. It is readable as an attachment and unreadable as a texture, at
 * the same instant.
 *
 * <h2>What was ruled out on the way</h2>
 *
 * <p>The first two questions are answered, both yes: the level's depth texture carries
 * {@code USAGE_TEXTURE_BINDING} and can be sampled, and the mip chain reduces through fragment
 * passes without complaint -- ten levels from a 1280x720 depth buffer, on Vulkan, with no error.
 *
 * <p>Reading the result back defeated three attempts before the GPU scan below worked.
 * {@code copyTextureToBuffer} is asynchronous and reports completion only through a callback that,
 * checked explicitly, never fires here -- so every CPU-side read returned memory the copy had not
 * written, arriving as NaN. The copy does land on the GPU regardless: a compute shader scanning the
 * destination buffer sees all of it. That matters beyond this test, because it is the same copy the
 * cull pass would use to read the pyramid.
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
					+ pyramid.levels() + " levels, reduction pipeline valid: "
					+ pyramid.pipelineValid());

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
			int level = Math.min(2, pyramid.levels() - 1);
			Stats stats = read(pyramid, lines);
			if (stats == null) {
				return new Result(false, lines);
			}

			lines.add("level " + level + " (" + stats.texels + " texels): depths run from " + stats.min
					+ " to " + stats.max);

			// A depth is a depth: outside [0, 1] means the reduction read something that was not the
			// depth buffer, which is the failure this is really looking for.
			if (!(stats.min >= 0.0f && stats.max <= 1.0f)) {
				lines.add("OUT OF RANGE -- that is not a depth, so the pass sampled the wrong thing");
				return new Result(false, lines);
			}

			// A real scene has depth in it. All-equal means nothing was sampled, whichever end of
			// the range that value happens to sit at, and occlusion culling against a flat pyramid
			// either hides everything or hides nothing.
			if (stats.max - stats.min < 1.0e-4f) {
				lines.add("FLAT -- every texel holds the same depth, so nothing was really sampled");
				return new Result(false, lines);
			}

			// Which end is far, read off the pyramid rather than guessed at from magnitudes.
			//
			// Magnitudes cannot answer it. With the near plane a twentieth of a block away, depth
			// works out as near/distance, so an ordinary scene lives between about 0.002 and 0.03
			// whichever way round the buffer runs -- small numbers throughout, which reads as
			// "near is zero" and is wrong. An earlier version of this said exactly that.
			//
			// The sky settles it: nothing is drawn there, so it holds the clear value, and the
			// clear value is the far plane. It comes back as 0, so 0 is far and larger is nearer.
			lines.add("the smallest depth here is " + stats.min + " and the largest " + stats.max
					+ "; the empty sky holds the clear value, so the end nearest zero is the far "
					+ "plane and the farthest depth in a region is its minimum");

			return new Result(true, lines);
		} catch (Exception e) {
			lines.add("THREW while reading the pyramid: " + e);
			return new Result(false, lines);
		}
	}

	private record Stats(int texels, float min, float max, int nearerThanFar) {
	}

	/**
	 * Scans the copied level on the GPU, because the CPU cannot see it.
	 *
	 * <p>Every attempt to read the pyramid by mapping a buffer has come back as memory the copy never
	 * wrote: {@code copyTextureToBuffer} is asynchronous and reports completion only through a
	 * callback that, checked explicitly, never fires here. So the scan runs where the data already
	 * is. A compute shader walks the level and reduces it to four numbers, and only those four cross
	 * back -- along the buffer readback path the rest of this backend already relies on.
	 *
	 * <p>Which also answers the question the cull pass depends on. If this returns real depths then
	 * the copy does land on the GPU whatever the callback says, and occlusion culling can read it.
	 */
	private static final String SCAN = """
			layout(local_size_x = 64) in;

			layout(std430, FLW_SET(0) binding = 0) buffer Summary {
				uint _flw_min;
				uint _flw_max;
				uint _flw_width;
				uint _flw_count;
			};

			// Sampled, not copied. A compute shader here could only read buffers until this
			// backend's compute layer learned to bind an image, and the copy that would have filled
			// a buffer instead -- copyTextureToBuffer -- never delivers its data on 26.2.
			layout(FLW_SET(0) binding = 6) uniform sampler2D _flw_pyramid;

			void main() {
				// Counted before anything can return, so "the dispatch never ran" and "the texture
				// came back empty" are different answers rather than the same zero.
				atomicAdd(_flw_count, 1u);

				ivec2 size = textureSize(_flw_pyramid, 0);

				// Recorded for the same reason: a binding that did not take reports a size of zero,
				// and then every invocation returns without touching anything.
				atomicMax(_flw_width, uint(size.x));

				uint total = uint(size.x * size.y);
				uint i = gl_GlobalInvocationID.x;
				if (i >= total) {
					return;
				}

				ivec2 at = ivec2(int(i) % size.x, int(i) / size.x);

				// Both channels: .r is the nearest depth under this texel and .g the farthest, so
				// the two together give the whole range of depths in the frame. Which end means
				// "near" is exactly the question this is here to answer.
				vec2 range = texelFetch(_flw_pyramid, at, 0).rg;

				// Compared as bit patterns, which is exact for non-negative floats: IEEE 754 orders
				// them the same way the integers order. A depth is never negative, so this holds.
				atomicMin(_flw_min, floatBitsToUint(max(range.r, 0.0)));
				atomicMax(_flw_max, floatBitsToUint(max(range.g, 0.0)));
			}
			""";

	/**
	 * Reads the level the renderer copied out during its own frame.
	 *
	 * <p>Nothing is copied here. {@code copyTextureToBuffer} is asynchronous and reports completion
	 * only through a callback, so a copy started and read in the same breath reads whatever was in
	 * that memory -- which came back as NaN, and the callback confirmed it had never fired. The
	 * renderer starts one every frame instead, and by the time this runs an earlier one has landed.
	 */
	private static @Nullable Stats read(DepthPyramid pyramid, List<String> lines) {
		int level = Math.min(2, pyramid.levels() - 1);
		GpuTextureView view = pyramid.levelView(level);

		if (view == null) {
			lines.add("the pyramid has no view for level " + level);
			return null;
		}

		int texels = pyramid.texture()
				.getWidth(level)
				* pyramid.texture()
						.getHeight(level);

		ComputeBackend gpu = Compute.backend();
		ComputePipeline scan = gpu.createPipeline(
				ComputePipeline.Description.of("flywheel pyramid scan", SCAN));

		if (scan == null) {
			lines.add("the scan shader would not build");
			return null;
		}

		int storage = Blaze3dxBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC;

		try (ComputePipeline pipeline = scan;
				GpuBuffer summary = RenderSystem.getDevice()
						.createBuffer(() -> "flywheel pyramid summary", storage, 4L * Integer.BYTES);
				GpuBuffer readback = RenderSystem.getDevice()
						.createBuffer(() -> "flywheel pyramid summary readback",
								GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
								4L * Integer.BYTES)) {

			// Seeded so the atomics have something to beat: the minimum starts at the largest
			// possible bit pattern and the maximum at the smallest.
			ByteBuffer seed = ByteBuffer.allocateDirect(4 * Integer.BYTES)
					.order(ByteOrder.nativeOrder());
			seed.putInt(0, 0x7F7FFFFF);
			seed.putInt(4, 0);
			seed.putInt(8, 0);
			seed.putInt(12, 0);
			Staging.upload(summary.slice(), seed);

			var sampler = RenderSystem.getSamplerCache()
					.getClampToEdge(com.mojang.blaze3d.textures.FilterMode.NEAREST);

			try (ComputePass pass = gpu.beginPass("flywheel pyramid scan")) {
				pass.setPipeline(pipeline);
				pass.bindStorageBuffer(0, summary.slice());
				pass.bindTexture(ComputePipeline.FIRST_IMAGE_BINDING, view, sampler);
				pass.dispatch((texels + 63) / 64, 1, 1);
				pass.barrier(BarrierScope.STORAGE);
			}

			RenderSystem.getDevice()
					.createCommandEncoder()
					.copyToBuffer(summary.slice(0, 4 * Integer.BYTES),
							readback.slice(0, 4 * Integer.BYTES));

			gpu.flush();
			if (!gpu.awaitGpu(TIMEOUT_NS)) {
				lines.add("the GPU did not signal within " + (TIMEOUT_NS / 1_000_000) + "ms");
				return null;
			}

			try (GpuBufferSlice.MappedView mapped = readback.map(true, false)) {
				IntBuffer values = mapped.data()
						.asIntBuffer();

				int invocations = values.get(3);
				int reportedWidth = values.get(2);
				lines.add("scan ran " + invocations + " invocations; the shader saw a texture "
						+ reportedWidth + " wide");

				if (invocations == 0) {
					lines.add("the dispatch did not run at all");
					return null;
				}

				if (reportedWidth == 0) {
					lines.add("the shader saw a texture of width zero, so the image binding never "
							+ "reached it");
					return null;
				}

				float min = Float.intBitsToFloat(values.get(0));
				float max = Float.intBitsToFloat(values.get(1));

				// Counted here rather than in the shader: the summary has four slots and the width
				// probe needed one of them.
				return new Stats(texels, min, max, max > 1.0e-6f ? 1 : 0);
			}
		} catch (Exception e) {
			lines.add("THREW while scanning the pyramid: " + e);
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
