package dev.engine_room.flywheel.backend.engine.blaze;

import java.util.Optional;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.platform.CompareOp;

import dev.engine_room.flywheel.api.material.DepthTest;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import net.minecraft.resources.Identifier;

/**
 * A material's fixed-function state, as a Blaze3D pipeline describes it.
 *
 * <p>The OpenGL backend sets this per draw through {@code GlStateManager}: enable this, set that
 * blend function, flip that write mask. 26.2 has no such thing. Every piece of it is baked into an
 * immutable {@code RenderPipeline} at build time, so what was a sequence of calls before a draw
 * becomes part of the identity of the pipeline the draw is made with -- and draws that differ in
 * any of it cannot share one.
 *
 * <p>Hence {@link Key}: the part of a material that changes the pipeline, pulled out so pipelines
 * can be cached by it. Two materials that differ only in their texture share a pipeline; two that
 * differ in blending do not.
 */
public final class BlazeMaterials {
	private BlazeMaterials() {
	}

	/**
	 * Everything about a material that the pipeline has to be built around.
	 *
	 * <p>Deliberately not the whole material. The texture does not appear, because it is a binding
	 * rather than pipeline state and folding it in would build a separate pipeline per texture, and
	 * there are hundreds.
	 *
	 * <p>The fog and cutout shaders do appear, although they are source rather than state, because
	 * on 26.2 a pipeline carries its compiled shaders: two materials that fog differently cannot
	 * share one however identical their blending is.
	 */
	public record Key(Transparency transparency, DepthTest depthTest, WriteMask writeMask,
			boolean backfaceCulling, boolean polygonOffset, Identifier fog, Identifier cutout) {

		public static Key of(Material material) {
			return new Key(material.transparency(), material.depthTest(), material.writeMask(),
					material.backfaceCulling(), material.polygonOffset(), material.fog()
							.source(),
					material.cutout()
							.source());
		}

		/** A short, stable name, so a generated shader can be keyed by it without collisions. */
		public String describe() {
			return transparency.name()
					.toLowerCase() + "_" + depthTest.name()
							.toLowerCase()
					+ "_" + writeMask.name()
							.toLowerCase()
					+ (backfaceCulling ? "_cull" : "")
					+ (polygonOffset ? "_offset" : "");
		}
	}

	public static ColorTargetState colorTarget(Key key) {
		Optional<BlendFunction> blend = blendFunction(key.transparency());

		int mask = key.writeMask()
				.color() ? ColorTargetState.WRITE_ALL : ColorTargetState.WRITE_NONE;

		return new ColorTargetState(blend, com.mojang.blaze3d.GpuFormat.RGBA8_UNORM, mask);
	}

	public static DepthStencilState depthStencil(Key key) {
		// Polygon offset pushes decals off the surface they sit on. The sign follows the depth
		// direction, and 26.2's is reversed, so what was a negative bias under OpenGL is positive
		// here -- getting it backwards buries the decal instead of lifting it.
		float bias = key.polygonOffset() ? 1.0f : 0.0f;

		return new DepthStencilState(compareOp(key.depthTest()), key.writeMask()
				.depth(), bias, bias);
	}

	/**
	 * The comparison, mirrored.
	 *
	 * <p>26.2 draws the level into a reversed depth buffer: the projection has its near and far
	 * swapped under zero-to-one clip control, so near writes 1, far writes 0, and the buffer clears
	 * to 0. {@link DepthTest} names what the caller <em>means</em> -- {@code LESS} is "nearer than"
	 * -- so every ordered test is issued as its mirror image. The unordered ones read the same
	 * either way.
	 *
	 * <p>This is the same swap vanilla made when its own pipelines moved to
	 * {@code GREATER_THAN_OR_EQUAL}, and the same one {@code MaterialRenderState} makes for OpenGL.
	 * Reaching for the comparison the material names, because the material names it, is how a
	 * ported render type ends up drawing through walls.
	 */
	private static CompareOp compareOp(DepthTest depthTest) {
		return switch (depthTest) {
			// No depth state at all would mean no depth attachment, so "off" is a test that always
			// passes rather than an absent one.
			case OFF, ALWAYS -> CompareOp.ALWAYS_PASS;
			case NEVER -> CompareOp.NEVER_PASS;
			case LESS -> CompareOp.GREATER_THAN;
			case LEQUAL -> CompareOp.GREATER_THAN_OR_EQUAL;
			case GREATER -> CompareOp.LESS_THAN;
			case GEQUAL -> CompareOp.LESS_THAN_OR_EQUAL;
			case EQUAL -> CompareOp.EQUAL;
			case NOTEQUAL -> CompareOp.NOT_EQUAL;
		};
	}

	private static Optional<BlendFunction> blendFunction(Transparency transparency) {
		return switch (transparency) {
			case OPAQUE -> Optional.empty();
			case ADDITIVE -> Optional.of(BlendFunction.ADDITIVE);
			case LIGHTNING -> Optional.of(BlendFunction.LIGHTNING);
			case GLINT -> Optional.of(BlendFunction.GLINT);
			// Crumbling is vanilla's block-breaking overlay, which multiplies the surface it sits
			// on rather than covering it.
			case CRUMBLING -> Optional.of(BlendFunction.TRANSLUCENT);
			case TRANSLUCENT -> Optional.of(BlendFunction.TRANSLUCENT);
			// Until there is an order-independent path, the honest approximation is ordinary
			// blending: wrong where two of them overlap, right everywhere else, and visible rather
			// than absent.
			case ORDER_INDEPENDENT -> Optional.of(BlendFunction.TRANSLUCENT);
		};
	}

	/** Whether this material's draws must come after the opaque ones. */
	public static boolean isTranslucent(Key key) {
		return key.transparency() != Transparency.OPAQUE;
	}
}
