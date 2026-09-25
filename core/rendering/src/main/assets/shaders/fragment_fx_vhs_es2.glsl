// VHS look: tracking wobble, chroma bleed, tape noise bands, scanline shimmer and soft edges.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform float uTimeSec;
uniform float uIntensity;

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

void main() {
    vec2 uv = vTexSamplingCoord;

    // Horizontal tracking wobble that drifts over time.
    float wobble = sin(uv.y * 60.0 + uTimeSec * 2.0) * 0.002
                 + sin(uv.y * 13.0 - uTimeSec * 0.7) * 0.004;
    uv.x += wobble * uIntensity;

    // Random tracking band sliding vertically.
    float bandPos = fract(uTimeSec * 0.08);
    float band = smoothstep(0.0, 0.02, abs(uv.y - bandPos) ) ;
    float bandNoise = (1.0 - band) * uIntensity;
    uv.x += bandNoise * 0.06 * (hash(vec2(floor(uv.y * 200.0), floor(uTimeSec * 10.0))) - 0.5);

    // Chroma bleed: red/blue sample offset horizontally.
    vec4 color;
    color.r = texture2D(uTexSampler, uv + vec2(0.004 * uIntensity, 0.0)).r;
    color.g = texture2D(uTexSampler, uv).g;
    color.b = texture2D(uTexSampler, uv - vec2(0.004 * uIntensity, 0.0)).b;
    color.a = 1.0;

    // Tape noise speckle.
    float n = hash(uv * 512.0 + vec2(uTimeSec * 91.0, uTimeSec * 53.0));
    color.rgb += (n - 0.5) * 0.12 * uIntensity;

    // Scanline shimmer.
    float scan = sin(uv.y * 800.0) * 0.02 * uIntensity;
    color.rgb -= scan;

    // Slight desaturation + contrast lift typical of tape.
    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    color.rgb = mix(color.rgb, vec3(luma), 0.15 * uIntensity);

    // Bright band artifact at the bottom edge.
    float bottom = smoothstep(0.02, 0.0, uv.y) * uIntensity;
    color.rgb = mix(color.rgb, vec3(0.9, 0.9, 1.0), bottom * 0.7);

    gl_FragColor = color;
}
