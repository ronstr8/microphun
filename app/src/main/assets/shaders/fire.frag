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

// Set by FireLayer.
uniform sampler2D uImage;    // what is burning: a frame of another visual, or a photo
uniform float uImageAspect;  // its width over height, so it covers the screen undistorted
uniform float uImageFlip;    // 1 for a photo: bitmaps store their top row first, GL its bottom
uniform float uBurn;         // 0 untouched, 1 the fire has reached the centre
uniform float uHeat;         // how fierce the flames are right now, with the beat
uniform float uPoof;         // 1 the moment an image is swallowed, decaying
uniform sampler2D uNoise;    // 256×256 random bytes, read texel by texel

out vec4 fragColor;

// A random value per lattice point, 0..1, looked up rather than computed.
//
// It has to be exact, and arithmetic hashes were not on the Adreno driver. The
// usual float hash amplifies rounding, and the compiler rounds the same corner
// slightly differently in neighbouring cells, so every cell edge showed as a
// seam. An integer hash came out nearly constant. A table read by integer
// coordinate is the same value however it is reached.
float hash(vec2 p) {
    return texelFetch(uNoise, ivec2(floor(p)) & 255, 0).r;
}

float noise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
               mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
}

float fbm(vec2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int i = 0; i < 4; i++) {
        v += a * noise(p);
        p = p * 2.03 + vec2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

// Black body-ish: deep red through orange and yellow to white as t rises.
vec3 flame(float t) {
    t = clamp(t, 0.0, 1.0);
    vec3 c = mix(vec3(0.35, 0.02, 0.0), vec3(1.0, 0.25, 0.02), smoothstep(0.0, 0.35, t));
    c = mix(c, vec3(1.0, 0.72, 0.15), smoothstep(0.35, 0.7, t));
    return mix(c, vec3(1.0, 0.97, 0.85), smoothstep(0.7, 1.0, t));
}

// An image burning in from the edges, drawn under nothing: this visual is all
// backdrop.
//
// The front is a rounded rectangle closing on the centre — a superellipse, so
// the corners catch first and the last thing to go is a point in the middle —
// roughened by noise that drifts upward, which is what makes the edge read as
// flames licking rather than a wipe. Behind the front is char and embers; just
// ahead of it the image scorches and shimmers in the heat.
void main() {
    vec2 uv = gl_FragCoord.xy / uResolution;
    float aspect = uResolution.x / uResolution.y;

    // How deep into the image this pixel is: 0 at the middle of each edge,
    // negative in the corners, 1 at the centre.
    vec2 q = abs(uv - 0.5) * 2.0;
    float depth = 1.0 - pow(pow(q.x, 4.0) + pow(q.y, 4.0), 0.25);

    // Flame tongues: noise in screen space, scrolling up.
    vec2 np = vec2(uv.x * aspect, uv.y) * 5.0;
    float lick = fbm(np - vec2(0.0, uTime * (1.2 + 1.5 * uHeat)));
    float flicker = fbm(np * 2.3 + vec2(uTime * 0.7, -uTime * 3.0));

    float front = uBurn * 1.15 - 0.1;
    float d = depth - front - (lick - 0.5) * (0.18 + 0.10 * uHeat);

    float band = 0.035 + 0.075 * uHeat;

    // Cover-fit the image, and let the heat just ahead of the front ripple it.
    vec2 iuv = uv - 0.5;
    if (aspect > uImageAspect) {
        iuv.y *= uImageAspect / aspect;
    } else {
        iuv.x *= aspect / uImageAspect;
    }
    float shimmer = smoothstep(band + 0.18, band, d) * step(0.0, d);
    if (uImageFlip > 0.5) iuv.y = -iuv.y;
    iuv += (vec2(flicker, lick) - 0.5) * 0.02 * shimmer;
    vec3 image = texture(uImage, iuv + 0.5).rgb;

    // Scorch: browned and darkened as the front approaches.
    float scorch = smoothstep(band + 0.14, band, d);
    image = mix(image, image * vec3(0.45, 0.28, 0.14), scorch * 0.85);

    vec3 col = image;

    // The flames themselves: hottest right at the burning edge.
    float inFlame = step(0.0, d) * step(d, band);
    float t = (1.0 - d / band) * (0.55 + 0.45 * flicker) + 0.35 * uHeat;
    col = mix(col, flame(t) * (0.9 + 0.9 * uHeat), inFlame);

    // A glow that bleeds outward over the image, brighter with the beat.
    float glow = exp(-max(d - band, 0.0) * 18.0) * step(band, d);
    col += flame(0.6) * glow * (0.25 + 0.6 * uHeat);

    // Behind the front: black, with embers dying out away from the edge.
    if (d < 0.0) {
        vec2 cell = floor(gl_FragCoord.xy / 5.0);
        float spark = step(0.965, hash(cell + floor(uTime * 3.0)));
        float ember = spark * exp(d * 10.0) * (0.6 + 0.8 * uHeat);
        col = flame(0.45 + 0.4 * flicker) * ember;
        col += flame(0.3) * exp(d * 30.0) * (0.35 + 0.5 * uHeat);
    }

    // The poof: a flash out of the centre as the next image arrives.
    float r = length((uv - 0.5) * vec2(aspect, 1.0));
    float ring = smoothstep(0.12, 0.0, abs(r - (1.0 - uPoof) * 1.4)) * uPoof;
    col = mix(col, vec3(1.0, 0.92, 0.75), uPoof * uPoof * uPoof * 0.9);
    col += flame(0.85) * ring * 1.2;

    fragColor = vec4(col, 1.0);
}
