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
out vec3 surfaceNormal;
out float faceLight;
out float ambientOcclusion;
out float viewDistance;
out vec3 fragmentWorldPosition;
void main() {
    vec4 worldPosition = model * vec4(aPosition, 1.0);
    vec4 viewPosition = view * worldPosition;
    gl_Position = projection * viewPosition;
    texCoord = aUv;
    surfaceNormal = mat3(model) * aNormal;
    float vertexLight = mod(aFaceLight, 16.0) / 15.0;
    faceLight = vertexLight;
    ambientOcclusion = aAmbientOcclusion;
    viewDistance = length(viewPosition.xyz);
    fragmentWorldPosition = worldPosition.xyz;
}
