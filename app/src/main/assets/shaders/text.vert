#version 300 es

// One unit quad per word, positioned and sized in screen pixels so the
// rasterised glyph texture keeps its aspect ratio whatever the viewport is.
in vec2 aPosition;

uniform vec2 uResolution;
uniform vec2 uCenter;
uniform vec2 uHalfSize;

out vec2 vUv;

void main() {
    // Bitmap rows arrive top first, clip space counts upwards: flip t.
    vUv = vec2(aPosition.x, 1.0 - aPosition.y);

    vec2 pixels = uCenter + (aPosition * 2.0 - 1.0) * uHalfSize;
    gl_Position = vec4(pixels / uResolution * 2.0 - 1.0, 0.0, 1.0);
}
