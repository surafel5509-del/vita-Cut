// Masking pass. uShape: 0 none(passthrough), 1 rectangle, 2 circle, 3 linear, 4 radial,
// 5 texture (CPU-baked freeform mask sampled from uMaskTex).
// Mask alpha multiplies the frame alpha; feather softens edges; invert flips the region.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform sampler2D uMaskTex;

uniform int uShape;
uniform vec2 uCenter;      // NDC-ish 0..1 in frame space
uniform vec2 uScale;       // mask size relative to frame (1 = full)
uniform float uRotationRad;
uniform float uFeather;    // 0..1
uniform float uInvert;     // 0 or 1
uniform float uAspect;     // frame width/height

float maskAlphaRect(vec2 p, vec2 halfSize, float feather) {
    vec2 d = abs(p) - halfSize + vec2(feather);
    float outside = length(max(d, 0.0));
    float inside = min(max(d.x, d.y), 0.0);
    float dist = outside + inside;               // signed distance to the rect
    return 1.0 - smoothstep(-feather, feather, dist);
}

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    if (uShape == 0) {
        gl_FragColor = color;
        return;
    }

    vec2 uv = vTexSamplingCoord;
    if (uShape == 5) {
        float m = texture2D(uMaskTex, uv).a;
        m = mix(m, 1.0 - m, uInvert);
        gl_FragColor = vec4(color.rgb, color.a * m);
        return;
    }

    // Move into mask-local space: origin at mask center, aspect-corrected, rotated.
    vec2 p = uv - uCenter;
    p.x *= uAspect;
    float s = sin(uRotationRad);
    float c = cos(uRotationRad);
    p = vec2(p.x * c + p.y * s, -p.x * s + p.y * c);

    float feather = max(uFeather * 0.25, 0.0001);
    float alpha;

    if (uShape == 1) {
        alpha = maskAlphaRect(p, uScale * vec2(uAspect, 1.0) * 0.5, feather);
    } else if (uShape == 2) {
        vec2 halfSize = uScale * vec2(uAspect, 1.0) * 0.5;
        float r = length(p / halfSize);
        alpha = 1.0 - smoothstep(1.0 - feather * 2.0, 1.0 + feather * 2.0, r);
    } else if (uShape == 3) {
        // Linear: gradient along local x across the mask width.
        float edge = (p.x / max(uScale.x * uAspect, 0.0001)) + 0.5;
        alpha = smoothstep(0.5 - feather, 0.5 + feather, edge);
    } else {
        // Radial: soft circle from center outward, feather controls falloff width.
        float r = length(p / max(uScale * vec2(uAspect, 1.0) * 0.5, vec2(0.0001)));
        alpha = 1.0 - smoothstep(max(1.0 - feather * 2.0, 0.0), 1.0, r);
    }

    alpha = mix(alpha, 1.0 - alpha, uInvert);
    gl_FragColor = vec4(color.rgb, color.a * alpha);
}
