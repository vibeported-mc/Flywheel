package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.material.Transparency;
import dev.engine_room.flywheel.api.material.WriteMask;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.material.CutoutShaders;
import dev.engine_room.flywheel.lib.material.FogShaders;
import dev.engine_room.flywheel.lib.material.LightShaders;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.DrawManager;
import dev.engine_room.flywheel.backend.engine.InstanceHandleImpl;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.Vec3i;

/**
 * Turns a frame's worth of instancers into draws, through Blaze3D.
 *
 * <p>The Blaze3D counterpart of {@code IndirectDrawManager}, and the place all the pieces meet: the
 * mesh pool holds the geometry, each instancer holds its instances, and once a frame this writes
 * what the GPU needs to draw them and opens one render pass to do it.
 *
 * <p>The order within a frame matters and is not arbitrary: every upload, then the compute passes
 * that decide what to draw, then one render pass. Compute cannot be dispatched inside a render pass
 * on Vulkan, and a staged upload cannot be recorded inside either.
 *
 * <h2>What this does not do yet</h2>
 *
 * <p>The overlay texture, which is the red flash a damaged block entity gets -- no material in
 * Flywheel's own library turns it on, so nothing in Create asks for it. Order-independent
 * transparency falls back to ordinary blending, which was measured against the real thing on the one
 * scene in Create that can tell them apart and found to be indistinguishable.
 */
public class BlazeDrawManager extends DrawManager<BlazeInstancer<?>> {
	private final BlazeMeshPool meshPool = new BlazeMeshPool();
	private final BlazeUniforms uniforms = new BlazeUniforms();
	private final BlazeLight light = new BlazeLight();
	private final BlazeEnvironments environments = new BlazeEnvironments();
	private final BlazeCull cull = new BlazeCull();
	private final BlazeIdentity identity = new BlazeIdentity();
	private final BlazeCrumbling crumbling = new BlazeCrumbling();
	private final DepthPyramid depthPyramid = new DepthPyramid();

	/** One pipeline per instance type, since the shader is generated from its layout. */
	private final Map<PipelineKey, @Nullable GeneratedPipeline> pipelines = new HashMap<>();

	/** The same, for the block-breaking variant, which is a different shader and layout. */
	private final Map<PipelineKey, @Nullable GeneratedPipeline> crumblingPipelines = new HashMap<>();

	private @Nullable RenderContext context;
	private Vec3i renderOrigin = Vec3i.ZERO;
	private @Nullable EnvironmentStorage environmentStorage;
	private boolean fallback;

	/** Set by the engine before {@link #render}, since a draw manager is not given the context. */
	public void prepare(RenderContext context, Vec3i renderOrigin) {
		this.context = context;
		this.renderOrigin = renderOrigin;
	}

	@Override
	protected <I extends Instance> BlazeInstancer<?> create(InstancerKey<I> key) {
		// Off the render thread. CPU state only: no buffers, no pipelines.
		return new BlazeInstancer<>(key, new AbstractInstancer.Recreate<>(key, this));
	}

	@Override
	protected <I extends Instance> void initialize(InstancerKey<I> key, BlazeInstancer<?> instancer) {
		// Render thread, and only for instancers that actually got instances.
		Model model = key.model();
		List<Model.ConfiguredMesh> meshes = model.meshes();

		for (int i = 0; i < meshes.size(); i++) {
			Model.ConfiguredMesh configured = meshes.get(i);

			instancer.addDraw(new BlazeDraw(instancer, configured.material(),
					meshPool.alloc(configured.mesh()), key.bias(), i));
		}

		// Sorted once, here, and never again. Two things depend on the order being fixed: draws that
		// share a material end up next to each other, so a run of them is one indirect call, and the
		// apply pass writes one command per draw in this order -- so a later re-sort would point
		// every command at the wrong mesh.
		instancer.draws()
				.sort(ORDER);
	}

	@Override
	public void render(LightStorage lightStorage, EnvironmentStorage environmentStorage) {
		super.render(lightStorage, environmentStorage);

		if (fallback || context == null) {
			return;
		}

		instancers.values()
				.removeIf(instancer -> {
					if (instancer.instanceCount() == 0) {
						instancer.delete();
						return true;
					}
					return false;
				});

		meshPool.flush();
		if (meshPool.isEmpty()) {
			return;
		}

		uniforms.update(context, renderOrigin);
		light.flush(lightStorage);
		environments.flush(environmentStorage);
		this.environmentStorage = environmentStorage;

		List<BlazeInstancer<?>> drawable = new ArrayList<>();
		for (BlazeInstancer<?> instancer : instancers.values()) {
			instancer.upload();

			if (!instancer.draws()
					.isEmpty()) {
				drawable.add(instancer);
			}
		}

		if (drawable.isEmpty()) {
			return;
		}

		// Before the render pass, not inside it. A compute dispatch is illegal inside a render pass on
		// Vulkan, and on OpenGL it would bind a program out from under the draws already recorded.
		submit(drawable, cull.dispatch(drawable, context, renderOrigin));
	}

