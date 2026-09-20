#version 300 es

// One oversized triangle covering the clip volume; the fragment shaders do
// all the work from gl_FragCoord.
in vec2 aPosition;
out vec2 vUv;

void main() {
    vUv = aPosition * 0.5 + 0.5;
    gl_Position = vec4(aPosition, 0.0, 1.0);
}
