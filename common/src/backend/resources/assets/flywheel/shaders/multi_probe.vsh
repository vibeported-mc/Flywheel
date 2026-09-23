#version 460 core

// The engine's real draw shape, at its smallest: several models, many instances each, all of it in
// one indirect call whose commands the CPU never wrote and never read.

in vec3 Position;

// Per-instance data for every model at once, packed end to end. A draw finds its own slice through
// ModelData rather than through the command, which is what lets one buffer serve every model.
uniform usamplerBuffer InstanceData;

// One texel per draw: .x instanceCount, .y baseInstance. Indexed by gl_DrawID.
//
// This is what replaces a non-zero firstInstance, and it is not an optimisation. Blaze3D's Vulkan
// device never enables drawIndirectFirstInstance, so a command carrying one is invalid there and is
// dropped in silence -- the instance counts read back exactly right and the model is simply absent
// from the frame. Every command therefore says zero and the base is looked up here instead, which
// costs one texelFetch and works on both backends: shaderDrawParameters is required on Vulkan, and
// gl_DrawID is core in 460.
uniform usamplerBuffer ModelData;

out vec4 vColor;

void main() {
	gl_Position = vec4(Position, 1.0);

	uint base = texelFetch(ModelData, gl_DrawID).y;
	uvec4 data = texelFetch(InstanceData, int(base) + gl_InstanceID);

	vColor = vec4(data) / 255.0;
}
