#version 410 core
in float skyInterpolation;
out vec4 fragmentColor;
uniform vec3 skyHorizon;
uniform vec3 skyTop;

vec3 linearToSrgb(vec3 linear) {
    bvec3 useLinearSegment = lessThanEqual(linear, vec3(0.0031308));
    vec3 linearSegment = linear * 12.92;
    vec3 exponentialSegment =
        1.055 * pow(linear, vec3(1.0 / 2.4)) - vec3(0.055);
    return mix(exponentialSegment, linearSegment, useLinearSegment);
}

void main() {
    vec3 linearSky = mix(skyHorizon, skyTop,
        clamp(skyInterpolation, 0.0, 1.0));
    fragmentColor = vec4(linearToSrgb(linearSky), 1.0);
}
