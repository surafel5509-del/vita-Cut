// Stylize family, selected by uMode:
//  0 pixelate  1 posterize  2 halftone  3 duotone  4 invert
//  5 thermal   6 night vision  7 edge glow  8 oil paint  9 bloom  10 neon glow
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform int uMode;
uniform float uIntensity;
uniform float uTimeSec;

float luma(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }

vec3 sampleRgb(vec2 uv) {
    return texture2D(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

void main() {
    vec2 uv = vTexSamplingCoord;
    vec4 src = texture2D(uTexSampler, uv);
    vec3 color = src.rgb;
    float t = clamp(uIntensity, 0.0, 1.0);

    if (uMode == 0) {
        float cells = mix(120.0, 12.0, t);
        vec2 grid = floor(uv * cells) / cells;
        color = mix(src.rgb, sampleRgb(grid), t);
    } else if (uMode == 1) {
        float levels = mix(16.0, 3.0, t);
        vec3 q = floor(src.rgb * levels + 0.5) / levels;
        color = mix(src.rgb, q, t);
    } else if (uMode == 2) {
        float dots = 48.0;
        vec2 cell = fract(uv * dots) - 0.5;
        float l = luma(src.rgb);
        float r = (1.0 - l) * 0.55 * t;
        float ink = smoothstep(r, r + 0.08, length(cell));
        color = mix(src.rgb, vec3(ink), t);
    } else if (uMode == 3) {
        vec3 shadow = vec3(0.05, 0.08, 0.28);
        vec3 highlight = vec3(1.0, 0.72, 0.28);
        color = mix(src.rgb, mix(shadow, highlight, luma(src.rgb)), t);
    } else if (uMode == 4) {
        color = mix(src.rgb, 1.0 - src.rgb, t);
    } else if (uMode == 5) {
        float l = luma(src.rgb);
        vec3 cold = vec3(0.05, 0.1, 0.85);
        vec3 hot = vec3(1.0, 0.15, 0.02);
        vec3 mid = vec3(0.95, 0.85, 0.1);
        vec3 mapped = mix(cold, mid, smoothstep(0.0, 0.5, l));
        mapped = mix(mapped, hot, smoothstep(0.5, 1.0, l));
        color = mix(src.rgb, mapped, t);
    } else if (uMode == 6) {
        float l = luma(src.rgb);
        vec3 nv = vec3(l * 0.2, l * 1.15, l * 0.25);
        float scan = 0.85 + 0.15 * sin(uv.y * 420.0 + uTimeSec * 8.0);
        color = mix(src.rgb, nv * scan, t);
    } else if (uMode == 7) {
        vec3 n = sampleRgb(uv + vec2(0.0, uTexelSize.y));
        vec3 s = sampleRgb(uv - vec2(0.0, uTexelSize.y));
        vec3 e = sampleRgb(uv + vec2(uTexelSize.x, 0.0));
        vec3 w = sampleRgb(uv - vec2(uTexelSize.x, 0.0));
        float edge = abs(luma(n) - luma(s)) + abs(luma(e) - luma(w));
        vec3 glow = src.rgb + vec3(0.55, 0.85, 1.0) * edge * 6.0 * t;
        color = mix(src.rgb, clamp(glow, 0.0, 1.0), t);
    } else if (uMode == 8) {
        vec3 acc = vec3(0.0);
        acc += sampleRgb(uv + vec2(-2.0, -1.0) * uTexelSize);
        acc += sampleRgb(uv + vec2(2.0, -1.0) * uTexelSize);
        acc += sampleRgb(uv + vec2(-1.0, 2.0) * uTexelSize);
        acc += sampleRgb(uv + vec2(1.0, 2.0) * uTexelSize);
        acc += src.rgb;
        vec3 smeared = acc / 5.0;
        float levels = mix(10.0, 4.0, t);
        vec3 paint = floor(smeared * levels + 0.5) / levels;
        color = mix(src.rgb, paint, t);
    } else if (uMode == 9) {
        vec3 acc = vec3(0.0);
        acc += sampleRgb(uv + vec2(-3.0, 0.0) * uTexelSize) * 0.15;
        acc += sampleRgb(uv + vec2(3.0, 0.0) * uTexelSize) * 0.15;
        acc += sampleRgb(uv + vec2(0.0, -3.0) * uTexelSize) * 0.15;
        acc += sampleRgb(uv + vec2(0.0, 3.0) * uTexelSize) * 0.15;
        acc += sampleRgb(uv + vec2(-2.0, -2.0) * uTexelSize) * 0.1;
        acc += sampleRgb(uv + vec2(2.0, 2.0) * uTexelSize) * 0.1;
        acc += src.rgb * 0.2;
        float bright = max(0.0, luma(acc) - 0.55);
        color = clamp(src.rgb + acc * bright * 1.8 * t, 0.0, 1.0);
    } else {
        // neon glow
        vec3 acc = vec3(0.0);
        acc += sampleRgb(uv + vec2(-2.0, 0.0) * uTexelSize);
        acc += sampleRgb(uv + vec2(2.0, 0.0) * uTexelSize);
        acc += sampleRgb(uv + vec2(0.0, -2.0) * uTexelSize);
        acc += sampleRgb(uv + vec2(0.0, 2.0) * uTexelSize);
        vec3 halo = acc * 0.25;
        vec3 neon = src.rgb * vec3(1.05, 0.85, 1.25) + halo * vec3(0.4, 0.9, 1.3);
        color = mix(src.rgb, clamp(neon, 0.0, 1.0), t);
    }

    gl_FragColor = vec4(color, src.a);
}
