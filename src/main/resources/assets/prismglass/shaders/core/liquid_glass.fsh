#version 330

// Liquid glass: the panel is a thick convex lens over a snapshot of the world behind it.
//  - refraction: background is pulled inward near the rim, so edges magnify and bend the scene
//  - dispersion: R and B are refracted slightly differently at the rim (rainbow fringe)
//  - light blur + saturation lift: the scene stays clear and vivid, not frosted grey
//  - specular rim lit from the top-left, softer counter-light bottom-right, thin inner shade
// Panel parameters arrive per vertex (see Glass.LiquidPanel), so differently styled panels batch together.

uniform sampler2D Sampler0;

in vec4 tintOpacity;
in vec2 localPx;
flat in vec2 sizePx;
flat in vec4 params;
flat in vec3 params2;

out vec4 fragColor;

float sdRoundRect(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2 screen = vec2(textureSize(Sampler0, 0));
    vec2 frag = gl_FragCoord.xy;
    vec2 local = localPx;
    float cell = params2.z;
    if (cell > 0.0) {
        // Blocky: evaluate the whole panel once per cell (the panel origin sits on the block grid)
        vec2 q = (floor(local / cell) + 0.5) * cell;
        frag += vec2(q.x - local.x, local.y - q.y);
        local = q;
    }
    vec2 halfSize = sizePx * 0.5;
    // local position with y up (to match gl_FragCoord / the snapshot texture)
    vec2 p = vec2(local.x - halfSize.x, (sizePx.y - local.y) - halfSize.y);
    float r = min(params.x, min(halfSize.x, halfSize.y));
    float d = sdRoundRect(p, halfSize, r);

    float coverage = cell > 0.0 ? step(d, 0.0) : 1.0 - smoothstep(-0.75, 0.75, d);
    if (coverage <= 0.0) discard;

    vec2 e = vec2(1.0, 0.0);
    vec2 n = vec2(sdRoundRect(p + e.xy, halfSize, r) - sdRoundRect(p - e.xy, halfSize, r),
                  sdRoundRect(p + e.yx, halfSize, r) - sdRoundRect(p - e.yx, halfSize, r));
    n = n / max(length(n), 1e-4);

    float refraction = params.y;
    float bevel = max(refraction * 1.6, 2.0);
    float edge = clamp(1.0 + d / bevel, 0.0, 1.0);
    float lens = edge * edge * edge;
    vec2 uv = (frag - n * lens * refraction) / screen;
    vec2 disp = n * lens * params.w * 3.0 / screen;

    vec3 col = vec3(0.0);
    float blur = params2.x;
    for (int i = 0; i < 12; i++) {
        float a = float(i) * 0.5235988;
        vec2 o = vec2(cos(a), sin(a)) * blur / screen;
        col.r += texture(Sampler0, uv + o + disp).r;
        col.g += texture(Sampler0, uv + o).g;
        col.b += texture(Sampler0, uv + o - disp).b;
    }
    col /= 12.0;

    float luma = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(vec3(luma), col, 1.22);
    col = col * 1.04 + 0.025;
    col = mix(col, tintOpacity.rgb, params.z);

    float t = clamp((p.y + halfSize.y) / max(sizePx.y, 1.0), 0.0, 1.0);
    col += vec3(0.07) * smoothstep(0.45, 1.0, t);

    vec2 light = normalize(vec2(-0.55, 0.83));
    float rim = cell > 0.0 ? step(-cell, d) : smoothstep(-2.6, -0.4, d);
    float spec = pow(max(dot(n, light), 0.0), 2.5) + 0.5 * pow(max(dot(n, -light), 0.0), 3.0);
    col += vec3(1.0) * rim * (0.18 + 0.85 * spec) * params2.y;

    float inner = cell > 0.0 ? step(-2.0 * cell, d) * step(d, -cell) : smoothstep(-7.0, -2.0, d) * (1.0 - smoothstep(-2.0, -0.5, d));
    col *= 1.0 - 0.12 * inner * (1.0 - spec);

    if (cell > 0.0) col = floor(col * 16.0 + 0.5) / 16.0; // pixel-art palette
    fragColor = vec4(clamp(col, 0.0, 1.0), coverage * min(1.0, tintOpacity.a * 2.2));
}
