#version 330
#extension GL_ARB_separate_shader_objects : require

// Hologram quad: just samples the captured ClickGUI texture, tinted/faded by ColorModulator.
uniform sampler2D Sampler0;

layout(location = 0) in vec2 uv;
layout(location = 1) in vec4 colorMod;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 c = texture(Sampler0, uv);
    if (c.a <= 0.0) discard;
    fragColor = c * colorMod;
}
