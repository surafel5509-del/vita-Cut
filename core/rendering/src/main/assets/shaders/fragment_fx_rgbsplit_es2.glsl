// RGB split (chromatic aberration): channels sample at radial offsets from the frame center.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform float uIntensity;  // 0..1
uniform float uAngleDeg;   // split direction (0 = radial)
uniform float uTimeSec;    // allows pulsing via CPU-computed intensity; kept for jitter option

void main() {
    vec2 uv = vTexSamplingCoord;
    vec2 dir = uv - 0.5;
    float amount = uIntensity * 0.02;

    vec4 color;
    color.r = texture2D(uTexSampler, uv + dir * amount).r;
    color.g = texture2D(uTexSampler, uv).g;
    color.b = texture2D(uTexSampler, uv - dir * amount).b;
    color.a = texture2D(uTexSampler, uv).a;
    gl_FragColor = color;
}
