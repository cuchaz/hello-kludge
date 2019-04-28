#version 450

layout(location = 0) in vec3 fragColor;

layout(location = 0) out vec4 outColor; // location references attachment 0 in the graphics pipeline

void main() {
	outColor = vec4(fragColor, 1.0);
}
