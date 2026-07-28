#version 410 core
in vec2 localUv;
out vec4 fragmentColor;
uniform sampler2D blockAtlas;
uniform float uMin;
uniform float uMax;
uniform float vMin;
uniform float vMax;

vec3 srgbToLinear(vec3 srgb) {
    bvec3 useLinearSegment = lessThanEqual(srgb, vec3(0.04045));
    vec3 linearSegment = srgb / 12.92;
    vec3 exponentialSegment =
        pow((srgb + vec3(0.055)) / 1.055, vec3(2.4));
    return mix(exponentialSegment, linearSegment, useLinearSegment);
}

vec3 linearToSrgb(vec3 linear) {
    bvec3 useLinearSegment = lessThanEqual(linear, vec3(0.0031308));
    vec3 linearSegment = linear * 12.92;
    vec3 exponentialSegment =
        1.055 * pow(linear, vec3(1.0 / 2.4)) - vec3(0.055);
    return mix(exponentialSegment, linearSegment, useLinearSegment);
}

void main() {
    vec2 atlasUv = mix(vec2(uMin, vMin), vec2(uMax, vMax), localUv);
    vec4 sampled = texture(blockAtlas, atlasUv);
    if (sampled.a < 0.1) {
        discard;
    }
    vec3 linearColor = srgbToLinear(sampled.rgb);
    vec3 encodedColor = linearToSrgb(linearColor);
    fragmentColor = vec4(encodedColor, sampled.a);
}