	/**
	 * Reduces the frame's depth into the pyramid occlusion culling reads.
	 *
	 * <p>Called from {@link BlazeEngine#afterLevelRender} rather than from {@link #render}, because
	 * the depth texture is the attachment of the pass in progress while the level is being drawn and
	 * sampling an attachment reads as zero. The pyramid is therefore always one frame behind, which
	 * is what Hi-Z occlusion culling does by convention anyway.
	 *
	 * <p>Kept alive between frames rather than rebuilt from scratch, so the texture holds the last
	 * frame it was given even when nothing is looking.
	 */
	void buildDepthPyramid() {
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		var depth = target.getDepthTexture();
		var depthView = target.getDepthTextureView();

		if (depth == null || depthView == null) {
			return;
		}

		try {
			depthPyramid.build(depth, depthView);
			BlazeStats.depthPyramidLevels = depthPyramid.levels();

			// Fine enough that solid ground reads as ground rather than as the sky behind it, which
			// is what anything checking the pyramid needs to see.
			depthPyramid.sampleLevel(Math.min(2, depthPyramid.levels() - 1));
		} catch (Exception e) {
			// One bad frame should not take the renderer down with it, and a pyramid is an
			// optimisation: without it the cull pass simply tests fewer things.
			FlwBackend.LOGGER.error("Could not build the depth pyramid; occlusion culling is off", e);
			BlazeStats.depthPyramidLevels = 0;
		}
	}

	/** The pyramid as last built, for anything that wants to read it back. */
	public DepthPyramid depthPyramid() {
		return depthPyramid;
	}

	/**
	 * The block-breaking overlay, drawn over instances a player is mining.
	 *
	 * <p>A separate pass rather than a material swap: every instance being broken is drawn a second
	 * time, through a shader that replaces its colour with the breaking texture and leaves its alpha
	 * alone. One draw per instance per mesh, which sounds extravagant and is not -- the list is the
	 * blocks players currently have a pick in, so it is almost always empty and never long.
	 */
	@Override
	public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
		if (fallback || context == null || crumblingBlocks.isEmpty() || meshPool.isEmpty()) {
			return;
		}

		List<CrumblingDraw> work = collectCrumbling(crumblingBlocks);
		if (work.isEmpty()) {
			return;
		}

		// Every upload first, because a staged one cannot be recorded inside an open render pass.
		int[] indices = new int[work.size()];
		for (int i = 0; i < work.size(); i++) {
			indices[i] = work.get(i)
					.index();
		}
		crumbling.prepare(indices, indices.length);

