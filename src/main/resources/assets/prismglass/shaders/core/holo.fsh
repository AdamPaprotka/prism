#version 330

// Hologram quad: just samples the captured ClickGUI texture, tinted/faded by ColorModulator.
uniform sampler2D Sampler0;

in vec2 uv;
in vec4 colorMod;

out vec4 fragColor;

void main() {
    vec4 c = texture(Sampler0, uv);
    if (c.a <= 0.0) discard;
    fragColor = c * colorMod;
}
