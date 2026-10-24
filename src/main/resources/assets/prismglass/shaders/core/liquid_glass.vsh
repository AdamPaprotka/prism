#version 330
#extension GL_ARB_separate_shader_objects : require

// 26.3 (renderpearl): shaders go through SPIR-V, so every in/out needs an explicit location; the uniform blocks are
// bound by name (same layout as minecraft:dynamictransforms.glsl / projection.glsl).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    mat4 TextureMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

layout(location = 0) in vec3 Position;
layout(location = 1) in vec4 Color;      // tint rgb, opacity
layout(location = 2) in vec2 UV0;        // position inside the panel, framebuffer pixels from top-left
layout(location = 3) in ivec2 UV1;       // panel size in pixels
layout(location = 4) in ivec2 UV2;       // x: radius*4 + 256*tintStrength%, y: refraction*4 (low 10 bits) + 1024*blockPx*2
layout(location = 5) in vec3 Normal;     // dispersion/4, blur/32, rim/2 (0..1)

layout(location = 0) out vec4 tintOpacity;
layout(location = 1) out vec2 localPx;
layout(location = 2) flat out vec2 sizePx;
layout(location = 3) flat out vec4 params;   // radius, refraction, tintStrength, dispersion
layout(location = 4) flat out vec3 params2;  // blur, rim, Blocky cell size in pixels (0 = smooth)

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    tintOpacity = Color;
    localPx = UV0;
    sizePx = vec2(UV1);
    float radius = float(UV2.x % 256) / 4.0;
    float tintStrength = float(UV2.x / 256) / 100.0;
    params = vec4(radius, float(UV2.y % 1024) / 4.0, tintStrength, Normal.x * 4.0);
    params2 = vec3(Normal.y * 32.0, Normal.z * 2.0, float(UV2.y / 1024) / 2.0);
}
