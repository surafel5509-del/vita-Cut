// Chroma key (green screen). Operates in YCbCr; alpha is derived from the distance to the key
// chroma with soft edges, plus spill suppression, edge shadow and optional solid background.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;

uniform vec3 uKeyColor;         // linear RGB of the key color
uniform float uSimilarity;      // intensity 0..1
uniform float uEdge;            // edge softness 0..1
uniform float uFeather;         // additional feather 0..1
uniform float uSpill;           // spill suppression 0..1
uniform float uShadow;          // edge darkening 0..1
uniform int uBgMode;            // 0 keep alpha, 1 solid color
uniform vec3 uBgColor;

vec3 rgb2ycbcr(vec3 c) {
    float y = dot(c, vec3(0.299, 0.587, 0.114));
    float cb = 0.5 + (-0.168736 * c.r - 0.331264 * c.g + 0.5 * c.b);
    float cr = 0.5 + (0.5 * c.r - 0.418688 * c.g - 0.081312 * c.b);
    return vec3(y, cb, cr);
}

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 ycc = rgb2ycbcr(color.rgb);
    vec3 keyYcc = rgb2ycbcr(uKeyColor);

    // Chroma distance (CbCr plane) drives the key.
    vec2 delta = ycc.yz - keyYcc.yz;
    float dist = length(delta);

    float similarity = mix(0.02, 0.45, uSimilarity);
    float edgeWidth = mix(0.005, 0.18, uEdge + uFeather);
    float alpha = smoothstep(similarity, similarity + edgeWidth, dist);

    // Spill suppression: pull chroma of semi-keyed pixels away from the key hue.
    vec3 despilled = color.rgb;
    float spillMask = (1.0 - alpha) * uSpill;
    float greenExcess = max(0.0, color.g - max(color.r, color.b));
    despilled.g -= greenExcess * spillMask;
    // Also nudge surviving pixels slightly.
    despilled.g -= greenExcess * 0.35 * uSpill * alpha;

    // Edge shadow: darken fringe pixels a touch to seat them over backgrounds.
    despilled *= mix(1.0, alpha, uShadow * 0.5);

    if (uBgMode == 1) {
        vec3 composited = mix(uBgColor, despilled, alpha);
        gl_FragColor = vec4(composited, 1.0);
    } else {
        gl_FragColor = vec4(despilled * alpha, alpha); // premultiplied for the compositor
    }
}
