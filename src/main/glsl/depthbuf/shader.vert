#version 450

layout(location = 0) out vec3 fragColor;

vec3 positions[6] = vec3[] (

	// triangle 1, shallower, so on top, but drawn first
	vec3( 0.0 - 0.1, -0.5 - 0.1, 0.2),
	vec3(-0.5 - 0.1,  0.5 - 0.1, 0.2),
	vec3( 0.5 - 0.1,  0.5 - 0.1, 0.2),

	// triangle 2, deeper, so on bottom, but drawn second
	vec3( 0.0 + 0.1, -0.5 + 0.1, 0.4),
	vec3(-0.5 + 0.1,  0.5 + 0.1, 0.4),
	vec3( 0.5 + 0.1,  0.5 + 0.1, 0.4)
);

vec3 colors[6] = vec3[] (

	// triangle 1, red
	vec3(0.6, 0.0, 0.0),
	vec3(0.6, 0.0, 0.0),
	vec3(0.6, 0.0, 0.0),

	// triangle 2, blue
	vec3(0.0, 0.0, 0.6),
	vec3(0.0, 0.0, 0.6),
	vec3(0.0, 0.0, 0.6)
);

void main() {
	gl_Position = vec4(positions[gl_VertexIndex], 1.0);
	fragColor = colors[gl_VertexIndex];
}
