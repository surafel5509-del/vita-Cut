// Light leak (uMode 0) and film burn (uMode 1): warm gradients sweeping across the frame,
// animated by uTimeSec and scaled by uIntensity.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform float uTimeSec;
uniform float uIntensity;
uniform int uMode;

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    vec2 uv = vTexSamplingCoord;

    float sweep = fract(uTimeSec * 0.08); // slow traversal
    vec3 leakColor;
    float mask;

    if (uMode == 1) {
        // Film burn: blistering hole growing from a corner with hot orange rim.
        vec2 corner = vec2(0.0, 1.0);
        float d = length((uv - corner) * vec2(1.0, 0.6)) - sweep * 0.9;
        mask = smoothstep(0.15, -0.05, d) * uIntensity;
        float rim = smoothstep(0.2, 0.0, abs(d)) * uIntensity;
        leakColor = mix(vec3(1.0, 0.45, 0.1), vec3(1.0, 0.95, 0.8), rim);
        color.rgb = mix(color.rgb, leakColor, clamp(mask + rim * 0.5, 0.0, 1.0));
        color.rgb += rim * vec3(0.5, 0.2, 0.05);
    } else {
        // Light leak: soft diagonal band of warm light drifting across.
        float diag = (uv.x * 0.7 + uv.y * 0.7) - sweep * 2.0 + 0.65;
        mask = exp(-diag * diag * 6.0) * uIntensity;
        float edge = exp(-pow(uv.y * 3.0, 2.0)) * 0.3; // glow hugging the top edge
        leakColor = mix(vec3(1.0, 0.55, 0.25), vec3(1.0, 0.85, 0.5), uv.y);
        color.rgb += leakColor * (mask + edge * uIntensity);
    }

    gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}
