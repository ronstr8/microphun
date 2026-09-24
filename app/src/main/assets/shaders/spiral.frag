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

// Set by SpiralLayer, which also places the words on the same curve.
uniform float uThrob;        // 1 when a word is heard, decaying
uniform float uWordAge;      // seconds since the last word was heard
uniform float uFill;         // how full the corpus is, 0..1 of what a phrase needs
uniform float uFlow;         // 0 while filling, eases to 1 once phrases can flow
uniform float uSpin;         // turn of the whole spiral, radians
uniform float uPitch;        // k in r = a·e^(kθ)
uniform float uSpawnRadius;  // pixels; where words come out
uniform float uTravel;       // how far words have moved outward, in ρ
uniform float uHue;          // beat-driven colour drift

out vec4 fragColor;

const float TAU = 6.28318530718;

vec3 palette(float t) {
    return 0.5 + 0.5 * cos(TAU * (t + vec3(0.0, 0.33, 0.67)));
}

// Backdrop for the spiral, under the phrases SpiralLayer draws.
//
// Two states, crossfaded by uFlow. While the room is still filling the corpus
// there is nothing to say yet, so the centre throbs with the rhythm of the
// words arriving and swells as the corpus fills. Once phrases can flow, the
// spiral's own arm appears — the lane the words ride — streaming outward with
// them, turned and coloured by the beat.
void main() {
    vec2 d = gl_FragCoord.xy - 0.5 * uResolution;
    float unit = 0.5 * min(uResolution.x, uResolution.y);
    float r = length(d) / unit;
    float a = atan(d.y, d.x + 1e-4);  // atan(0, 0) is undefined on some drivers

    // ---- filling: a heart that beats with the words ----------------------
    // It has to be obvious from across a room that the thing is listening:
    // the first version was a faint dot, and on the device it read as broken.
    float heart = 0.28 + 0.42 * uFill + 0.22 * uThrob;
    float core = smoothstep(heart, heart * 0.15, r);
    float rim = smoothstep(0.10, 0.0, abs(r - heart)) * (0.4 + 0.6 * uFill);
    float rings = 0.5 + 0.5 * sin(r * 14.0 - uTime * 2.0 - uThrob * 3.0);
    rings *= smoothstep(1.6, heart, r) * (0.22 + 0.55 * uThrob);

    // Every word sends a ripple out from the heart.
    float front = heart + uWordAge * 1.1;
    float ripple = smoothstep(0.14, 0.0, abs(r - front)) * exp(-uWordAge * 1.3);

    vec3 filling = palette(uHue + r * 0.35) * (core * (0.55 + 0.9 * uThrob) + rings * 0.6);
    filling += palette(uHue + 0.15) * rim * (0.5 + 0.5 * uThrob);
    filling += palette(uHue + 0.5 - r * 0.2) * ripple * 0.9;
    filling += palette(uHue + 0.5) * (0.07 + 0.15 * uLevel);

    // ---- flowing: the arm the words ride ---------------------------------
    // Same curve as SpiralFlow: a point is on the arm where its angle equals
    // ρ / k + spin, with ρ = ln(r / spawn radius).
    float rho = log(max(length(d), 1.0) / uSpawnRadius);
    float phase = fract((a - rho / uPitch - uSpin) / TAU);
    float lane = min(phase, 1.0 - phase);          // turns away from the arm
    // Narrow enough that the gap between turns stays dark: a wide soft band
    // blurs one turn into the next and the eye reads rings, not a spiral.
    float arm = smoothstep(0.13, 0.0, lane);

    // Bright streaks along the arm, moving outward exactly as fast as the words.
    float streak = 0.5 + 0.5 * sin((rho - uTravel) * 22.0);

    // The arm winds infinitely tight at the centre; fade it in from the spawn
    // radius so the middle is a soft glow rather than a moiré.
    float emerge = smoothstep(-0.5, 0.8, rho);

    vec3 flowing = palette(uHue + rho * 0.09) * arm * (0.16 + 0.14 * streak + 0.35 * uBass);
    flowing += palette(uHue + 0.35) * arm * uOnset * 0.30;
    flowing *= emerge;
    flowing += palette(uHue + 0.6) * smoothstep(0.35, 0.0, r) * (0.12 + 0.25 * uThrob + 0.2 * uLevel);
    flowing += palette(uHue + 0.5) * 0.03;

    vec3 col = mix(filling, flowing, uFlow);
    col *= 1.0 - 0.12 * r * r;

    fragColor = vec4(col, 1.0);
}
