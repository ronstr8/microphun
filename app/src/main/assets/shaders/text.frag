#version 300 es
precision mediump float;

uniform sampler2D uGlyph;
uniform float uHue;
uniform float uAlpha;
uniform float uGlow;

in vec2 vUv;

out vec4 fragColor;

const float TAU = 6.28318530718;

vec3 palette(float t) {
    return 0.55 + 0.45 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

void main() {
    // The glyph bitmap is premultiplied: white ink where the letters are, and a
    // black halo around them. Alpha is the combined coverage, so using rgb as
    // the ink mask tints the letters while the halo only darkens whatever is
    // behind them — which is what keeps a word readable over a bright visual.
    vec4 glyph = texture(uGlyph, vUv);
    vec3 ink = palette(uHue) * glyph.r * (0.85 + 1.4 * uGlow);

    fragColor = vec4(ink, glyph.a) * uAlpha;
}
