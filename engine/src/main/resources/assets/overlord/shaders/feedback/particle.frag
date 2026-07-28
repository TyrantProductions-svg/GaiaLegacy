#version 410 core
in vec2 texCoord;
out vec4 fragmentColor;
uniform sampler2D blockAtlas;
void main() {
    vec4 sampled = texture(blockAtlas, texCoord);
    if (sampled.a < 0.1) {
        discard;
    }
    fragmentColor = sampled;
}
