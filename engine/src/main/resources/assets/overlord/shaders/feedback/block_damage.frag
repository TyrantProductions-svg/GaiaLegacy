#version 410 core
in vec2 localUv;
out vec4 fragmentColor;
uniform sampler2D damageAtlas;
uniform float uMin;
uniform float uMax;
uniform float vMin;
uniform float vMax;
void main() {
    vec2 atlasUv = mix(vec2(uMin, vMin), vec2(uMax, vMax), localUv);
    vec4 sampled = texture(damageAtlas, atlasUv);
    if (sampled.a < 0.1) {
        discard;
    }
    fragmentColor = vec4(sampled.rgb, 1.0);
}
