// CRT monitor emulation: scanlines, aperture-grille stripes, barrel curvature, edge vignette
// and a slow rolling refresh band.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform float uTimeSec;
uniform float uIntensity;

vec2 curveUv(vec2 uv, float amount) {
    uv = uv * 2.0 - 1.0;
    vec2 offset = abs(uv.yx) / vec2(6.0, 4.0);
    uv += uv * offset * offset * amount;
    return uv * 0.5 + 0.5;
}

void main() {
    vec2 uv = curveUv(vTexSamplingCoord, uIntensity * 0.35);

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec4 color = texture2D(uTexSampler, uv);

    // Scanlines (~480 visible lines).
    float scan = sin(uv.y * 480.0 * 3.14159) * 0.5 + 0.5;
    color.rgb *= 1.0 - scan * 0.18 * uIntensity;

    // Aperture grille: per-subpixel column tinting.
    float col = mod(floor(uv.x * 640.0), 3.0);
    vec3 grille = col < 0.5 ? vec3(1.06, 0.97, 0.97)
                : col < 1.5 ? vec3(0.97, 1.06, 0.97)
                : vec3(0.97, 0.97, 1.06);
    color.rgb *= mix(vec3(1.0), grille, uIntensity);

    // Rolling refresh band.
    float band = fract(uTimeSec * 0.35);
    float bandMask = smoothstep(0.0, 0.05, abs(uv.y - band));
    color.rgb *= mix(1.08, 1.0, bandMask * (1.0 - uIntensity) + uIntensity);
    color.rgb = mix(color.rgb, color.rgb * 1.05, (1.0 - bandMask) * uIntensity);

    // Phosphor glow vignette.
    vec2 d = uv - 0.5;
    color.rgb *= 1.0 - dot(d, d) * 0.9 * uIntensity;

    // Slight desaturation & contrast of old panels.
    float luma = dot(color.rgb, vec3(0.299, 0.587, 0.114));
    color.rgb = mix(color.rgb, vec3(luma) * vec3(1.02, 1.0, 0.96), 0.12 * uIntensity);

    gl_FragColor = clamp(color, 0.0, 1.0);
}
