#version 450

layout(binding = 1) uniform sampler2D uniTexSampler;

layout(location = 0) in vec2 inTexCoord;

layout(location = 0) out vec4 outColor;

void main() {
	outColor = texture(uniTexSampler, inTexCoord);
}
