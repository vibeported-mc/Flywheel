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

import dev.engine_room.flywheel.backend.compute.Compute;
import dev.engine_room.flywheel.backend.compute.BarrierScope;
import dev.engine_room.flywheel.backend.compute.ComputeBackend;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;
import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
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
 * <h2>This currently fails, and the reason is structural</h2>
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

			layout(std430, FLW_SET(0) binding = 0) readonly buffer Pyramid {
				float _flw_depth[];
			};

			layout(std430, FLW_SET(0) binding = 1) buffer Summary {
				uint _flw_min;
				uint _flw_max;
				uint _flw_nearer;
				uint _flw_count;
			};

			layout(std430, FLW_SET(0) binding = 2) readonly buffer Params {
				uint _flw_texels;
			};

			void main() {
				uint i = gl_GlobalInvocationID.x;
				if (i >= _flw_texels) {
					return;
				}

				float d = _flw_depth[i];

				// Compared as bit patterns, which is exact for non-negative floats: IEEE 754 orders
				// them the same way the integers order. A depth is never negative, so this holds.
				uint bits = floatBitsToUint(max(d, 0.0));

				atomicMin(_flw_min, bits);
				atomicMax(_flw_max, bits);
				atomicAdd(_flw_count, 1u);

				// Reversed depth: the far plane is zero, so anything above it is real geometry.
				if (d > 1.0e-6) {
					atomicAdd(_flw_nearer, 1u);
				}
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
		int level = pyramid.sampledLevel();
		GpuBuffer pyramidBuffer = pyramid.storageOf(level);
		int texels = pyramid.storageTexels();

		if (pyramidBuffer == null || texels == 0) {
			lines.add("the pyramid has no level copied into a storage buffer");
			return null;
		}

		ComputeBackend gpu = Compute.backend();

		ComputePipeline scan = gpu.createPipeline(
				ComputePipeline.Description.of("flywheel pyramid scan", SCAN));

		if (scan == null) {
			lines.add("the scan shader would not build");
			return null;
		}

		int storage = FlwBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST | GpuBuffer.USAGE_COPY_SRC;

		try (ComputePipeline pipeline = scan;
				GpuBuffer summary = RenderSystem.getDevice()
						.createBuffer(() -> "flywheel pyramid summary", storage, 4L * Integer.BYTES);
				GpuBuffer params = RenderSystem.getDevice()
						.createBuffer(() -> "flywheel pyramid scan params", storage, Integer.BYTES);
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

			ByteBuffer count = ByteBuffer.allocateDirect(Integer.BYTES)
					.order(ByteOrder.nativeOrder());
			count.putInt(0, texels);
			Staging.upload(params.slice(), count);

			try (ComputePass pass = gpu.beginPass("flywheel pyramid scan")) {
				pass.setPipeline(pipeline);
				pass.bindStorageBuffer(0, pyramidBuffer.slice());
				pass.bindStorageBuffer(1, summary.slice());
				pass.bindStorageBuffer(2, params.slice());
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

			try (GpuBufferSlice.MappedView view = readback.map(true, false)) {
				IntBuffer values = view.data()
						.asIntBuffer();

				int scanned = values.get(3);
				if (scanned == 0) {
					lines.add("the scan saw no texels at all, so the dispatch did not run");
					return null;
				}

				return new Stats(scanned, Float.intBitsToFloat(values.get(0)),
						Float.intBitsToFloat(values.get(1)), values.get(2));
			}
		} catch (Exception e) {
			lines.add("THREW while scanning the level on the GPU: " + e);
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
