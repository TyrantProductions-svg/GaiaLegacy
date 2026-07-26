#version 410 core
out float skyInterpolation;

const vec2 FULLSCREEN_POSITIONS[3] = vec2[](
    vec2(-1.0, -1.0),
    vec2(3.0, -1.0),
    vec2(-1.0, 3.0)
);

void main() {
    vec2 position = FULLSCREEN_POSITIONS[gl_VertexID];
    skyInterpolation = position.y * 0.5 + 0.5;
    gl_Position = vec4(position, 0.0, 1.0);
}
