package dev.engine_room.flywheel.backend.engine.blaze;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.vertex.VertexFormat;

import dev.engine_room.flywheel.backend.InternalVertex;

/**
 * Flywheel's vertex format, described to Blaze3D.
 *
 * <p>The bytes are exactly {@link InternalVertex}'s -- the same 36-byte stride the OpenGL backend
 * uses, written by the same {@code FullVertexView} through the same setters. Only the description
 * is new: where the OpenGL backend hands a list of attributes to {@code glVertexAttribPointer}
 * itself, a Blaze3D pipeline is given a {@link VertexFormat} and does the binding.
 *
 * <p>The names matter and are not free choices. A generated vertex shader declares its inputs by
 * these names, and Vulkan's shader compiler matches them against this format to assign locations --
 * a name here that a shader does not declare is an attribute that never arrives, with no error on
 * either side.
 *
 * <p>The offsets are likewise not free: they mirror {@code InternalVertex.LAYOUT} field for field,
 * and Blaze3D checks each one against its format's alignment on the way in. Should that layout ever
 * change, this fails loudly at startup rather than reading vertices crookedly.
 */
public final class BlazeVertex {
	public static final VertexFormat FORMAT = VertexFormat.builder(0)
			.addAttribute("flw_position", GpuFormat.RGB32_FLOAT)
			.addAttribute("flw_color", GpuFormat.RGBA8_UNORM)
			.addAttribute("flw_texCoord", GpuFormat.RG32_FLOAT)
			.addAttribute("flw_overlay", GpuFormat.RG16_SINT)
			.addAttribute("flw_light", GpuFormat.RG16_UINT)
			// Advanced by 4 rather than its own 3 bytes, because InternalVertex pads the normal out
			// to a 36-byte stride -- and a vertex size that is not a multiple of 4 is rejected.
			.addAttribute("flw_normal", 4, GpuFormat.RGB8_SNORM)
			.build();

	static {
		// The builder derives offsets by appending, so this is the one thing that could silently
		// disagree with the bytes being written. Checking it here turns a wrong stride into a
		// startup failure instead of geometry that reads every vertex from the wrong place -- which
		// is what the first version of this did, having used an overload whose `stride` argument
		// means the gap between a matrix's columns rather than between vertices.
		if (FORMAT.getVertexSize() != InternalVertex.STRIDE) {
			throw new IllegalStateException("vertex format is " + FORMAT.getVertexSize()
					+ " bytes but InternalVertex writes " + InternalVertex.STRIDE);
		}
	}

	private BlazeVertex() {
	}
}