		BlazeStats.crumblingCalls = 0;
		submitCrumbling(work);
	}

	private List<CrumblingDraw> collectCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
		List<CrumblingDraw> work = new ArrayList<>();

		for (Engine.CrumblingBlock block : crumblingBlocks) {
			int progress = block.progress();

			if (progress < 0 || progress >= ModelBakery.BREAKING_LOCATIONS.size()) {
				continue;
			}

			for (Instance instance : block.instances()) {
				// Checked rather than assumed: the list is every crumbling block in the level, and an
				// instance another engine created would be read as one of ours from the wrong buffer.
				if (!(instance.handle() instanceof InstanceHandleImpl<?> handle)) {
					continue;
				}
				if (!(handle.state instanceof BlazeInstancer<?> instancer)) {
					continue;
				}
				if (instancer.draws()
						.isEmpty()) {
					continue;
				}

				work.add(new CrumblingDraw(instancer, handle.index, progress));
			}
		}

		return work;
	}

	private void submitCrumbling(List<CrumblingDraw> work) {
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView color = target.getColorTextureView();
		GpuTextureView depth = target.getDepthTextureView();

		if (color == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice()
				.createCommandEncoder();

		GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightmap();

		try (RenderPass pass = encoder.createRenderPass(() -> "flywheel crumbling", color,
				Optional.empty(), depth, OptionalDouble.empty())) {

			pass.setVertexBuffer(0, meshPool.vertices()
					.slice());
			pass.setIndexBuffer(meshPool.indices(), IndexType.INT);

			for (int i = 0; i < work.size(); i++) {
				drawCrumbling(pass, work.get(i), crumbling.slice(i), lightmap);
			}
		}
	}

	private void drawCrumbling(RenderPass pass, CrumblingDraw work, GpuBufferSlice selection,
			GpuTextureView lightmap) {
		BlazeInstancer<?> instancer = work.instancer();
		var samplers = RenderSystem.getSamplerCache();

		var instanceSlice = instancer.slice();
		if (instanceSlice == null) {
			return;
		}

		GpuTextureView cracks = textureOf(ModelBakery.BREAKING_LOCATIONS.get(work.progress()));
		if (cracks == null) {
			return;
		}

		var environment = environments.slice(environmentStorage, instancer.environment.matrixIndex());

		for (BlazeDraw draw : instancer.draws()) {
			if (draw.isEmpty()) {
				continue;
			}

			GpuTextureView diffuse = textureOf(draw.material()
					.texture());
			if (diffuse == null) {
				continue;
			}

			// The crumbling material, which is the draw's own with the parts that would make the
			// overlay read as a separate object taken out: no fog, no lighting, colour written but
			// not depth, and a polygon offset so it sits on the surface rather than fighting it.
			var material = crumblingMaterial(draw.material());
			GeneratedPipeline pipeline = crumblingPipelineFor(instancer, material);

			if (pipeline == null) {
				continue;
			}

			pass.setPipeline(pipeline.pipeline());
			pass.setUniform(BlazeUniforms.BLOCK_NAME, uniforms.slice());
			pass.setUniform(BlazeEnvironments.BLOCK_NAME, environment);
			pass.setUniform("_flw_instances", instanceSlice);
			pass.setUniform("_flw_visible", selection);
			pass.setUniform("_flw_lightSections", light.sections());
			pass.setUniform("_flw_lightLut", light.lut());

			pass.bindTexture("Sampler0", diffuse, samplers.getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture("Sampler1", cracks, samplers.getClampToEdge(FilterMode.NEAREST));
			pass.bindTexture("Sampler2", lightmap, samplers.getClampToEdge(FilterMode.LINEAR));

			var mesh = draw.mesh();
			// One instance, and which one is in the selection bound above rather than in
			// firstInstance -- which Vulkan drops in silence when it is not zero.
			pass.drawIndexed(mesh.indexCount(), 1, mesh.firstIndex(), mesh.baseVertex(), 0);
			BlazeStats.crumblingCalls++;
		}
	}

	/**
	 * A material's crumbling twin, matching what the OpenGL backends build in
	 * {@code CommonCrumbling}.
	 *
	 * <p>{@code WriteMask.COLOR} is the part worth naming: the overlay must not write depth, or it
	 * occludes the very surface it is drawn on.
	 */
	private static BlazeMaterials.Key crumblingMaterial(Material base) {
		return new BlazeMaterials.Key(Transparency.CRUMBLING, base.depthTest(), WriteMask.COLOR,
				base.backfaceCulling(), true, FogShaders.NONE.source(),
				CutoutShaders.ONE_TENTH.source(), LightShaders.SMOOTH_WHEN_EMBEDDED.source(), false,
				// No shading and no lightmap: cracks are meant to read as marks on the surface, and
				// a lit, shaded copy of them reads as a second object hovering above it.
				CardinalLightingMode.OFF, false);
	}

	private @Nullable GeneratedPipeline crumblingPipelineFor(BlazeInstancer<?> instancer,
			BlazeMaterials.Key material) {
		var key = new PipelineKey(instancer.type, material,
				instancer.environment.matrixIndex() != 0);

		if (!crumblingPipelines.containsKey(key)) {
			crumblingPipelines.put(key, GeneratedPipeline.of(instancer, material, true));
		}

		return crumblingPipelines.get(key);
	}

	/** One instance of one instancer, drawn again with the breaking texture at one stage. */
	private record CrumblingDraw(BlazeInstancer<?> instancer, int index, int progress) {
	}

	@Override
	public void triggerFallback() {
		fallback = true;
	}

	@Override
	public void delete() {
		super.delete();

		for (BlazeInstancer<?> instancer : instancers.values()) {
			instancer.delete();
		}

		pipelines.clear();
		crumblingPipelines.clear();

		crumbling.close();
		depthPyramid.close();
		identity.close();
		cull.close();
		environments.close();
		light.close();
		meshPool.close();
		uniforms.close();
		GeneratedShaders.clear();
	}

	private void submit(List<BlazeInstancer<?>> drawable, Set<BlazeInstancer<?>> culled) {
		var target = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTextureView color = target.getColorTextureView();
		GpuTextureView depth = target.getDepthTextureView();

		if (color == null) {
			return;
		}

		CommandEncoder encoder = RenderSystem.getDevice()
				.createCommandEncoder();

		GpuBuffer vertices = meshPool.vertices();
		GpuBuffer indices = meshPool.indices();

		BlazeStats.reset();

		// No clear on either attachment: this draws into the level as it stands, after the terrain
		// and before whatever comes next. Clearing would erase the world.
		try (RenderPass pass = encoder.createRenderPass(() -> "flywheel", color, Optional.empty(),
				depth, OptionalDouble.empty())) {

			pass.setVertexBuffer(0, vertices.slice());
			pass.setIndexBuffer(indices, IndexType.INT);

			// Opaque first, then translucent, and the two passes are not interchangeable. A
			// translucent surface blends with whatever is already in the colour buffer, so one drawn
			// before the opaque machine standing behind it blends with the sky instead -- and then
			// the machine, being nearer in nothing but draw order, is rejected by the depth test the
			// translucent surface already wrote. Create's fluids are the ones this shows up on.
			for (BlazeInstancer<?> instancer : drawable) {
				draw(pass, instancer, culled.contains(instancer), false);
			}

			for (BlazeInstancer<?> instancer : drawable) {
				draw(pass, instancer, culled.contains(instancer), true);
			}
		}
	}

	/** @param translucentPass whether this is the second pass, which draws what blends */
	private void draw(RenderPass pass, BlazeInstancer<?> instancer, boolean culled,
			boolean translucentPass) {
		// Every declared binding must be supplied, and these are never absent: an empty volume is a
		// buffer of zeros rather than nothing. Skipping the draw when there was no light yet is what
		// made every machine in the world vanish -- a world with no light-using visual in it still
		// has machines to draw.
		var lightSections = light.sections();
		var lightLut = light.lut();

		var instanceSlice = instancer.slice();
		if (instanceSlice == null) {
			return;
		}

		// The list the vertex shader turns gl_InstanceID into a real instance with. When the cull pass
		// ran it is what the cull pass packed; when it did not, it is 0, 1, 2, ... so the same shader
		// draws everything. The alternative -- two generated shaders, one indexed and one not -- would
		// double the compile cost to save a buffer nobody notices.
		var visible = culled ? instancer.visibleSlice()
				: identity.upTo(instancer.instanceCount());
		if (visible == null) {
			return;
		}

		var samplers = RenderSystem.getSamplerCache();
		GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightmap();
		var environment = environments.slice(environmentStorage, instancer.environment.matrixIndex());

		List<BlazeDraw> draws = instancer.draws();

		if (!translucentPass) {
			if (culled) {
				BlazeStats.indirectInstancers++;
			} else {
				BlazeStats.directInstancers++;
			}
		}

		for (int first = 0; first < draws.size(); ) {
			BlazeDraw draw = draws.get(first);

			if (draw.isEmpty() || belongsToTranslucentPass(draw) != translucentPass) {
				first++;
				continue;
			}

			GpuTextureView diffuse = textureOf(draw.material()
					.texture());
			if (diffuse == null) {
				first++;
				continue;
			}

			// One pipeline per instance type and material state together: the type decides the
			// shader, the material decides the blending, the depth test and the write mask. On 26.2
			// all of that is baked into the pipeline rather than set before the draw, so two draws
			// that differ in any of it cannot share one.
			var material = BlazeMaterials.Key.of(draw.material());
			GeneratedPipeline pipeline = pipelineFor(instancer, material);

			if (pipeline == null) {
				first++;
				continue;
			}

			// How many draws after this one share everything the pipeline and the bindings are keyed
			// by. Their commands sit next to each other in the command buffer -- the apply pass wrote
			// one per draw, in this order -- so a run of them is one indirect call instead of several.
			int last = first + 1;
			while (last < draws.size() && sharesState(draw, draws.get(last))) {
				last++;
			}

			pass.setPipeline(pipeline.pipeline());
			pass.setUniform(BlazeUniforms.BLOCK_NAME, uniforms.slice());
			pass.setUniform(BlazeEnvironments.BLOCK_NAME, environment);
			pass.setUniform("_flw_instances", instanceSlice);
			pass.setUniform("_flw_visible", visible);
			pass.setUniform("_flw_lightSections", lightSections);
			pass.setUniform("_flw_lightLut", lightLut);

			// Nearest for the atlas, because a block texture filtered linearly bleeds between
			// neighbouring sprites; linear for the lightmap, which is a gradient and wants it.
			pass.bindTexture("Sampler0", diffuse, samplers.getClampToEdge(
					draw.material()
							.blur() ? FilterMode.LINEAR : FilterMode.NEAREST));
			pass.bindTexture("Sampler2", lightmap, samplers.getClampToEdge(FilterMode.LINEAR));

			if (culled) {
				// The whole point: how many instances this draws is a number in a buffer that the
				// CPU never reads. An empty run costs the command fetch and nothing else.
				pass.drawIndexedIndirect(instancer.commandsSlice(first, last - first), last - first);
				BlazeStats.indirectCalls++;
			} else {
				for (int i = first; i < last; i++) {
					var mesh = draws.get(i)
							.mesh();
					pass.drawIndexed(mesh.indexCount(), instancer.instanceCount(), mesh.firstIndex(),
							mesh.baseVertex(), 0);
					BlazeStats.directCalls++;
				}
			}

			first = last;
		}
	}

	/**
	 * Whether two draws can go in one indirect call.
	 *
	 * <p>Everything the pipeline is built from and everything bound before it: the pipeline state, the
	 * texture, and the filter that texture is sampled with. Nothing else varies between two draws of
	 * one instancer -- they share the type, the instance buffer and the environment by construction.
	 */
	private static boolean belongsToTranslucentPass(BlazeDraw draw) {
		return BlazeMaterials.isTranslucent(BlazeMaterials.Key.of(draw.material()));
	}

	private static boolean sharesState(BlazeDraw a, BlazeDraw b) {
		if (b.isEmpty() || belongsToTranslucentPass(a) != belongsToTranslucentPass(b)) {
			return false;
		}

		return BlazeMaterials.Key.of(a.material())
				.equals(BlazeMaterials.Key.of(b.material()))
				&& a.material()
						.texture()
						.equals(b.material()
								.texture())
				&& a.material()
						.blur() == b.material()
								.blur();
	}

	/**
	 * The pipeline for one type drawn with one material's state, built once.
	 *
	 * <p>{@code containsKey} rather than {@code computeIfAbsent}, which will not store a null: a
	 * shader that did not compile would otherwise be rebuilt, and re-logged, every frame.
	 */
	private @Nullable GeneratedPipeline pipelineFor(BlazeInstancer<?> instancer,
			BlazeMaterials.Key material) {
		var key = new PipelineKey(instancer.type, material,
				instancer.environment.matrixIndex() != 0);

		if (!pipelines.containsKey(key)) {
			pipelines.put(key, GeneratedPipeline.of(instancer, material));
		}

		return pipelines.get(key);
	}

	/**
	 * What a pipeline is cached by.
	 *
	 * <p>The shader comes from the type, the state and the rest of the shader from the material --
	 * and {@code embedded} from neither. Whether instances carry their own coordinate space is a
	 * property of the instancer, and {@code smooth_when_embedded} asks about it at compile time, so
	 * two instancers of one type and material still need separate pipelines when they differ in it.
	 */
	private record PipelineKey(Object type, BlazeMaterials.Key material, boolean embedded) {
	}

	private static @Nullable GpuTextureView textureOf(net.minecraft.resources.Identifier location) {
		var texture = Minecraft.getInstance()
				.getTextureManager()
				.getTexture(location);

		return texture == null ? null : texture.getTextureView();
	}

	/** Sorts draws the way the OpenGL backend does, so state changes come in runs. */
	static final Comparator<BlazeDraw> ORDER = Comparator
			.comparingInt(BlazeDraw::bias)
			.thenComparingInt(BlazeDraw::indexOfMeshInModel)
			.thenComparing(BlazeDraw::material, MaterialRenderState.COMPARATOR);

	/** Scratch for building the small per-frame buffers. */
	static ByteBuffer scratch(int ints) {
		return ByteBuffer.allocateDirect(ints * Integer.BYTES)
				.order(ByteOrder.nativeOrder());
	}

	static int storageUsage() {
		return FlwBufferUsage.STORAGE | GpuBuffer.USAGE_COPY_DST;
	}
}
