#version 300 es
precision highp float;

uniform vec2 uResolution;
uniform float uTime;
uniform float uLevel;
uniform float uBass;
uniform float uMid;
uniform float uTreble;
uniform float uOnset;
uniform float uBands[$BAND_COUNT];

out vec4 fragColor;

const float TAU = 6.28318530718;

vec3 palette(float t) {
    return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

void main() {
    vec2 p = (gl_FragCoord.xy * 2.0 - uResolution) / min(uResolution.x, uResolution.y);
    float r = length(p);
    float a = atan(p.y, p.x) / TAU + 0.5;

    // Mirrored around the vertical axis so the spectrum reads symmetrically.
    float m = abs(a * 2.0 - 1.0);
    int i = int(clamp(m, 0.0, 0.999) * float($BAND_COUNT));
    float h = uBands[i];

    float inner = 0.28 + 0.05 * uBass;
    float outer = inner + h * 0.65;

    float bar = smoothstep(outer, outer - 0.02, r) * smoothstep(inner - 0.02, inner, r);

    vec3 col = palette(float(i) / float($BAND_COUNT) + uTime * 0.05) * bar;

    // Base ring, brightened by onsets, plus a wash that fills the screen.
    col += vec3(0.8, 0.9, 1.0) * smoothstep(0.012, 0.0, abs(r - inner)) * (0.3 + uOnset);
    col += palette(uTime * 0.2) * uOnset * 0.25 * smoothstep(1.4, 0.3, r);
    col += vec3(0.15, 0.05, 0.3) * uMid * smoothstep(1.5, 0.0, r) * 0.5;

    col *= 0.4 + 1.0 * uLevel;

    fragColor = vec4(col, 1.0);
}
