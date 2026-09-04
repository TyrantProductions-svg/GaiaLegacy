#version 410 core

layout(location = 0) in vec3 position;
layout(location = 1) in vec3 normal;
layout(location = 2) in vec2 texCoord;

uniform mat4 projection;
uniform mat4 modelView;

out vec3 viewPosition;
out vec3 viewNormal;
out vec2 uv0;

void main() {
    vec4 view = modelView * vec4(position, 1.0);
    viewPosition = view.xyz;
    viewNormal = transpose(inverse(mat3(modelView))) * normal;
    uv0 = texCoord;
    gl_Position = projection * view;
}
