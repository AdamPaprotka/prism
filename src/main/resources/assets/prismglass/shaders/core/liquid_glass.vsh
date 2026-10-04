#version 330

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

in vec3 Position;
in vec4 Color;      // tint rgb, opacity
in vec2 UV0;        // position inside the panel, framebuffer pixels from top-left
in ivec2 UV1;       // panel size in pixels
in ivec2 UV2;       // x: radius*4 + 256*tintStrength%, y: refraction*4 (low 10 bits) + 1024*blockPx*2
in vec3 Normal;     // dispersion/4, blur/32, rim/2 (0..1)

out vec4 tintOpacity;
out vec2 localPx;
flat out vec2 sizePx;
flat out vec4 params;   // radius, refraction, tintStrength, dispersion
flat out vec3 params2;  // blur, rim, Blocky cell size in pixels (0 = smooth)

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
