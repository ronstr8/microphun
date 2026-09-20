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

// Cosine gradient palette: cheap, always in gamut, easy to shift by phase.
vec3 palette(float t, vec3 phase) {
    return 0.5 + 0.5 * cos(TAU * (t + phase));
}

void main() {
    vec2 p = (gl_FragCoord.xy * 2.0 - uResolution) / min(uResolution.x, uResolution.y);

    // Onsets punch the zoom, bass stretches the whole field.
    p *= 1.0 - 0.25 * uOnset - 0.15 * uBass;

    float t = uTime * 0.35 + uMid * 2.0;

    float v = 0.0;
    v += sin(p.x * 3.0 + t);
    v += sin(p.y * 4.0 - t * 1.3);
    v += sin((p.x + p.y) * 2.5 + t * 0.7);
    v += sin(length(p * 3.0) * 4.0 - t * 2.0);
    v *= 0.25;

    // Treble ripples the isolines, so cymbals read as fine detail.
    v += 0.15 * uTreble * sin(length(p) * 30.0 - uTime * 6.0);

    vec3 col = palette(v + uTime * 0.05, vec3(0.0, 0.33, 0.67) + uBass * 0.4);

    col *= 0.35 + 1.1 * uLevel;
    col += vec3(0.6, 0.25, 0.9) * uOnset * 0.35;
    col *= 1.0 - 0.175 * dot(p, p);

    fragColor = vec4(col, 1.0);
}
