// Blur family in one program, selected by uMode:
//   0 = gaussian (9-tap), 1 = motion blur (directional, 9-tap), 2 = cinematic blur
//       (stronger gaussian + slight vignette + 2.39:1 letterbox softening).
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform int uMode;
uniform float uIntensity;   // 0..1
uniform vec2 uDirection;    // motion blur direction (normalized)

vec3 gaussian(vec2 uv, float radius) {
    vec3 sum = vec3(0.0);
    sum += texture2D(uTexSampler, uv + vec2(-radius, -radius) * uTexelSize).rgb * 1.0;
    sum += texture2D(uTexSampler, vec2(uv.x, uv.y - radius * uTexelSize.y)).rgb * 2.0;
    sum += texture2D(uTexSampler, uv + vec2(radius, -radius) * uTexelSize).rgb * 1.0;
    sum += texture2D(uTexSampler, vec2(uv.x - radius * uTexelSize.x, uv.y)).rgb * 2.0;
    sum += texture2D(uTexSampler, uv).rgb * 4.0;
    sum += texture2D(uTexSampler, vec2(uv.x + radius * uTexelSize.x, uv.y)).rgb * 2.0;
    sum += texture2D(uTexSampler, uv + vec2(-radius, radius) * uTexelSize).rgb * 1.0;
    sum += texture2D(uTexSampler, vec2(uv.x, uv.y + radius * uTexelSize.y)).rgb * 2.0;
    sum += texture2D(uTexSampler, uv + vec2(radius, radius) * uTexelSize).rgb * 1.0;
    return sum / 16.0;
}

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);

    if (uMode == 1) {
        vec3 sum = vec3(0.0);
        float steps = 9.0;
        for (float i = -4.0; i <= 4.0; i += 1.0) {
            vec2 offset = uDirection * uTexelSize * i * 2.0 * uIntensity * 4.0;
            sum += texture2D(uTexSampler, clamp(vTexSamplingCoord + offset, vec2(0.0), vec2(1.0))).rgb;
        }
        gl_FragColor = vec4(mix(color.rgb, sum / steps, uIntensity), color.a);
        return;
    }

    float radius = 1.0 + uIntensity * 5.0;
    vec3 blurred = gaussian(vTexSamplingCoord, radius);

    if (uMode == 2) {
        blurred = mix(blurred, gaussian(vTexSamplingCoord, radius * 2.0), 0.5);
        // Cinematic letterbox: gently darken outside 2.39:1 window.
        float y = abs(vTexSamplingCoord.y - 0.5) * 2.0;
        float bar = smoothstep(0.836, 1.0, y); // 1080/2.39/1080 ~= 0.418 half-height -> y>0.836
        blurred *= (1.0 - bar * uIntensity);
    }

    gl_FragColor = vec4(mix(color.rgb, blurred, uIntensity), color.a);
}
