package dev.engine_room.flywheel.backend.engine.blaze;

import java.util.Optional;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.resources.Identifier;

/**
 * A mip chain of the frame's depth, each texel holding the farthest depth beneath it.
 *
 * <p>What occlusion culling reads. A cull pass wanting to know whether an instance is hidden
 * projects its bounding sphere to a rectangle on screen and asks this one question: is everything
 * already drawn in that rectangle nearer than the sphere's nearest point? A pyramid answers it with
 * a couple of texel reads at whichever level is coarse enough that the rectangle spans about a
 * texel, instead of reading every depth under it.
 *
 * <h2>Why fragment passes</h2>
 *
 * <p>The usual way to build one is a compute shader writing to successive mips as storage images.
 * Blaze3D 26.2 has no storage images and no compute-writable textures at all, so each level is a
 * full-screen draw instead: three vertices, a fragment shader that reads four texels of the level
 * above and keeps the farthest. Slower than a compute reduction and entirely adequate -- the whole
 * chain is a few thousand pixels after the first level.
 *
 * <h2>Reversed depth</h2>
 *
 * <p>26.2 draws into a reversed depth buffer: near is 1, far is 0. So the farthest depth in a region
 * is its <em>minimum</em>, and the reduction takes {@code min}. Taking {@code max} builds a pyramid
 * of near depths instead, which does not fail or look wrong in isolation -- it makes occlusion
 * culling hide the things the player can see.
 */
public class DepthPyramid implements AutoCloseable {
	/**
	 * The chain starts at half the screen, which is the usual compromise.
	 *
	 * <p>Full resolution costs four times as much to build and buys precision that occlusion culling
	 * throws away anyway: the test is deliberately conservative, and a false "visible" only costs the
	 * work of drawing something hidden.
	 */
	private static final int DOWNSCALE = 2;

	private static final String VERTEX = """
			#version 460 core

			out vec2 v_uv;

			void main() {
				// One triangle covering the screen, from the vertex index alone -- no vertex buffer,
				// no index buffer, nothing to bind. Two of its corners are off screen, which is the
				// point: a single triangle has no seam down the middle the way two do.
				vec2 corner = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
				v_uv = corner;
				gl_Position = vec4(corner * 2.0 - 1.0, 0.0, 1.0);
			}
			""";

	/**
	 * Makes the reduction write a constant instead of a depth, for telling two failures apart.
	 *
	 * <p>If a readback shows the constant, the draw and the target are fine and the depth fetch is
	 * not. If it shows the same thing either way -- which is what happened -- then nothing is reading
	 * the pyramid at all and the fault is downstream of it.
	 */
	private static final boolean PROBE = Boolean.getBoolean("flywheel.pyramidProbe");

	private static final String FRAGMENT = """
			#version 460 core

			in vec2 v_uv;

			uniform sampler2D Source;

			out float fragColor;

			void main() {
				// The four texels of the level above that this one covers. textureGather would do it
				// in one fetch; four explicit offsets are used instead because the source of the
				// first level is the depth buffer rather than this texture, and the two do not
				// necessarily agree about what a gather returns.
				ivec2 base = ivec2(gl_FragCoord.xy) * 2;
				ivec2 limit = textureSize(Source, 0) - 1;

				float a = texelFetch(Source, min(base, limit), 0).r;
				float b = texelFetch(Source, min(base + ivec2(1, 0), limit), 0).r;
				float c = texelFetch(Source, min(base + ivec2(0, 1), limit), 0).r;
				float d = texelFetch(Source, min(base + ivec2(1, 1), limit), 0).r;

				// Farthest, and on a reversed depth buffer that is the smallest.
				fragColor = min(min(a, b), min(c, d));
			#ifdef FLW_PYRAMID_PROBE
				// A constant, to tell "the pass never wrote" from "the sample returned zero". If the
				// readback shows this, the draw and the target are fine and the depth fetch is not.
				fragColor = 0.5;
			#endif
			}
			""";

	private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
			.withSampler("Source")
			.build();

	private @Nullable GpuTexture texture;
	private final GpuTextureView[] levelViews;

	private int width;
	private int height;
	private int levels;

	private @Nullable RenderPipeline pipeline;

