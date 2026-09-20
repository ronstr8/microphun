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

// Backdrop for the word cloud. Deliberately dark and low contrast: the words
// are drawn over this by WordLayer and have to stay readable, so the field
// breathes rather than flashes and never climbs far off black.
void main() {
    vec2 p = (gl_FragCoord.xy * 2.0 - uResolution) / min(uResolution.x, uResolution.y);
    float r = length(p);

    float t = uTime * 0.12;

    // Two slow interfering fields, drifting in opposite directions.
    float v = sin(p.x * 1.7 + t) + sin(p.y * 2.1 - t * 1.3);
    v += sin((p.x - p.y) * 1.3 + t * 0.6);
    v *= 0.333;

    // The band ladder sweeps up the screen, so the room's spectrum is still
    // legible behind the text as a soft horizontal banding.
    float band = uBands[int(clamp(gl_FragCoord.y / uResolution.y, 0.0, 0.999) * float($BAND_COUNT))];

    vec3 col = palette(v * 0.25 + t) * (0.05 + 0.10 * uLevel + 0.12 * band);

    // Bass blooms out of the centre; onsets add a brief rim, well away from the
    // middle of the screen where most words sit.
    col += palette(t * 2.0) * uBass * 0.10 * smoothstep(1.2, 0.0, r);
    col += vec3(0.5, 0.35, 0.9) * uOnset * 0.12 * smoothstep(0.5, 1.5, r);
    col += vec3(0.2, 0.25, 0.35) * uTreble * 0.05;

    col *= 1.0 - 0.25 * dot(p, p);

    fragColor = vec4(col, 1.0);
}
