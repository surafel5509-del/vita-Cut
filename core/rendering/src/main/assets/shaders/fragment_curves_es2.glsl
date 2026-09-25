// Tone curves applied through a baked 256x4 LUT texture:
// row 0 = RGB master, row 1 = red, row 2 = green, row 3 = blue.
// The LUT is generated on the CPU from the project's CurvePoints (see CurvesLutBaker).
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform sampler2D uCurveLut;
uniform float uIntensity; // 0..1, keyframable

float applyCurve(float channel, float row) {
    // Sample the LUT row; +0.5/256.0 centers texels, /4.0 selects the row band.
    vec2 uv = vec2(clamp(channel, 0.0, 1.0) * (255.0 / 256.0) + 0.5 / 256.0,
                   (row + 0.5) / 4.0);
    return texture2D(uCurveLut, uv).r;
}

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);

    float r = applyCurve(applyCurve(color.r, 0.0), 1.0);
    float g = applyCurve(applyCurve(color.g, 0.0), 2.0);
    float b = applyCurve(applyCurve(color.b, 0.0), 3.0);

    gl_FragColor = vec4(mix(color.rgb, vec3(r, g, b), uIntensity), color.a);
}
