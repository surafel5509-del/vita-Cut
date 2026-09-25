// Digital glitch: horizontal slice displacement, occasional channel tearing and block noise.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform float uTimeSec;
uniform float uIntensity; // 0..1

float hash(float n) {
    return fract(sin(n) * 43758.5453123);
}

void main() {
    vec2 uv = vTexSamplingCoord;

    float t = floor(uTimeSec * 12.0); // glitch steps at ~12Hz
    float rowJitter = hash(floor(uv.y * 24.0) + t * 7.0);
    float glitchActive = step(1.0 - uIntensity * 0.6, hash(t * 3.7));

    float shift = (rowJitter - 0.5) * 0.12 * uIntensity * glitchActive;
    uv.x = fract(uv.x + shift);

    // Slice vertical wobble.
    float slice = hash(floor(uv.y * 48.0) + t);
    uv.x += (slice - 0.5) * 0.02 * uIntensity * glitchActive;

    vec4 color = texture2D(uTexSampler, uv);

    // Channel tear during strong glitch moments.
    float tear = step(0.85, hash(t * 11.0)) * uIntensity;
    color.r = mix(color.r, texture2D(uTexSampler, fract(uv + vec2(0.01 * tear, 0.0))).r, tear);
    color.b = mix(color.b, texture2D(uTexSampler, fract(uv - vec2(0.01 * tear, 0.0))).b, tear);

    // Block noise speckle.
    float block = hash(floor(uv.x * 32.0) + floor(uv.y * 32.0) * 57.0 + t * 13.0);
    float noise = step(1.0 - uIntensity * 0.05, block) * glitchActive;
    color.rgb = mix(color.rgb, vec3(noise), noise * 0.6);

    gl_FragColor = color;
}
