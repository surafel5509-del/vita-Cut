// Clip placement pass — the last program in each clip's chain. Maps the (already graded/effected)
// source frame onto the output canvas, fusing: user crop, content-fit, spatial transform
// (translate/scale/rotate/flip), opacity, and canvas background painting for uncovered areas.
// All spatial parameters are CPU-computed so preview and export share identical math.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;

uniform vec2 uUvScale;       // visible source uv extent across the whole canvas
uniform vec2 uCropCenter;    // crop window center in source uv (0..1)
uniform vec2 uCropHalf;      // crop window half-size in source uv
uniform vec2 uTranslation;   // NDC offset (-1..1)
uniform vec2 uScaleAbs;      // transform scale magnitude (>0)
uniform vec2 uFlip;          // per-axis +1 / -1 (flip horizontal / vertical)
uniform float uRotationRad;
uniform float uOpacity;      // 0..1
uniform int uBgMode;         // 0 black, 1 solid color, 2 gradient, 3 blur-fill
uniform vec3 uBgColor0;
uniform vec3 uBgColor1;

vec3 blurWide(vec2 uv) {
    vec3 sum = vec3(0.0);
    float r = 0.03;
    sum += texture2D(uTexSampler, clamp(uv + vec2(-r, -r), vec2(0.0), vec2(1.0))).rgb;
    sum += texture2D(uTexSampler, clamp(uv + vec2(r, -r), vec2(0.0), vec2(1.0))).rgb;
    sum += texture2D(uTexSampler, clamp(uv + vec2(-r, r), vec2(0.0), vec2(1.0))).rgb;
    sum += texture2D(uTexSampler, clamp(uv + vec2(r, r), vec2(0.0), vec2(1.0))).rgb;
    sum += texture2D(uTexSampler, clamp(uv + vec2(-r * 2.0, 0.0), vec2(0.0), vec2(1.0))).rgb;
    sum += texture2D(uTexSampler, clamp(uv + vec2(r * 2.0, 0.0), vec2(0.0), vec2(1.0))).rgb;
    sum += texture2D(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
    return sum / 7.0;
}

vec3 paintBackground(vec2 uv, vec2 fallbackUv) {
    if (uBgMode == 1) return uBgColor0;
    if (uBgMode == 2) return mix(uBgColor0, uBgColor1, uv.y);
    if (uBgMode == 3) return blurWide(clamp(fallbackUv, vec2(0.0), vec2(1.0))) * 1.08;
    return vec3(0.0);
}

void main() {
    vec2 outUv = vTexSamplingCoord;

    // Output-space NDC, apply layer translation, rotation and scale (inverse mapping).
    vec2 p = (outUv - 0.5) * 2.0;
    p -= uTranslation;
    float s = sin(uRotationRad);
    float c = cos(uRotationRad);
    p = vec2(p.x * c + p.y * s, -p.x * s + p.y * c);
    p /= max(uScaleAbs, vec2(0.00001));
    p *= uFlip;

    // Map to source uv through the fit window and crop region.
    vec2 sourceUv = uCropCenter + (p * 0.5) * uUvScale;

    vec2 cropMin = uCropCenter - uCropHalf;
    vec2 cropMax = uCropCenter + uCropHalf;
    bool inside = sourceUv.x >= cropMin.x && sourceUv.x <= cropMax.x &&
                  sourceUv.y >= cropMin.y && sourceUv.y <= cropMax.y;

    vec4 content;
    if (inside) {
        content = texture2D(uTexSampler, clamp(sourceUv, vec2(0.0), vec2(1.0)));
    } else {
        content = vec4(0.0);
    }

    vec3 background = paintBackground(outUv, clamp(sourceUv, cropMin, cropMax));
    vec3 composited = mix(background, content.rgb, content.a * uOpacity * (inside ? 1.0 : 0.0));

    gl_FragColor = vec4(composited, 1.0);
}
