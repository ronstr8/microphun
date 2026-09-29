#version 300 es
precision mediump float;

in float vEdge;

out vec4 fragColor;

// Writes crack coverage into the mask: 1 along the core, falling off across
// the width. Drawn with GL_MAX blending, so crossing cracks keep the brighter
// of the two instead of adding up into a blob.
void main() {
    float e = abs(vEdge);
    float coverage = 1.0 - smoothstep(0.0, 1.0, e);
    fragColor = vec4(coverage * coverage, 0.0, 0.0, 1.0);
}
