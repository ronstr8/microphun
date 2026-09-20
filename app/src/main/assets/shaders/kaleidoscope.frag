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

vec3 palette(float t, vec3 phase) {
    return 0.5 + 0.5 * cos(TAU * (t + phase));
}

float bandAt(float x) {
    int i = int(clamp(x, 0.0, 0.999) * float($BAND_COUNT));
    return uBands[i];
}

// Fold the plane into mirrored wedges — the two-mirror geometry a real
// kaleidoscope is built from. Everything drawn afterwards is reflected into
// every wedge for free.
vec2 kaleido(vec2 p, float segments) {
    float r = length(p);
    float seg = TAU / segments;
    float a = atan(p.y, p.x);
    a = mod(a + seg * 0.5, seg) - seg * 0.5;
    a = abs(a);
    return vec2(cos(a), sin(a)) * r;
}

void main() {
    vec2 p = (gl_FragCoord.xy * 2.0 - uResolution) / min(uResolution.x, uResolution.y);

    // Slow counter-rotation before folding, so the mirrors appear to turn.
    float rot = uTime * 0.13 + uMid * 0.6;
    float cr = cos(rot);
    float sr = sin(rot);
    p = mat2(cr, -sr, sr, cr) * p;

    p = kaleido(p, 10.0);

    // Onsets breathe the whole figure in and out.
    p *= 1.0 - 0.18 * uOnset - 0.08 * uBass;

    float r = length(p);
    float a = atan(p.y, p.x);

    // Mirror radially too: concentric shards rather than smooth rings.
    float shard = abs(fract(r * 1.6 - uTime * 0.12) - 0.5) * 2.0;

    float v = 0.0;
    v += sin(r * 7.0 - uTime * 1.2 + uBass * 5.0);
    v += sin(a * 6.0 + uTime * 0.7);
    v += sin((p.x * 5.0 + p.y * 4.0) - uTime * 0.9);
    v *= 0.333;

    // The spectrum carved into the radius: bass at the centre, treble at the rim.
    v += bandAt(clamp(r * 0.7, 0.0, 1.0)) * 1.3;

    // Treble adds fine facets near the edges.
    v += 0.25 * uTreble * sin(r * 36.0 - uTime * 7.0);

    vec3 col = palette(v * 0.5 + shard * 0.25 + uTime * 0.03,
                       vec3(0.0, 0.35, 0.7) + uBass * 0.3);

    // Bright seams where the mirrored shards meet.
    col += vec3(1.0, 0.95, 0.85) * smoothstep(0.06, 0.0, shard) * (0.12 + 0.5 * uOnset);

    col *= 0.3 + 1.2 * uLevel;
    col += palette(uTime * 0.2, vec3(0.0, 0.33, 0.67)) * uOnset * 0.25;
    col *= smoothstep(2.2, 0.15, r) + 0.08;

    fragColor = vec4(col, 1.0);
}