	/** Whether the reduction pipeline compiled, for anything asking why the chain is empty. */
	private boolean valid;

	/**
	 * One level, copied out every frame so anything outside the frame can look at it.
	 *
	 * <p>Copied rather than read on demand because {@code copyTextureToBuffer} is asynchronous and
	 * says so only through a callback: a copy started and read in the same breath returns whatever
	 * was in that memory, which came back as NaN. Started inside the frame and read later, the copy
	 * has long since landed.
	 */
	private @Nullable GpuBuffer sample;

	private int sampleLevel;
	private int sampleTexels;

	private @Nullable GpuBuffer storage;
	private int storageLevel;
	private int storageTexels;

	public DepthPyramid() {
		levelViews = new GpuTextureView[32];
	}

	public GpuTexture texture() {
		if (texture == null) {
			throw new IllegalStateException("the pyramid has not been built yet");
		}
		return texture;
	}

	public int width() {
		return width;
	}

	public int height() {
		return height;
	}

	/** The view of one level, for a shader that wants to sample it. */
	public @Nullable GpuTextureView levelView(int level) {
		return level >= 0 && level < levels ? levelViews[level] : null;
	}

	public boolean pipelineValid() {
		return valid;
	}

	public int levels() {
		return levels;
	}

	/**
	 * Reduces the level's depth into the chain, one full-screen draw per level.
	 *
	 * @param depth the texture, for its size
	 * @param depthView the view to sample it through -- the render target's own, not one made here.
	 *            A depth texture can carry a stencil aspect as well, and a view that picks the wrong
	 *            one reads as zero everywhere rather than failing, which is indistinguishable from a
	 *            frame of empty sky. Minecraft already keeps a view it samples this texture through;
	 *            using that one avoids having to guess.
	 */
	public void build(GpuTexture depth, GpuTextureView depthView) {
		ensure(Math.max(1, depth.getWidth(0) / DOWNSCALE), Math.max(1, depth.getHeight(0) / DOWNSCALE));

		RenderPipeline reduce = pipeline();
		CommandEncoder encoder = RenderSystem.getDevice()
				.createCommandEncoder();

		var samplers = RenderSystem.getSamplerCache();
		var nearest = samplers.getClampToEdge(FilterMode.NEAREST);

		// The first level reads the real depth buffer; every later one reads the level above. The
		// source is always a different texture or a different mip than the target, which is what
		// keeps this legal -- a pass may not sample the mip it is drawing into.
		for (int level = 0; level < levels; level++) {
			GpuTextureView source = level == 0 ? depthView : levelViews[level - 1];

			try (RenderPass pass = encoder.createRenderPass(() -> "flywheel depth pyramid",
					levelViews[level], Optional.empty(), null, OptionalDouble.empty())) {

				pass.setPipeline(reduce);
				pass.bindTexture("Source", source, nearest);
				// vertexCount, instanceCount, firstVertex, firstInstance -- the order vkCmdDraw
				// takes them in, which RenderPass.draw passes straight through. Written as
				// (0, 3, 0, 1) at first, on the assumption that it began with a first index: that
				// asks for zero vertices and three instances starting at instance one. It draws
				// nothing, reports nothing wrong, and leaves a pyramid of zeroes behind.
				pass.draw(3, 1, 0, 0);
			}
		}
	}

	/**
	 * Starts the copy of one level into the buffer {@link #sample()} returns.
	 *
	 * <p>Call from inside the frame, right after building. Nothing waits on it.
	 */
	public void sampleLevel(int level) {
		if (texture == null || level >= levels) {
			return;
		}

		int texels = texture.getWidth(level) * texture.getHeight(level);

		if (sample == null || sampleLevel != level || sampleTexels != texels) {
			if (sample != null) {
				sample.close();
			}
			sample = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel depth pyramid sample",
							GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST,
							(long) texels * Float.BYTES);
			sampleLevel = level;
			sampleTexels = texels;
		}

