// Per-band HSL adjustments (8 hue bands: red, orange, yellow, green, cyan, blue, purple, magenta).
// Each band gets hue rotation, saturation and luminance deltas. Band influence is a smooth
// triangular window over the hue circle so adjustments never produce hard edges.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;

uniform float uHue0; uniform float uSat0; uniform float uLum0; // red
uniform float uHue1; uniform float uSat1; uniform float uLum1; // orange
uniform float uHue2; uniform float uSat2; uniform float uLum2; // yellow
uniform float uHue3; uniform float uSat3; uniform float uLum3; // green
uniform float uHue4; uniform float uSat4; uniform float uLum4; // cyan
uniform float uHue5; uniform float uSat5; uniform float uLum5; // blue
uniform float uHue6; uniform float uSat6; uniform float uLum6; // purple
uniform float uHue7; uniform float uSat7; uniform float uLum7; // magenta

vec3 rgb2hsl(vec3 c) {
    float maxC = max(c.r, max(c.g, c.b));
    float minC = min(c.r, min(c.g, c.b));
    float l = (maxC + minC) * 0.5;
    float d = maxC - minC;
    float h = 0.0;
    float s = 0.0;
    if (d > 0.00001) {
        s = d / (1.0 - abs(2.0 * l - 1.0) + 0.00001);
        if (maxC == c.r) {
            h = mod((c.g - c.b) / d, 6.0);
        } else if (maxC == c.g) {
            h = (c.b - c.r) / d + 2.0;
        } else {
            h = (c.r - c.g) / d + 4.0;
        }
        h /= 6.0;
    }
    return vec3(h, s, l);
}

float hueComponent(float p, float q, float t) {
    if (t < 0.0) t += 1.0;
    if (t > 1.0) t -= 1.0;
    if (t < 1.0 / 6.0) return p + (q - p) * 6.0 * t;
    if (t < 1.0 / 2.0) return q;
    if (t < 2.0 / 3.0) return p + (q - p) * (2.0 / 3.0 - t) * 6.0;
    return p;
}

vec3 hsl2rgb(vec3 hsl) {
    float h = hsl.x; float s = hsl.y; float l = hsl.z;
    if (s < 0.00001) return vec3(l);
    float q = l < 0.5 ? l * (1.0 + s) : l + s - l * s;
    float p = 2.0 * l - q;
    return vec3(
        hueComponent(p, q, h + 1.0 / 3.0),
        hueComponent(p, q, h),
        hueComponent(p, q, h - 1.0 / 3.0)
    );
}

// Triangular weight of hue h (0..1) around band center (0..1), width covers 2 of 8 segments.
float bandWeight(float h, float center) {
    float d = abs(h - center);
    d = min(d, 1.0 - d); // wrap around the hue circle
    return clamp(1.0 - d * 4.0, 0.0, 1.0);
}

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 hsl = rgb2hsl(color.rgb);

    float w0 = bandWeight(hsl.x, 0.000); // red
    float w1 = bandWeight(hsl.x, 0.083); // orange
    float w2 = bandWeight(hsl.x, 0.167); // yellow
    float w3 = bandWeight(hsl.x, 0.333); // green
    float w4 = bandWeight(hsl.x, 0.500); // cyan
    float w5 = bandWeight(hsl.x, 0.667); // blue
    float w6 = bandWeight(hsl.x, 0.750); // purple
    float w7 = bandWeight(hsl.x, 0.833); // magenta

    hsl.x = fract(hsl.x
        + uHue0 * w0 + uHue1 * w1 + uHue2 * w2 + uHue3 * w3
        + uHue4 * w4 + uHue5 * w5 + uHue6 * w6 + uHue7 * w7);
    hsl.y = clamp(hsl.y
        + uSat0 * w0 + uSat1 * w1 + uSat2 * w2 + uSat3 * w3
        + uSat4 * w4 + uSat5 * w5 + uSat6 * w6 + uSat7 * w7, 0.0, 1.0);
    hsl.z = clamp(hsl.z
        + uLum0 * w0 + uLum1 * w1 + uLum2 * w2 + uLum3 * w3
        + uLum4 * w4 + uLum5 * w5 + uLum6 * w6 + uLum7 * w7, 0.0, 1.0);

    gl_FragColor = vec4(hsl2rgb(hsl), color.a);
}
