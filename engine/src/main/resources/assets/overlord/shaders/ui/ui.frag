#version 410 core

in vec2 vUv;
in vec4 vTint;

uniform sampler2D uiTexture;
uniform int textureSamplingEnabled;

layout (location = 0) out vec4 fragColor;

vec3 srgbToLinear(vec3 value) {
    bvec3 cutoff = lessThanEqual(value, vec3(0.04045));
    vec3 lower = value / 12.92;
    vec3 upper = pow((value + 0.055) / 1.055, vec3(2.4));
    return mix(upper, lower, cutoff);
}

vec3 linearToSrgb(vec3 value) {
    bvec3 cutoff = lessThanEqual(value, vec3(0.0031308));
    vec3 lower = value * 12.92;
    vec3 upper = 1.055 * pow(max(value, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(upper, lower, cutoff);
}

void main() {
    vec4 sampled = textureSamplingEnabled != 0 ? texture(uiTexture, vUv) : vec4(1.0);
    vec3 linearRgb = srgbToLinear(sampled.rgb) * srgbToLinear(vTint.rgb);
    fragColor = vec4(linearToSrgb(linearRgb), sampled.a * vTint.a);
}
