#version 410 core
layout (location = 0) in vec3 aPosition;
layout (location = 1) in vec2 aUv;
layout (location = 2) in vec3 aNormal;
layout (location = 3) in float aFaceLight;
layout (location = 4) in float aAmbientOcclusion;
uniform mat4 projection;
uniform mat4 view;
uniform mat4 model;
out vec2 texCoord;
void main() {
    gl_Position =
        projection * view * model * vec4(aPosition, 1.0);
    texCoord = aUv;
}
