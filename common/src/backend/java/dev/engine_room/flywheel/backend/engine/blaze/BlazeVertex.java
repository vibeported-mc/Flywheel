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
			.addAttribute("flw_position", 0, InternalVertex.STRIDE, GpuFormat.RGB32_FLOAT, 1)
			.addAttribute("flw_color", 12, InternalVertex.STRIDE, GpuFormat.RGBA8_UNORM, 1)
			.addAttribute("flw_texCoord", 16, InternalVertex.STRIDE, GpuFormat.RG32_FLOAT, 1)
			.addAttribute("flw_overlay", 24, InternalVertex.STRIDE, GpuFormat.RG16_SINT, 1)
			.addAttribute("flw_light", 28, InternalVertex.STRIDE, GpuFormat.RG16_UINT, 1)
			.addAttribute("flw_normal", 32, InternalVertex.STRIDE, GpuFormat.RGB8_SNORM, 1)
			.build();

	private BlazeVertex() {
	}
}
