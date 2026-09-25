// Atmosphere / motion family, selected by uMode:
//  0 rain  1 snow  2 fog  3 zoom pulse  4 spin blur  5 god rays
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform int uMode;
uniform float uIntensity;
uniform float uTimeSec;
uniform float uAspect;

float hash(float n) { return fract(sin(n) * 43758.5453123); }

vec3 sampleRgb(vec2 uv) {
    return texture2D(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0))).rgb;
}

void main() {
    vec2 uv = vTexSamplingCoord;
    vec4 src = texture2D(uTexSampler, uv);
    vec3 color = src.rgb;
    float t = clamp(uIntensity, 0.0, 1.0);

    if (uMode == 0) {
        float cols = 42.0;
        float x = floor(uv.x * cols);
        float speed = 1.6 + hash(x) * 1.4;
        float y = fract(uv.y * 18.0 + uTimeSec * speed + hash(x * 3.1));
        float drop = smoothstep(0.02, 0.0, abs(y - 0.08)) * step(0.55, hash(x + 17.0));
        color = mix(src.rgb, src.rgb + vec3(0.55, 0.62, 0.75) * drop, t);
        color *= mix(1.0, 0.88, t * 0.4);
    } else if (uMode == 1) {
        float flakes = 0.0;
        for (float i = 0.0; i < 6.0; i += 1.0) {
            vec2 cell = uv * (8.0 + i * 3.0);
            cell.y += uTimeSec * (0.12 + i * 0.04);
            cell.x += sin(uTimeSec * 0.4 + i) * 0.15;
            vec2 f = fract(cell) - 0.5;
            float id = hash(i + 11.0 + floor(cell.x) * 13.0 + floor(cell.y) * 7.0);
            flakes += smoothstep(0.08 * id, 0.0, length(f)) * id;
        }
        color = mix(src.rgb, src.rgb + vec3(flakes) * 0.85, t);
    } else if (uMode == 2) {
        float depth = smoothstep(0.15, 0.95, uv.y);
        vec3 fog = vec3(0.78, 0.82, 0.88);
        color = mix(src.rgb, mix(src.rgb, fog, depth * 0.75), t);
        color += vec3(0.04) * t;
    } else if (uMode == 3) {
        float pulse = 0.5 + 0.5 * sin(uTimeSec * 4.0);
        float scale = 1.0 + pulse * 0.18 * t;
        vec2 z = (uv - 0.5) / scale + 0.5;
        color = mix(src.rgb, sampleRgb(z), t);
    } else if (uMode == 4) {
        vec2 p = uv - 0.5;
        p.x *= uAspect;
        vec3 acc = vec3(0.0);
        for (float i = 0.0; i < 8.0; i += 1.0) {
            float a = (i - 3.5) * 0.045 * t;
            float s = sin(a);
            float c = cos(a);
            vec2 r = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
            r.x /= uAspect;
            acc += sampleRgb(r + 0.5);
        }
        color = mix(src.rgb, acc / 8.0, t);
    } else {
        vec2 light = vec2(0.72, 0.22);
        vec2 dir = normalize(uv - light);
        vec3 acc = src.rgb;
        for (float i = 1.0; i <= 8.0; i += 1.0) {
            acc += sampleRgb(uv - dir * uTexelSize * i * 14.0) * (1.0 / i);
        }
        acc /= 3.2;
        float shaft = pow(max(0.0, 1.0 - length(uv - light)), 1.4);
        color = mix(src.rgb, clamp(src.rgb + acc * shaft * 0.65, 0.0, 1.0), t);
    }

    gl_FragColor = vec4(color, src.a);
}
