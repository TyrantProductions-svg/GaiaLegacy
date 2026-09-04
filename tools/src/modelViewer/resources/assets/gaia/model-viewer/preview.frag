#version 410 core

in vec3 viewPosition;
in vec3 viewNormal;
in vec2 uv0;

uniform sampler2D baseColorTexture;
uniform vec3 baseColorFactor;
uniform vec3 lineColor;
uniform int textured;
uniform int unlit;

layout(location = 0) out vec4 outputColor;

vec3 linearToSrgb(vec3 value) {
    vec3 low = value * 12.92;
    vec3 high = 1.055 * pow(max(value, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, lessThanEqual(value, vec3(0.0031308)));
}

void main() {
    if (unlit != 0) {
        outputColor = vec4(lineColor, 1.0);
        return;
    }
    vec3 albedo = baseColorFactor;
    if (textured != 0) albedo *= texture(baseColorTexture, uv0).rgb;
    vec3 n = normalize(viewNormal);
    vec3 light = normalize(vec3(0.35, 0.75, 0.55));
    float diffuse = max(dot(n, light), 0.0);
    float hemisphere = n.y * 0.18 + 0.34;
    vec3 linear = albedo * clamp(0.30 + diffuse * 0.58 + hemisphere, 0.18, 1.0);
    outputColor = vec4(linearToSrgb(clamp(linear, 0.0, 1.0)), 1.0);
}
