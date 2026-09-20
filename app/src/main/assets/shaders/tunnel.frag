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
    return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.25, 0.55)));
}

float bandAt(float x) {
    int i = int(clamp(x, 0.0, 0.999) * float($BAND_COUNT));
    return uBands[i];
}

void main() {
    vec2 p = (gl_FragCoord.xy * 2.0 - uResolution) / min(uResolution.x, uResolution.y);
    float r = length(p);
    float a = atan(p.y, p.x) / TAU + 0.5;

    // Perspective down an infinite tunnel; bass hauls the far wall forward.
    float depth = 1.0 / (r + 0.12) + uTime * (0.6 + uBass * 1.4);
    float ring = fract(depth);

    float energy = bandAt(fract(a * 2.0 + uTime * 0.02));
    float glow = smoothstep(0.9, 0.0, ring) * (0.3 + energy * 1.6);

    vec3 col = palette(depth * 0.15 + a * 0.5 + uMid) * glow;

    // Treble scratches high-frequency rings into the walls.
    col += vec3(0.5, 0.8, 1.0) * uTreble * 0.15 * smoothstep(0.95, 1.0, fract(depth * 8.0));

    col += palette(a) * uOnset * 0.4 * smoothstep(1.2, 0.2, r);
    col *= 0.3 + 1.2 * uLevel;
    col *= smoothstep(1.6, 0.1, r) + 0.15;

    fragColor = vec4(col, 1.0);
}
