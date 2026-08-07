#version 410 core
in vec2 localUv;
flat in int faceIndex;
out vec4 fragmentColor;
uniform sampler2D blockAtlas;
uniform float uMin[6];
uniform float uMax[6];
uniform float vMin[6];
uniform float vMax[6];
uniform float visualAlpha;

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
    vec2 atlasUv = mix(
        vec2(uMin[faceIndex], vMin[faceIndex]),
        vec2(uMax[faceIndex], vMax[faceIndex]),
        localUv);
    vec4 sampled = texture(blockAtlas, atlasUv);
    if (sampled.a < 0.1) {
        discard;
    }
    vec3 linearColor = srgbToLinear(sampled.rgb);
    vec3 encodedColor = linearToSrgb(linearColor);
    fragmentColor = vec4(encodedColor, sampled.a * visualAlpha);
}
