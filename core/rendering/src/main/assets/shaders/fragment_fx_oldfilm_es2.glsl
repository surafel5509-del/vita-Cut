// Retro film family, selected by uMode:
//   0 = old film (sepia + flicker + gate weave + heavy grain)
//   1 = dust (random specks)
//   2 = scratches (vertical lines)
//   3 = film grain only (no sepia/flicker) — used by the standalone "Film grain" effect
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform int uMode;
uniform float uTimeSec;
uniform float uIntensity;

float hash(float n) {
    return fract(sin(n) * 43758.5453123);
}

void main() {
    vec2 uv = vTexSamplingCoord;
    vec4 color = texture2D(uTexSampler, uv);
    float t = floor(uTimeSec * 18.0); // 18Hz artifact cadence

    if (uMode == 0) {
        // Gate weave: tiny wandering offset.
        uv.x += (hash(t * 1.3) - 0.5) * 0.004 * uIntensity;
        uv.y += (hash(t * 2.7 + 17.0) - 0.5) * 0.004 * uIntensity;
        color = texture2D(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0)));

        // Sepia tone.
        float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
        vec3 sepia = vec3(luma * 1.12, luma * 0.98, luma * 0.78);
        color.rgb = mix(color.rgb, sepia, 0.6 * uIntensity);

        // Exposure flicker.
        float flicker = 1.0 + (hash(t * 5.1) - 0.5) * 0.22 * uIntensity;
        color.rgb *= flicker;

        // Heavy grain.
        float grain = hash(uv.x * 1024.0 + uv.y * 768.0 + t * 91.0);
        color.rgb += (grain - 0.5) * 0.25 * uIntensity;

        // Worn vignette.
        vec2 d = uv - 0.5;
        color.rgb *= 1.0 - dot(d, d) * 0.8 * uIntensity;
    } else if (uMode == 1) {
        // Dust specks: sparse bright/dark dots that pop for a frame or two.
        float cell = 96.0;
        vec2 grid = floor(uv * cell);
        float rnd = hash(grid.x * 13.7 + grid.y * 71.3 + t * 3.1);
        float speck = step(1.0 - 0.012 * uIntensity, rnd);
        float bright = step(0.5, hash(rnd * 777.0));
        color.rgb = mix(color.rgb, vec3(bright), speck * 0.9);
    } else if (uMode == 2) {
        // Scratches: a few wandering vertical lines.
        for (float i = 0.0; i < 3.0; i += 1.0) {
            float lifetime = hash(i * 31.0 + floor(uTimeSec * 2.0));
            float x = hash(i * 17.0 + floor(uTimeSec * 2.0) * 7.0);
            float active = step(1.0 - 0.35 * uIntensity, lifetime);
            float wobble = sin(uv.y * 90.0 + i) * 0.0006;
            float line = smoothstep(0.0015, 0.0, abs(uv.x - x + wobble));
            color.rgb = mix(color.rgb, vec3(0.95, 0.93, 0.9), line * active);
        }
    } else {
        // Film grain only.
        float grain = hash(uv.x * 1536.0 + uv.y * 1024.0 + t * 61.0);
        color.rgb += (grain - 0.5) * 0.4 * uIntensity;
    }

    gl_FragColor = clamp(color, 0.0, 1.0);
}
