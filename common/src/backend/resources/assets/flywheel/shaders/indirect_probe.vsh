#version 460 core

// A probe, not a renderer. It exists to answer one question that nothing else can answer as
// cheaply: can a Blaze3D pipeline run an indirect draw whose vertex shader reads its per-instance
// data out of a buffer? That is the whole mechanism a GPU-driven backend rests on, and on Vulkan it
// is the part with no guarantee -- the draw commands are written by a compute shader that the CPU
// never reads, so nothing on this side knows whether they were right.

// Positions arrive already in clip space, so this needs no transform and therefore declares no
// uniform blocks at all. That is deliberate. On 26.2 an indirect draw must have *every* uniform its
// pipeline declares set on the pass -- GlCommandEncoder.executeDrawIndirect calls trySetup with an
// empty dynamic-uniform list, where an ordinary draw passes the ones the render system is about to
// supply -- so a pipeline inheriting vanilla's snippets would have to supply DynamicTransforms,
// Projection, Fog and Globals here or throw. Sidestepping them keeps this about the mechanism.
in vec3 Position;

// Per-instance data as a uniform texel buffer, which is the one shape Blaze3D can describe to a
// pipeline on both backends: UniformType has no storage buffer, and VulkanBindGroupLayout has no
// descriptor type for one. A compute shader writes this; the vertex shader reads it.
uniform usamplerBuffer InstanceData;

out vec4 vColor;

void main() {
	gl_Position = vec4(Position, 1.0);

	// Indexed by instance, so a wrong instance count or a dropped draw shows up as the wrong
	// colour rather than as nothing at all -- a blank frame is what every unrelated failure looks
	// like, and it would not tell this apart from any of them.
	uvec4 data = texelFetch(InstanceData, gl_InstanceID);
	vColor = vec4(data) / 255.0;
}
