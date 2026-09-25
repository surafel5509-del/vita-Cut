// Camera shake: offsets sampling coordinates with layered noise driven by time.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform float uTimeSec;
uniform float uIntensity;

float noise(float seed) {
    return fract(sin(seed * 12.9898) * 78.233) * 2.0 - 1.0;
}

void main() {
    float t = uTimeSec * 24.0; // shake frequency
    float dx = noise(floor(t)) * 0.5 + noise(floor(t * 0.37)) * 0.5;
    float dy = noise(floor(t) + 91.7) * 0.5 + noise(floor(t * 0.41) + 33.3) * 0.5;
    float rot = noise(floor(t * 0.9) + 71.3) * 0.01;

    vec2 uv = vTexSamplingCoord - 0.5;
    float s = sin(rot * uIntensity);
    float c = cos(rot * uIntensity);
    uv = vec2(uv.x * c - uv.y * s, uv.x * s + uv.y * c);
    uv += 0.5 + vec2(dx, dy) * 0.03 * uIntensity;

    // Clamp inside the frame with a slight zoom so edges don't show.
    uv = clamp((uv - 0.5) * (1.0 - uIntensity * 0.06) + 0.5, vec2(0.001), vec2(0.999));
    gl_FragColor = texture2D(uTexSampler, uv);
}
