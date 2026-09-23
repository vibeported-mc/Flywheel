package dev.engine_room.flywheel.backend.compute.gl;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.opengl.GL44C;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.opengl.GlBuffer;
import com.mojang.blaze3d.opengl.GlStateManager;

import dev.engine_room.flywheel.backend.compute.BarrierScope;
import dev.engine_room.flywheel.backend.compute.Blaze3dx;
import dev.engine_room.flywheel.backend.compute.ComputePass;
import dev.engine_room.flywheel.backend.compute.ComputePipeline;

public final class GlComputePass implements ComputePass {
	/**
	 * How many texture units {@code GlStateManager} keeps a shadow of.
	 *
	 * <p>It holds the bound texture per unit in an array of exactly this many and indexes it with
	 * whatever {@code _activeTexture} was last given. The driver has far more units, and binding to
	 * unit 15 is perfectly legal OpenGL -- it is Minecraft's bookkeeping that has no slot for it,
	 * and the result is an {@code ArrayIndexOutOfBoundsException} out of the middle of a frame
	 * rather than a GL error.
	 */
	private static final int SHADOWED_TEXTURE_UNITS = 12;

	private final String label;
	private final List<Integer> boundBindings = new ArrayList<>(4);
	private final List<Integer> boundTextureUnits = new ArrayList<>(2);
	private boolean closed;

	GlComputePass(String label) {
		this.label = label;
	}

	@Override
	public void setPipeline(ComputePipeline pipeline) {
		checkOpen();
		if (!(pipeline instanceof GlComputePipeline gl)) {
			throw new IllegalArgumentException(
					"pipeline " + pipeline.label() + " was not built by the OpenGL backend");
		}
		GlStateManager._glUseProgram(gl.program());
	}

	@Override
	public void bindStorageBuffer(int binding, GpuBufferSlice slice) {
		checkOpen();
		if (!(slice.buffer() instanceof GlBuffer buffer)) {
			throw new IllegalArgumentException("buffer was not allocated by the OpenGL backend");
		}
		GL30C.glBindBufferRange(GL43C.GL_SHADER_STORAGE_BUFFER, binding, buffer.handle(),
				slice.offset(), slice.length());
		boundBindings.add(binding);
	}

	/**
	 * Binds a texture for the shader to sample, on the unit the shader itself names.
	 *
	 * <p>The unit is the binding, and that is not a choice. {@code FLW_SET(n)} expands to nothing on
	 * this backend, so a declaration written for Vulkan as
	 * {@code layout(FLW_SET(0) binding = 6) uniform sampler2D} reaches the GLSL compiler as
	 * {@code layout(binding = 6)} -- and on a sampler that <em>is</em> the texture unit, fixed at
	 * compile time with no uniform to assign afterwards.
	 *
	 * <p>An earlier version bound high instead, on the reasoning that Blaze3D hands sampler units out
	 * from zero upwards and the low ones belong to the next draw. Which is true, and beside the
	 * point: the shader was still reading unit 6 and found whatever happened to be there. Every
	 * texel came back zero, nothing was ever occluded, and no error said so.
	 *
	 * <p>Trampling is handled by {@link #close()} instead -- it unbinds each unit it touched, puts
	 * the active unit back to zero and drops the program cache, so the next draw re-establishes its
	 * own samplers. Which is the reason the whole pass is an object rather than a few static calls.
	 */
	@Override
	public void bindTexture(int binding, GpuTextureView view, GpuSampler sampler) {
		checkOpen();
		if (binding < ComputePipeline.FIRST_IMAGE_BINDING) {
			throw new IllegalArgumentException("binding " + binding + " is not an image binding; "
					+ "images live from " + ComputePipeline.FIRST_IMAGE_BINDING + " upwards");
		}
		if (binding >= SHADOWED_TEXTURE_UNITS) {
			throw new IllegalArgumentException("binding " + binding + " is beyond the "
					+ SHADOWED_TEXTURE_UNITS + " texture units GlStateManager tracks; binding it "
					+ "would throw out of the middle of a frame");
		}
		if (!(view instanceof GlTextureView glView)) {
			throw new IllegalArgumentException("texture view was not created by the OpenGL backend");
		}

		int unit = binding;

		GlStateManager._activeTexture(GL30C.GL_TEXTURE0 + unit);
		GlStateManager._bindTexture(glView.texture()
				.glId());

		// Sampler state comes from the texture object on this path rather than from a sampler
		// object, which is enough for the nearest-neighbour reads a depth pyramid does.
		//
		// NEAREST_MIPMAP_NEAREST rather than plain NEAREST, although nothing here filters between
		// levels and texelFetch ignores the filter entirely. What it does not ignore is
		// completeness, and completeness is judged against the filter: with a non-mipmap minifier
		// OpenGL considers only the base level, and a texelFetch naming any other lod is undefined.
		// In practice it returns zero -- so a shader picking its level by how big something looks on
		// screen reads a pyramid of zeroes for everything but the closest, with no error anywhere.
		GL30C.glTexParameteri(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MIN_FILTER,
				GL30C.GL_NEAREST_MIPMAP_NEAREST);
		GL30C.glTexParameteri(GL30C.GL_TEXTURE_2D, GL30C.GL_TEXTURE_MAG_FILTER, GL30C.GL_NEAREST);

		boundTextureUnits.add(unit);
	}

