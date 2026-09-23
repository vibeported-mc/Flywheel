#version 460 core

// The full GPU-driven path: what gets drawn was decided on the GPU, and this shader has to find its
// instance through that decision rather than around it.

in vec3 Position;

uniform usamplerBuffer InstanceData;

// One texel per draw: .x instanceCount, .y where this model's survivors start.
uniform usamplerBuffer ModelData;

// Written by the cull pass: the indices of the instances that survived, packed together. An
// instance's position in here has nothing to do with its position in InstanceData, which is the
// whole point -- three instances thinned to one must draw one, not draw three and hide two.
uniform usamplerBuffer VisibleIndices;

out vec4 vColor;

void main() {
	gl_Position = vec4(Position, 1.0);

	uint base = texelFetch(ModelData, gl_DrawID).y;

	// Two hops. gl_InstanceID counts survivors, VisibleIndices turns that into the real instance,
	// and only then does InstanceData mean anything. Reading InstanceData with gl_InstanceID
	// directly would draw the right *number* of things with the wrong data, which looks close
	// enough to right to survive a careless test.
	uint instance = texelFetch(VisibleIndices, int(base) + gl_InstanceID).x;
	uvec4 data = texelFetch(InstanceData, int(instance));

	vColor = vec4(data) / 255.0;
}