		RenderSystem.getDevice()
				.createCommandEncoder()
				.copyTextureToBuffer(texture, sample, 0, () -> {
				}, level);
	}

	/**
	 * Copies one level into a buffer a compute shader can read.
	 *
	 * <p>Separate from {@link #sampleLevel} because the destination is a storage buffer rather than a
	 * mappable one: this is the copy the cull pass needs, not the one a test reads. Both go through
	 * {@code copyTextureToBuffer}, which is the call whose completion callback never fires here --
	 * so whether this arrives at all is the question the self-test answers by scanning the result on
	 * the GPU rather than on the CPU.
	 */
	public @Nullable GpuBuffer storageOf(int level) {
		if (texture == null || level >= levels) {
			return null;
		}

		int texels = texture.getWidth(level) * texture.getHeight(level);

		if (storage == null || storageLevel != level || storageTexels != texels) {
			if (storage != null) {
				storage.close();
			}
			storage = RenderSystem.getDevice()
					.createBuffer(() -> "flywheel depth pyramid storage",
							dev.engine_room.flywheel.backend.compute.FlwBufferUsage.STORAGE
									| GpuBuffer.USAGE_COPY_DST,
							(long) texels * Float.BYTES);
			storageLevel = level;
			storageTexels = texels;
		}

		RenderSystem.getDevice()
				.createCommandEncoder()
				.copyTextureToBuffer(texture, storage, 0, () -> {
				}, level);

		return storage;
	}

	public int storageTexels() {
		return storageTexels;
	}

	public @Nullable GpuBuffer sample() {
		return sample;
	}

	public int sampleTexels() {
		return sampleTexels;
	}

	public int sampledLevel() {
		return sampleLevel;
	}

	@Override
	public void close() {
		for (int i = 0; i < levels; i++) {
			if (levelViews[i] != null) {
				levelViews[i].close();
				levelViews[i] = null;
			}
		}

		if (texture != null) {
			texture.close();
			texture = null;
		}

		if (sample != null) {
			sample.close();
			sample = null;
			sampleTexels = 0;
		}

		if (storage != null) {
			storage.close();
			storage = null;
			storageTexels = 0;
		}

		levels = 0;
		width = 0;
		height = 0;
	}

	private void ensure(int w, int h) {
		if (texture != null && w == width && h == height) {
			return;
		}

		close();

		width = w;
		height = h;
		levels = mipLevelsFor(w, h);

		int usage = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
				| GpuTexture.USAGE_COPY_SRC;

		texture = RenderSystem.getDevice()
				.createTexture(() -> "flywheel depth pyramid", usage, GpuFormat.R32_FLOAT, w, h, 1,
						levels);

		// One view per level, because a pass draws into exactly one mip and samples exactly one
		// other. A view of the whole chain cannot say which.
		for (int level = 0; level < levels; level++) {
			levelViews[level] = RenderSystem.getDevice()
					.createTextureView(texture, level, 1);
		}
	}

	private RenderPipeline pipeline() {
		if (pipeline == null) {
			String fragment = PROBE
					? FRAGMENT.replace("#version 460 core", "#version 460 core\n#define FLW_PYRAMID_PROBE")
					: FRAGMENT;

			Identifier shaders = GeneratedShaders.pipeline("flywheel_depth_pyramid", VERTEX, fragment);

			pipeline = RenderPipeline.builder()
					.withLocation(Identifier.fromNamespaceAndPath("flywheel", "pipeline/depth_pyramid"))
					.withVertexShader(shaders)
					.withFragmentShader(shaders)
					.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
					.withBindGroupLayout(LAYOUT)
					// No depth state at all, which on 26.2 also means no depth attachment -- this
					// draws a single triangle over a target it owns and has nothing to test against.
					.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.R32_FLOAT,
							ColorTargetState.WRITE_ALL))
					.withCull(false)
					.build();

			// Checked, because Blaze3D compiles lazily and an invalid pipeline is not discovered
			// until a draw uses it -- and a draw that never happens writes nothing and says nothing.
			valid = RenderSystem.getDevice()
					.precompilePipeline(pipeline)
					.isValid();
		}

		return pipeline;
	}

	private static int mipLevelsFor(int w, int h) {
		int levels = 1;
		while (w > 1 || h > 1) {
			w = Math.max(1, w / 2);
			h = Math.max(1, h / 2);
			levels++;
		}
		return levels;
	}
}
