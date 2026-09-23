package dev.engine_room.flywheel.backend.engine.blaze;

import java.util.Optional;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.backend.compute.Blaze3dx;

import net.minecraft.resources.Identifier;

/**
 * A mip chain of the frame's depth, holding the range of depths beneath each texel.
 *
 * <p>What occlusion culling reads. A cull pass wanting to know whether an instance is hidden
 * projects its bounding sphere to a rectangle on screen and asks this one question: is everything
 * already drawn in that rectangle nearer than the sphere's nearest point? A pyramid answers it with
 * a couple of texel reads at whichever level is coarse enough that the rectangle spans about a
 * texel, instead of reading every depth under it.
 *
 * <h2>Why fragment passes</h2>
 *
 * <p>Each texel holds two numbers: the nearest depth under it and the farthest. See below for why
 * both, rather than the one occlusion culling actually consumes.
 *
 * <p>The usual way to build one is a compute shader writing to successive mips as storage images.
 * Blaze3D 26.2 has no storage images and no compute-writable textures at all, so each level is a
 * full-screen draw instead: three vertices, a fragment shader that reads four texels of the level
 * above and carries both ends of their range upward. Slower than a compute reduction and entirely
 * adequate -- the whole chain is a few thousand pixels after the first level.
 *
 * <h2>Which end is near</h2>
 *
 * <p>Depth here is reversed, the same as everywhere else in this backend: 1 at the near plane
 * falling toward 0 in the distance, which is why {@code DepthStencilState.DEFAULT} is
 * {@code GREATER_THAN_OR_EQUAL} and {@code BlazeMaterials} mirrors every comparison. So the farthest
 * depth in a region is its <em>minimum</em>, carried in {@code .r}.
 *
 * <p>The magnitudes argue otherwise and they are wrong. With the near plane a twentieth of a block
 * out, depth works out as roughly {@code near / distance}, so an ordinary scene lives between about
 * 0.002 and 0.03 -- small numbers throughout, which reads as "near is zero" and cost this file a
 * revision saying exactly that. The tell is the sky: it samples as precisely 0.0, and the sky is as
 * far away as anything gets.
 *
 * <p>Which is why each texel carries both ends of its range rather than one. Occlusion culling needs
 * only the far end, but a pyramid holding a single number cannot be checked against the convention
 * it was built under: reduce the wrong way and every texel still looks like a plausible depth, and
 * the only symptom is machinery vanishing where it should be visible. Carrying both means
 * {@code DepthPyramidSelfTest} can read the convention off the pyramid and say so out loud.
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

	private static final String FRAGMENT = """
			#version 460 core

			in vec2 v_uv;

			uniform sampler2D Source;

			out vec2 fragColor;

			void main() {
				// The four texels of the level above that this one covers. textureGather would do it
				// in one fetch; four explicit offsets are used instead because the source of the
				// first level is the depth buffer rather than this texture, and the two do not
				// necessarily agree about what a gather returns.
				ivec2 base = ivec2(gl_FragCoord.xy) * 2;
				ivec2 limit = textureSize(Source, 0) - 1;

				vec4 a = texelFetch(Source, min(base, limit), 0);
				vec4 b = texelFetch(Source, min(base + ivec2(1, 0), limit), 0);
				vec4 c = texelFetch(Source, min(base + ivec2(0, 1), limit), 0);
				vec4 d = texelFetch(Source, min(base + ivec2(1, 1), limit), 0);

			#ifdef FLW_PYRAMID_FROM_DEPTH
				// The raw depth buffer has one channel, so both ends of the range start out equal.
				vec2 pa = a.rr, pb = b.rr, pc = c.rr, pd = d.rr;
			#else
				vec2 pa = a.rg, pb = b.rg, pc = c.rg, pd = d.rg;
			#endif

				// Both ends carried up the chain: .r the smallest depth under this texel and .g the
				// largest. On this reversed buffer .r is the farthest surface, which is the one
				// occlusion culling reads -- but carrying both means that can be read off the
				// pyramid rather than assumed, which is the one mistake here that would hide the
				// world instead of showing it.
				fragColor = vec2(min(min(pa.r, pb.r), min(pc.r, pd.r)),
						max(max(pa.g, pb.g), max(pc.g, pd.g)));
			}
			""";

	private static final BindGroupLayout LAYOUT = BindGroupLayout.builder()
			.withSampler("Source")
			.build();

	private @Nullable GpuTexture texture;
	private final GpuTextureView[] levelViews;

	private @Nullable GpuTextureView fullView;

	private int width;
	private int height;
	private int levels;

	private @Nullable RenderPipeline pipeline;

	/** The variant that reads the one-channel depth buffer, used for the first level. */
	private @Nullable RenderPipeline depthPipeline;

	/** Whether the reduction pipeline compiled, for anything asking why the chain is empty. */
	private boolean valid;

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

	/**
	 * A view of the whole chain, for a shader that picks its own level.
	 *
	 * <p>The per-level views cannot serve: each covers one mip, so {@code texelFetch} with a level
	 * argument has nothing to fetch from. Occlusion culling chooses a level from how big the thing
	 * it is testing looks on screen, so it needs all of them.
	 */
	public @Nullable GpuTextureView fullView() {
		return fullView;
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

		CommandEncoder encoder = RenderSystem.getDevice()
				.createCommandEncoder();

		var samplers = RenderSystem.getSamplerCache();
		var nearest = samplers.getClampToEdge(FilterMode.NEAREST);

		// The first level reads the real depth buffer; every later one reads the level above. The
		// source is always a different texture or a different mip than the target, which is what
		// keeps this legal -- a pass may not sample the mip it is drawing into.
		for (int level = 0; level < levels; level++) {
			GpuTextureView source = level == 0 ? depthView : levelViews[level - 1];

			// OpenGL samples the whole texture object, so without this the level being drawn into
			// is also readable and the read is a feedback loop -- which yields zeroes, silently,
			// for every level after the first. A no-op on Vulkan, where the views keep them apart.
			if (level > 0) {
				Blaze3dx.restrictMipRange(texture, level - 1, level - 1);
			}

			try (RenderPass pass = encoder.createRenderPass(() -> "flywheel depth pyramid",
					levelViews[level], Optional.empty(), null, OptionalDouble.empty())) {

				pass.setPipeline(pipeline(level == 0));
				pass.bindTexture("Source", source, nearest);
				// vertexCount, instanceCount, firstVertex, firstInstance -- the order vkCmdDraw
				// takes them in, which RenderPass.draw passes straight through. Written as
				// (0, 3, 0, 1) at first, on the assumption that it began with a first index: that
				// asks for zero vertices and three instances starting at instance one. It draws
				// nothing, reports nothing wrong, and leaves a pyramid of zeroes behind.
				pass.draw(3, 1, 0, 0);

			}
		}

		// Opened back up, or the next thing to sample the chain -- the cull pass, which picks its
		// own level -- sees only the sliver the last reduction left behind.
		Blaze3dx.releaseMipRange(texture, levels);
	}


	@Override
	public void close() {
		for (int i = 0; i < levels; i++) {
			if (levelViews[i] != null) {
				levelViews[i].close();
				levelViews[i] = null;
			}
		}

		if (fullView != null) {
			fullView.close();
			fullView = null;
		}

		if (texture != null) {
			texture.close();
			texture = null;
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
				.createTexture(() -> "flywheel depth pyramid", usage, GpuFormat.RG32_FLOAT, w, h, 1,
						levels);

		fullView = RenderSystem.getDevice()
				.createTextureView(texture);

		// One view per level, because a pass draws into exactly one mip and samples exactly one
		// other. A view of the whole chain cannot say which.
		for (int level = 0; level < levels; level++) {
			levelViews[level] = RenderSystem.getDevice()
					.createTextureView(texture, level, 1);
		}
	}

	/**
	 * The reduction pipeline, in two variants.
	 *
	 * <p>The first level reads the raw depth buffer, which has one channel; every level after it
	 * reads this texture, which has two. The same shader either way, one define apart.
	 */
	private RenderPipeline pipeline(boolean fromDepth) {
		if (fromDepth) {
			if (depthPipeline == null) {
				depthPipeline = build("depth_pyramid_first", true);
			}
			return depthPipeline;
		}

		if (pipeline == null) {
			pipeline = build("depth_pyramid", false);
		}
		return pipeline;
	}

	private RenderPipeline build(String name, boolean fromDepth) {
		String fragment = fromDepth
				? FRAGMENT.replace("#version 460 core", "#version 460 core\n#define FLW_PYRAMID_FROM_DEPTH")
				: FRAGMENT;

		Identifier shaders = GeneratedShaders.pipeline("flywheel_" + name, VERTEX, fragment);

		RenderPipeline built = RenderPipeline.builder()
				.withLocation(Identifier.fromNamespaceAndPath("flywheel", "pipeline/" + name))
				.withVertexShader(shaders)
				.withFragmentShader(shaders)
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.withBindGroupLayout(LAYOUT)
				// No depth state at all, which on 26.2 also means no depth attachment -- this draws
				// a single triangle over a target it owns and has nothing to test against.
				.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RG32_FLOAT,
						ColorTargetState.WRITE_ALL))
				.withCull(false)
				.build();

		// Checked, because Blaze3D compiles lazily and an invalid pipeline is not discovered until a
		// draw uses it -- and a draw that never happens writes nothing and says nothing.
		valid = RenderSystem.getDevice()
				.precompilePipeline(built)
				.isValid();

		return built;
	}

	/**
	 * How many levels the chain can have before a dimension runs out.
	 *
	 * <p>Stops when the <em>smaller</em> side reaches one, rather than carrying on to a 1x1 top the
	 * way a full mip chain does. The difference is one level on a 16:9 screen and it is not
	 * cosmetic: Blaze3D sizes a level by shifting, without clamping the result to one, so a 640x360
	 * pyramid asked for ten levels reports its last as 640x360 shifted nine places -- which is 1x0.
	 *
	 * <p>Vulkan tolerates that. OpenGL never allocates the level, so attaching it gives
	 * {@code GL_FRAMEBUFFER_INCOMPLETE_ATTACHMENT}, and from there the symptom is not an exception
	 * but a quiet one: the reduction draws nothing anywhere, every texel of the chain stays zero,
	 * the cull pass finds nothing farther than a sphere can be, and occlusion culling reports
	 * exactly zero hidden instances while looking entirely healthy.
	 *
	 * <p>The lost level costs nothing. A pyramid top of 2x1 is already far coarser than any
	 * bounding sphere's rectangle asks for.
	 */
	private static int mipLevelsFor(int w, int h) {
		int levels = 1;
		while (w / 2 >= 1 && h / 2 >= 1) {
			w /= 2;
			h /= 2;
			levels++;
		}
		return levels;
	}
}
