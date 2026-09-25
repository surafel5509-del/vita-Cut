// Combined color-grade pass: brightness, exposure, contrast, saturation, vibrance,
// temperature, tint, highlights, shadows, whites, blacks, fade, sharpen, clarity,
// vignette and film grain. One draw, all parameters time-varying via uniforms.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;

uniform vec2 uTexelSize;        // 1/width, 1/height
uniform float uTimeSec;         // for grain animation
uniform float uBrightness;      // -1..1
uniform float uExposure;        // -1..1 (~ +-2 stops)
uniform float uContrast;        // -1..1
uniform float uSaturation;      // -1..1
uniform float uVibrance;        // -1..1
uniform float uTemperature;     // -1..1
uniform float uTint;            // -1..1
uniform float uHighlights;      // -1..1
uniform float uShadows;         // -1..1
uniform float uWhites;          // -1..1
uniform float uBlacks;          // -1..1
uniform float uFade;            // 0..1
uniform float uSharpen;         // 0..1
uniform float uClarity;         // -1..1
uniform float uVignette;        // 0..1
uniform float uGrain;           // 0..1

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float rand(vec2 co) {
    return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453);
}

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 c = color.rgb;

    // Exposure (stops) then brightness (additive).
    c *= pow(2.0, uExposure * 2.0);
    c += uBrightness * 0.5;

    // Temperature (warm shifts red up / blue down) and tint (green/magenta).
    c.r += uTemperature * 0.1;
    c.b -= uTemperature * 0.1;
    c.g += uTint * 0.08;

    // Contrast around mid-gray.
    c = (c - 0.5) * (1.0 + uContrast) + 0.5;

    // Blacks / whites point adjustments.
    c = c * (1.0 + uWhites * 0.5) + uBlacks * 0.25;

    // Shadows / highlights weighted by luminance.
    float luma = dot(c, LUMA);
    float shadowWeight = clamp(1.0 - luma * 2.0, 0.0, 1.0);
    float highlightWeight = clamp(luma * 2.0 - 1.0, 0.0, 1.0);
    c += uShadows * 0.35 * shadowWeight;
    c += uHighlights * 0.35 * highlightWeight;

    // Saturation.
    c = mix(vec3(luma), c, 1.0 + uSaturation);

    // Vibrance: boost desaturated pixels more than already-vivid ones.
    float maxC = max(c.r, max(c.g, c.b));
    float minC = min(c.r, min(c.g, c.b));
    float chroma = maxC - minC;
    float vibranceWeight = 1.0 - clamp(chroma * 2.0, 0.0, 1.0);
    float vibLuma = dot(c, LUMA);
    c = mix(vec3(vibLuma), c, 1.0 + uVibrance * vibranceWeight);

    // Fade: lift blacks, lower whites (filmic wash).
    c = mix(c, c * 0.85 + 0.12, uFade);

    // Sharpen (3x3 Laplacian unsharp mask).
    if (uSharpen > 0.001) {
        vec3 sum = texture2D(uTexSampler, vTexSamplingCoord + vec2(uTexelSize.x, 0.0)).rgb
                 + texture2D(uTexSampler, vTexSamplingCoord - vec2(uTexelSize.x, 0.0)).rgb
                 + texture2D(uTexSampler, vTexSamplingCoord + vec2(0.0, uTexelSize.y)).rgb
                 + texture2D(uTexSampler, vTexSamplingCoord - vec2(0.0, uTexelSize.y)).rgb;
        vec3 blur = sum * 0.25;
        c += (c - blur) * uSharpen * 1.5;
    }

    // Clarity: mid-tone local contrast using a wider box sample.
    if (abs(uClarity) > 0.001) {
        vec2 o = uTexelSize * 4.0;
        vec3 wide = texture2D(uTexSampler, vTexSamplingCoord + vec2(o.x, o.y)).rgb
                  + texture2D(uTexSampler, vTexSamplingCoord + vec2(o.x, -o.y)).rgb
                  + texture2D(uTexSampler, vTexSamplingCoord + vec2(-o.x, o.y)).rgb
                  + texture2D(uTexSampler, vTexSamplingCoord + vec2(-o.x, -o.y)).rgb;
        wide *= 0.25;
        float midWeight = 1.0 - abs(dot(c, LUMA) - 0.5) * 2.0;
        c += (c - wide) * uClarity * midWeight;
    }

    // Vignette.
    if (uVignette > 0.001) {
        vec2 d = vTexSamplingCoord - 0.5;
        float r = length(d) * 1.41421356;
        float vig = smoothstep(1.1, 0.35, r);
        c *= mix(1.0, vig, uVignette);
    }

    // Film grain (animated).
    if (uGrain > 0.001) {
        float g = rand(vTexSamplingCoord * 1024.0 + vec2(uTimeSec * 37.0, uTimeSec * 17.0));
        c += (g - 0.5) * uGrain * 0.35;
    }

    gl_FragColor = vec4(clamp(c, 0.0, 1.0), color.a);
}
