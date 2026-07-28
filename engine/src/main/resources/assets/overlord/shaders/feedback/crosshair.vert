#version 410 core

layout(location = 0) in vec2 pixelPosition;

uniform vec2 framebufferSize;

void main() {
    vec2 ndc = pixelPosition / framebufferSize * 2.0 - 1.0;
    gl_Position = vec4(ndc, 0.0, 1.0);
}
