// Transition edge effects. uProgress: 0 = normal frame, 1 = fully transitioned.
// The CPU computes progress from the clip timeline position so identical math runs in preview
// and export. uKind selects the look; uDir the direction (0 left, 1 right, 2 up, 3 down).
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform vec2 uTexelSize;
uniform int uKind;
uniform int uDir;
uniform float uProgress;
uniform float uIntensity;
uniform float uTimeSec;
uniform float uAspect;

float hash(float n) { return fract(sin(n) * 43758.5453123); }

vec3 blurSample(vec2 uv, float radius) {
    vec3 sum = vec3(0.0);
    sum += texture2D(uTexSampler, uv + vec2(-radius, -radius) * uTexelSize).rgb;
    sum += texture2D(uTexSampler, uv + vec2(radius, -radius) * uTexelSize).rgb;
    sum += texture2D(uTexSampler, uv + vec2(-radius, radius) * uTexelSize).rgb;
    sum += texture2D(uTexSampler, uv + vec2(radius, radius) * uTexelSize).rgb;
    sum += texture2D(uTexSampler, uv).rgb;
    return sum / 5.0;
}

void main() {
    vec2 uv = vTexSamplingCoord;
    float p = clamp(uProgress, 0.0, 1.0) * uIntensity;

    if (p <= 0.0001) {
        gl_FragColor = texture2D(uTexSampler, uv);
        return;
    }

    // 0 FADE, 1 DIP BLACK, 2 DIP WHITE
    if (uKind <= 2) {
        vec4 c = texture2D(uTexSampler, uv);
        if (uKind == 0) { // fade: alpha-ish darkness ramp both for in/out usage
            gl_FragColor = vec4(c.rgb * (1.0 - p), c.a);
        } else if (uKind == 1) {
            gl_FragColor = vec4(c.rgb * (1.0 - p), c.a);
        } else {
            gl_FragColor = vec4(mix(c.rgb, vec3(1.0), p), c.a);
        }
        return;
    }

    // 3 BLUR_IN, 4 BLUR_OUT
    if (uKind == 3 || uKind == 4) {
        vec3 blurred = blurSample(uv, 2.0 + p * 24.0);
        vec4 c = texture2D(uTexSampler, uv);
        gl_FragColor = vec4(mix(c.rgb, blurred, p), c.a);
        return;
    }

    // 5 ZOOM_IN, 6 ZOOM_OUT
    if (uKind == 5 || uKind == 6) {
        float scale = uKind == 5 ? mix(1.0, 2.2, p) : mix(2.2, 1.0, p);
        vec2 z = (uv - 0.5) / scale + 0.5;
        vec4 c = texture2D(uTexSampler, clamp(z, vec2(0.0), vec2(1.0)));
        c.rgb *= mix(1.0, 0.0, uKind == 5 ? p * p : (1.0 - (1.0 - p) * (1.0 - p)));
        gl_FragColor = c;
        return;
    }

    // 7 SPIN_IN, 8 SPIN_OUT
    if (uKind == 7 || uKind == 8) {
        vec2 q = uv - 0.5;
        q.x *= uAspect;
        float angle = p * 6.28318 * (uKind == 7 ? 1.0 : -1.0);
        float s = sin(angle); float c = cos(angle);
        q = vec2(q.x * c - q.y * s, q.x * s + q.y * c);
        q /= mix(1.0, 0.35, p);
        q.x /= uAspect;
        vec4 sample4 = texture2D(uTexSampler, clamp(q + 0.5, vec2(0.0), vec2(1.0)));
        sample4.rgb *= (1.0 - p);
        gl_FragColor = sample4;
        return;
    }

    // 9 SLIDE, 10 PUSH
    if (uKind == 9 || uKind == 10) {
        vec2 shift = vec2(0.0);
        if (uDir == 0) shift = vec2(-p, 0.0);
        else if (uDir == 1) shift = vec2(p, 0.0);
        else if (uDir == 2) shift = vec2(0.0, p);
        else shift = vec2(0.0, -p);
        vec2 s = uv + shift;
        if (s.x < 0.0 || s.x > 1.0 || s.y < 0.0 || s.y > 1.0) {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        } else {
            gl_FragColor = texture2D(uTexSampler, s);
        }
        return;
    }

    // 11 SHAKE_IN
    if (uKind == 11) {
        float amp = sin(p * 3.14159) * 0.08;
        vec2 s = uv + vec2(hash(floor(uTimeSec * 30.0)) - 0.5, hash(floor(uTimeSec * 30.0) + 7.0) - 0.5) * amp;
        vec4 c = texture2D(uTexSampler, clamp(s, vec2(0.0), vec2(1.0)));
        c.rgb *= (1.0 - p * 0.9);
        gl_FragColor = c;
        return;
    }

    // 12 GLITCH_IN, 13 GLITCH_OUT
    if (uKind == 12 || uKind == 13) {
        float t = floor(uTimeSec * 20.0);
        float amp = sin(p * 3.14159);
        float rowShift = (hash(floor(uv.y * 30.0) + t) - 0.5) * 0.2 * amp;
        vec2 s = fract(uv + vec2(rowShift, 0.0));
        vec4 c;
        c.r = texture2D(uTexSampler, fract(s + vec2(0.01 * amp, 0.0))).r;
        c.g = texture2D(uTexSampler, s).g;
        c.b = texture2D(uTexSampler, fract(s - vec2(0.01 * amp, 0.0))).b;
        c.a = 1.0;
        c.rgb *= (1.0 - p * 0.85);
        gl_FragColor = c;
        return;
    }

    // 14 FLASH_IN, 15 FLASH_OUT
    if (uKind == 14 || uKind == 15) {
        vec4 c = texture2D(uTexSampler, uv);
        float flash = sin(p * 3.14159);
        gl_FragColor = vec4(mix(c.rgb, vec3(1.0), flash), c.a);
        return;
    }

    // 16 LIGHT_SWEEP, 17 LIGHT_LEAK_IN
    if (uKind == 16 || uKind == 17) {
        vec4 c = texture2D(uTexSampler, uv);
        float sweepPos = mix(-0.4, 1.4, p);
        float d = abs(uv.x - sweepPos);
        float sweep = exp(-d * d * 28.0);
        vec3 warm = vec3(1.0, 0.85, 0.6);
        c.rgb += warm * sweep * 0.9;
        c.rgb = mix(c.rgb, warm * 0.6, p * p * 0.5);
        gl_FragColor = vec4(clamp(c.rgb, 0.0, 1.0), c.a);
        return;
    }

    // 18 CUBE_SPIN, 19 FLIP (pseudo-3D)
    if (uKind == 18 || uKind == 19) {
        float angle = p * 3.14159 * 0.5 * (uDir == 1 || uDir == 3 ? -1.0 : 1.0);
        vec2 q = uv - 0.5;
        float c = cos(angle);
        // Perspective foreshortening along the rotation axis.
        if (uKind == 18) {
            q.x = q.x / max(c, 0.05);
        } else {
            q.y = q.y / max(c, 0.05);
        }
        q += 0.5;
        if (q.x < 0.0 || q.x > 1.0 || q.y < 0.0 || q.y > 1.0) {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        } else {
            vec4 s = texture2D(uTexSampler, q);
            // Simple lambert shading of the turning face.
            s.rgb *= c;
            gl_FragColor = s;
        }
        return;
    }

    // 20 WIPE
    if (uKind == 20) {
        float coord = uDir == 0 ? 1.0 - uv.x : uDir == 1 ? uv.x : uDir == 2 ? uv.y : 1.0 - uv.y;
        float edge = smoothstep(p, p - 0.02, coord);
        vec4 c = texture2D(uTexSampler, uv);
        gl_FragColor = vec4(c.rgb * edge, c.a * edge);
        return;
    }

    // 21 CINEMATIC_BARS: letterbox bars close in as the transition progresses.
    {
        vec4 c = texture2D(uTexSampler, uv);
        float limit = 1.0 - p * 0.55;
        float inBar = smoothstep(limit, limit + 0.02, abs(uv.y - 0.5) * 2.0);
        c.rgb = mix(c.rgb, vec3(0.0), inBar);
        c.rgb *= (1.0 - p * 0.35);
        gl_FragColor = c;
    }
}
