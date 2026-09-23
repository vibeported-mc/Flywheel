package dev.engine_room.flywheel.backend.engine.blaze;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import com.mojang.blaze3d.IndexType;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.api.instance.Instance;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.backend.FlwBackend;
import dev.engine_room.flywheel.backend.compute.FlwBufferUsage;
import dev.engine_room.flywheel.backend.engine.AbstractInstancer;
import dev.engine_room.flywheel.backend.engine.DrawManager;
import dev.engine_room.flywheel.backend.engine.InstancerKey;
import dev.engine_room.flywheel.backend.engine.LightStorage;
import dev.engine_room.flywheel.backend.engine.MaterialRenderState;
import dev.engine_room.flywheel.backend.engine.embed.EnvironmentStorage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;

/**
 * Turns a frame's worth of instancers into draws, through Blaze3D.
 *
 * <p>The Blaze3D counterpart of {@code IndirectDrawManager}, and the place all the pieces meet: the
 * mesh pool holds the geometry, each instancer holds its instances, and once a frame this writes
 * what the GPU needs to draw them and opens one render pass to do it.
 *
 * <h2>What this does not do yet</h2>
 *
 * <p>Draws every instance, with no culling pass, and ignores material state, fog, cutout, light and
 * crumbling. That is deliberate and it is temporary: culling, materials and light are each a piece
 * of work with their own failure modes, and putting them in before anything renders at all would
 * mean debugging them all at once against a black screen. The mechanisms for all of them are proven
 * -- {@code CullSelfTest} draws exactly what a compute pass chose -- so what is left is wiring them
 * to this, one at a time, against a picture that already works.
 */
public class BlazeDrawManager extends DrawManager<BlazeInstancer<?>> {
	private final BlazeMeshPool meshPool = new BlazeMeshPool();
	private final BlazeUniforms uniforms = new BlazeUniforms();
	private final BlazeLight light = new BlazeLight();
	private final BlazeEnvironments environments = new BlazeEnvironments();

	/** One pipeline per instance type, since the shader is generated from its layout. */
	private final Map<PipelineKey, @Nullable GeneratedPipeline> pipelines = new HashMap<>();

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

		submit(drawable);
	}

	@Override
	public void renderCrumbling(List<Engine.CrumblingBlock> crumblingBlocks) {
		// Not yet. Crumbling is a separate draw path rather than a material swap -- it draws the
		// same instances again through a different pipeline with the destroy texture -- and it is
		// worth having only once the ordinary path is right.
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

		environments.close();
		light.close();
		meshPool.close();
		uniforms.close();
		GeneratedShaders.clear();
	}

	private void submit(List<BlazeInstancer<?>> drawable) {
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

		// No clear on either attachment: this draws into the level as it stands, after the terrain
		// and before whatever comes next. Clearing would erase the world.
		try (RenderPass pass = encoder.createRenderPass(() -> "flywheel", color, Optional.empty(),
				depth, OptionalDouble.empty())) {

			pass.setVertexBuffer(0, vertices.slice());
			pass.setIndexBuffer(indices, IndexType.INT);

			for (BlazeInstancer<?> instancer : drawable) {
				draw(pass, instancer);
			}
		}
	}

	private void draw(RenderPass pass, BlazeInstancer<?> instancer) {
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

		var samplers = RenderSystem.getSamplerCache();
		GpuTextureView lightmap = Minecraft.getInstance().gameRenderer.lightmap();
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

			// One pipeline per instance type and material state together: the type decides the
			// shader, the material decides the blending, the depth test and the write mask. On 26.2
			// all of that is baked into the pipeline rather than set before the draw, so two draws
			// that differ in any of it cannot share one.
			var material = BlazeMaterials.Key.of(draw.material());
			GeneratedPipeline pipeline = pipelineFor(instancer, material);

			if (pipeline == null) {
				continue;
			}

			pass.setPipeline(pipeline.pipeline());
			pass.setUniform(BlazeUniforms.BLOCK_NAME, uniforms.slice());
			pass.setUniform(BlazeEnvironments.BLOCK_NAME, environment);
			pass.setUniform("_flw_instances", instanceSlice);
			pass.setUniform("_flw_lightSections", lightSections);
			pass.setUniform("_flw_lightLut", lightLut);

			// Nearest for the atlas, because a block texture filtered linearly bleeds between
			// neighbouring sprites; linear for the lightmap, which is a gradient and wants it.
			pass.bindTexture("Sampler0", diffuse, samplers.getClampToEdge(
					draw.material()
							.blur() ? FilterMode.LINEAR : FilterMode.NEAREST));
			pass.bindTexture("Sampler2", lightmap, samplers.getClampToEdge(FilterMode.LINEAR));

			var mesh = draw.mesh();
			pass.drawIndexed(mesh.indexCount(), instancer.instanceCount(), mesh.firstIndex(),
					mesh.baseVertex(), 0);
		}
	}

	/**
	 * The pipeline for one type drawn with one material's state, built once.
	 *
	 * <p>{@code containsKey} rather than {@code computeIfAbsent}, which will not store a null: a
	 * shader that did not compile would otherwise be rebuilt, and re-logged, every frame.
	 */
	private @Nullable GeneratedPipeline pipelineFor(BlazeInstancer<?> instancer,
			BlazeMaterials.Key material) {
		var key = new PipelineKey(instancer.type, material);

		if (!pipelines.containsKey(key)) {
			pipelines.put(key, GeneratedPipeline.of(instancer, material));
		}

		return pipelines.get(key);
	}

	/** What a pipeline is cached by: the shader comes from the type, the state from the material. */
	private record PipelineKey(Object type, BlazeMaterials.Key material) {
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
