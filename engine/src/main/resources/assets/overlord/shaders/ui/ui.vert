#version 410 core

layout (location = 0) in vec2 aPosition;
layout (location = 1) in vec2 aUv;
layout (location = 2) in vec4 aTint;

uniform vec2 framebufferSize;

out vec2 vUv;
out vec4 vTint;

void main() {
    vec2 normalized = aPosition / framebufferSize;
    vec2 ndc = vec2(normalized.x * 2.0 - 1.0, 1.0 - normalized.y * 2.0);
    gl_Position = vec4(ndc, 0.0, 1.0);
    vUv = aUv;
    vTint = aTint;
}
