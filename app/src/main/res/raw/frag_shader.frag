//#version 120
precision mediump float;

varying vec2 vTextureCoord;

uniform sampler2D samplerY;
uniform sampler2D samplerU;
uniform sampler2D samplerV;

void main() {
    float y = texture2D(samplerY, vTextureCoord).r;
    float u = texture2D(samplerU, vTextureCoord).r - 0.5;
    float v = texture2D(samplerV, vTextureCoord).r - 0.5;

    float r = y + 1.402 * v;
    float g = y - 0.344136 * u - 0.714136 * v;
    float b = y + 1.772 * u;

    gl_FragColor = vec4(r, g, b, 1.0);
}