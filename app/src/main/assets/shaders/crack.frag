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

// Set by CrackLayer.
uniform sampler2D uImage;    // the glass is over this: a frame of another visual
uniform float uImageAspect;
uniform sampler2D uMask;     // accumulated crack coverage, 0..1 in red
uniform vec2 uImpactPos;     // pixels, the most recent blow
uniform float uFlash;        // 1 on a blow, decaying
uniform float uShatter;      // 0 while the glass holds; runs to 1 as it goes
uniform float uReveal;       // 0 as a new image arrives, easing to 1

out vec4 fragColor;

// Cracked glass over an image.
//
// The mask holds where the cracks are, soft across their width. From it: the
// image refracts along each crack (the mask's slope bends the lookup), the
// glass darkens either side of a crack where the break catches no light, and
// the crack's core catches a highlight that brightens with the room. When the
// glass gives way the cracks flare white and everything goes dark.
void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    float aspect = uResolution.x / uResolution.y;

    vec2 texel = 1.0 / uResolution;
    float m = texture(uMask, uv).r;
    float mx = texture(uMask, uv + vec2(texel.x * 3.0, 0.0)).r - texture(uMask, uv - vec2(texel.x * 3.0, 0.0)).r;
    float my = texture(uMask, uv + vec2(0.0, texel.y * 3.0)).r - texture(uMask, uv - vec2(0.0, texel.y * 3.0)).r;
    vec2 slope = vec2(mx, my);

    // Cover-fit the image, bent along the cracks.
    vec2 iuv = uv - 0.5;
    if (aspect > uImageAspect) {
        iuv.y *= uImageAspect / aspect;
    } else {
        iuv.x *= aspect / uImageAspect;
    }
    iuv += slope * 0.012;
    vec3 col = texture(uImage, iuv + 0.5).rgb;

    // Either side of a crack the break is dark; its core catches the light.
    float rim = smoothstep(0.02, 0.35, m) * (1.0 - smoothstep(0.55, 0.9, m));
    col *= 1.0 - 0.6 * rim;
    float core = smoothstep(0.55, 1.0, m);
    col += vec3(0.85, 0.92, 1.0) * core * (0.45 + 0.7 * uLevel);

    // A glint that sweeps across the facets with time.
    vec2 light = vec2(cos(uTime * 0.4), sin(uTime * 0.4));
    float glint = pow(max(dot(normalize(slope + 1e-5), light), 0.0), 12.0) * length(slope);
    col += vec3(1.0) * glint * 1.5;

    // The blow itself: a flash at the point of impact.
    float d = distance(gl_FragCoord.xy, uImpactPos) / min(uResolution.x, uResolution.y);
    col += vec3(1.0, 0.96, 0.9) * uFlash * uFlash * exp(-d * 9.0);

    // Giving way: cracks flare, then everything goes dark.
    col += vec3(0.9, 0.95, 1.0) * m * sin(uShatter * 3.14159) * 2.0;
    col *= 1.0 - smoothstep(0.35, 0.95, uShatter);

    // The next image eases in from black.
    col *= uReveal;

    fragColor = vec4(col, 1.0);
}
