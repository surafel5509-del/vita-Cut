// 3D LUT stored as a 2D horizontal strip of NxN slices (standard .cube layout, N = 32/64).
// Trilinear interpolation between blue slices. uLutSize = N.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform sampler2D uLutTexture;
uniform float uLutSize;
uniform float uIntensity;

vec3 applyLut(vec3 color) {
    float n = uLutSize;
    float scale = (n - 1.0) / n;
    float offset = 0.5 / n;

    vec3 c = clamp(color, 0.0, 1.0) * scale + offset;

    float sliceWidth = 1.0 / n;               // one blue slice occupies 1/n of the strip width
    float bluePos = c.b * (n - 1.0);
    float slice0 = floor(bluePos);
    float slice1 = min(slice0 + 1.0, n - 1.0);
    float fracB = bluePos - slice0;

    vec2 uv0 = vec2((slice0 + c.r) * sliceWidth, 1.0 - c.g);
    vec2 uv1 = vec2((slice1 + c.r) * sliceWidth, 1.0 - c.g);

    vec3 lut0 = texture2D(uLutTexture, uv0).rgb;
    vec3 lut1 = texture2D(uLutTexture, uv1).rgb;
    return mix(lut0, lut1, fracB);
}

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    vec3 graded = applyLut(color.rgb);
    gl_FragColor = vec4(mix(color.rgb, graded, uIntensity), color.a);
}
