#version 300 es

// One unit quad per word, positioned and sized in screen pixels so the
// rasterised glyph texture keeps its aspect ratio whatever the viewport is.
in vec2 aPosition;

uniform vec2 uResolution;
uniform vec2 uCenter;
uniform vec2 uHalfSize;
// Direction the text reads, radians. Zero, the default, is level: the word
// cloud never sets it, the spiral lays words along its curve.
uniform float uRotation;

out vec2 vUv;

void main() {
    // Bitmap rows arrive top first, clip space counts upwards: flip t.
    vUv = vec2(aPosition.x, 1.0 - aPosition.y);

    vec2 local = (aPosition * 2.0 - 1.0) * uHalfSize;
    float c = cos(uRotation);
    float s = sin(uRotation);
    vec2 pixels = uCenter + vec2(c * local.x - s * local.y, s * local.x + c * local.y);
    gl_Position = vec4(pixels / uResolution * 2.0 - 1.0, 0.0, 1.0);
}
