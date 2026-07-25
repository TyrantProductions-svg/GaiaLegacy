#version 410 core
in vec2 texCoord;
out vec4 fragmentColor;
uniform sampler2D textureAtlas;
void main() {
    fragmentColor = texture(textureAtlas, texCoord);
}
