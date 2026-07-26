#version 410 core
in vec2 texCoord;
in vec3 surfaceNormal;
in float faceLight;
in float ambientOcclusion;
in float viewDistance;
out vec4 fragmentColor;
uniform sampler2D textureAtlas;
uniform vec3 sunDirection;
uniform float ambientStrength;
uniform float directionalStrength;
uniform vec3 fogColor;
uniform float fogStart;
uniform float fogEnd;

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
    vec4 sampledColor = texture(textureAtlas, texCoord);
    vec3 linearColor = srgbToLinear(sampledColor.rgb);
    float combinedLight = clamp(
        ambientStrength
            + directionalStrength
                * max(dot(normalize(surfaceNormal), sunDirection), 0.0),
        0.0,
        1.0);
    vec3 litColor =
        linearColor * combinedLight * faceLight * ambientOcclusion;
    float fogAmount = smoothstep(fogStart, fogEnd, viewDistance);
    vec3 foggedColor = mix(litColor, fogColor, fogAmount);
    fragmentColor = vec4(linearToSrgb(foggedColor), sampledColor.a);
}
