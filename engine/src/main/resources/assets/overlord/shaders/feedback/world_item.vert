#version 410 core
layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec2 aUv;
layout (location = 2) in float aFaceIndex;
uniform mat4 projection;
uniform mat4 view;
uniform mat4 model;
out vec2 localUv;
flat out int faceIndex;
void main() {
    gl_Position = projection * view * model * vec4(aPosition, 1.0);
    localUv = aUv;
    faceIndex = int(aFaceIndex);
}
