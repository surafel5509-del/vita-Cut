// Anamorphic-style lens flare: a horizontal streak from the brightest region plus ghosts.
// uLightPos is CPU-tracked (or fixed at uLightPos when no tracking), 0..1 texture space.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uLightPos;
uniform float uIntensity;
uniform float uTimeSec;
uniform vec3 uFlareColor;

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    vec2 uv = vTexSamplingCoord;

    vec2 delta = uv - uLightPos;
    // Aspect-corrected distance so the flare is circular on any canvas.
    float dist = length(vec2(delta.x * 1.7777, delta.y));

    // Core glow.
    float glow = exp(-dist * 6.0) * 0.8;

    // Horizontal anamorphic streak.
    float streak = exp(-abs(delta.y) * 60.0) * exp(-abs(delta.x) * 3.0) * 0.6;

    // Ghosts along the line through the center.
    vec2 ghostDir = uLightPos - vec2(0.5);
    float ghost = 0.0;
    vec2 g1 = vec2(0.5) - ghostDir * 0.6;
    vec2 g2 = vec2(0.5) - ghostDir * 1.3;
    ghost += exp(-length((uv - g1) * vec2(1.7777, 1.0)) * 22.0) * 0.25;
    ghost += exp(-length((uv - g2) * vec2(1.7777, 1.0)) * 30.0) * 0.18;

    float flare = (glow + streak + ghost) * uIntensity;
    // Modulate slightly over time for a living highlight.
    flare *= 0.9 + 0.1 * sin(uTimeSec * 3.0);

    color.rgb += uFlareColor * flare;
    gl_FragColor = vec4(clamp(color.rgb, 0.0, 1.0), color.a);
}
