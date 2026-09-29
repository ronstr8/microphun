#version 300 es

// One crack segment is a quad, two triangles, in screen pixels. aEdge runs
// from -1 on one long side to 1 on the other, so the fragment shader can fade
// a line from its bright core out to nothing.
in vec2 aPosition;
in float aEdge;

uniform vec2 uResolution;

out float vEdge;

void main() {
    vEdge = aEdge;
    gl_Position = vec4(aPosition / uResolution * 2.0 - 1.0, 0.0, 1.0);
}