	@Override
	public void dispatch(int x, int y, int z) {
		checkOpen();
		GL43C.glDispatchCompute(x, y, z);
	}

	@Override
	public void barrier(int scopes) {
		checkOpen();
		GL43C.glMemoryBarrier(toGlBits(scopes));
	}

	/**
	 * Put back everything Blaze3D assumes it still owns.
	 *
	 * <p>This is the whole reason a compute pass is an object rather than a few static calls.
	 * {@code GlCommandEncoder} caches the program and pipeline it last bound and skips
	 * {@code glUseProgram} when the next draw asks for the same one. A dispatch changes the bound
	 * program behind that cache, so without the invalidation below the next draw would silently
	 * render with the compute program still current, which looks like geometry vanishing with no
	 * error reported anywhere.
	 *
	 * <p>Storage buffer bindings are released too. They are a namespace of their own that Blaze3D
	 * never touches, so that part is hygiene rather than necessity, but a stale binding surviving
	 * into an unrelated dispatch is a genuinely miserable bug to track down.
	 */
	@Override
	public void close() {
		if (closed) {
			return;
		}
		closed = true;

		for (int binding : boundBindings) {
			GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, binding, 0);
		}
		boundBindings.clear();

		// Sampler units are not ours either. Blaze3D hands them out from zero upwards and leaves the
		// active unit wherever it was, so a pass that binds high and walks away leaves the next draw
		// selecting a unit nobody set up.
		for (int unit : boundTextureUnits) {
			GlStateManager._activeTexture(GL30C.GL_TEXTURE0 + unit);
			GlStateManager._bindTexture(0);
		}
		boundTextureUnits.clear();
		GlStateManager._activeTexture(GL30C.GL_TEXTURE0);

		GlStateManager._glUseProgram(0);
		Blaze3dx.invalidateProgramCache();
	}

	private void checkOpen() {
		if (closed) {
			throw new IllegalStateException("compute pass " + label + " is already closed");
		}
	}

	private static int toGlBits(int scopes) {
		int bits = 0;
		if ((scopes & BarrierScope.STORAGE) != 0) {
			bits |= GL43C.GL_SHADER_STORAGE_BARRIER_BIT;
		}
		if ((scopes & BarrierScope.INDEX) != 0) {
			bits |= GL43C.GL_ELEMENT_ARRAY_BARRIER_BIT;
		}
		if ((scopes & BarrierScope.INDIRECT) != 0) {
			bits |= GL43C.GL_COMMAND_BARRIER_BIT;
		}
		if ((scopes & BarrierScope.VERTEX_ATTRIB) != 0) {
			bits |= GL43C.GL_VERTEX_ATTRIB_ARRAY_BARRIER_BIT;
		}
		if ((scopes & BarrierScope.TEXTURE_FETCH) != 0) {
			bits |= GL43C.GL_TEXTURE_FETCH_BARRIER_BIT;
		}
		if ((scopes & BarrierScope.BUFFER_UPDATE) != 0) {
			bits |= GL43C.GL_BUFFER_UPDATE_BARRIER_BIT;
		}
		if ((scopes & BarrierScope.CLIENT_MAPPED_BUFFER) != 0) {
			// GL 4.4, arriving with ARB_buffer_storage alongside persistent mapping itself.
			bits |= GL44C.GL_CLIENT_MAPPED_BUFFER_BARRIER_BIT;
		}
		return bits;
	}
}
