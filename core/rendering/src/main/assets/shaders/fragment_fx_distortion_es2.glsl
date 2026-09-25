// Distortion family, selected by uMode:
//   0 = wave (horizontal sine displacement)
//   1 = ripple (radial rings from center)
//   2 = warp (smooth swirl/bulge blend)
//   3 = fisheye (barrel lens)
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform int uMode;
uniform float uIntensity;
uniform float uTimeSec;
uniform float uAspect; // width/height so circular effects stay circular

vec2 aspectCorrect(vec2 p) {
    return vec2((p.x - 0.5) * uAspect, p.y - 0.5);
}

vec2 aspectRestore(vec2 p) {
    return vec2(p.x / uAspect + 0.5, p.y + 0.5);
}

void main() {
    vec2 uv = vTexSamplingCoord;

    if (uMode == 0) {
        uv.x += sin(uv.y * 30.0 + uTimeSec * 4.0) * 0.02 * uIntensity;
        uv.y += sin(uv.x * 20.0 - uTimeSec * 3.0) * 0.01 * uIntensity;
    } else if (uMode == 1) {
        vec2 p = aspectCorrect(uv);
        float d = length(p);
        float wave = sin(d * 40.0 - uTimeSec * 6.0) * 0.02 * uIntensity * exp(-d * 3.0);
        p += normalize(p + vec2(0.0001)) * wave;
        uv = aspectRestore(p);
    } else if (uMode == 2) {
        vec2 p = aspectCorrect(uv);
        float d = length(p);
        float angle = uIntensity * 3.0 * exp(-d * 2.0);
        float s = sin(angle); float c = cos(angle);
        p = vec2(p.x * c - p.y * s, p.x * s + p.y * c);
        // subtle bulge
        p *= 1.0 - uIntensity * 0.1 * exp(-d * 2.5);
        uv = aspectRestore(p);
    } else if (uMode == 3) {
        vec2 p = (uv - 0.5) * 2.0;
        p.x *= uAspect / max(uAspect, 1.0);
        float r2 = dot(p, p);
        float f = 1.0 + r2 * (0.35 * uIntensity);
        vec2 distorted = p / f;
        uv = distorted * 0.5 + 0.5;
        // blacken pixels pulled outside the source
        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
            gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
            return;
        }
    } else if (uMode == 4) {
        // Mirror: fold the frame around the vertical axis, intensity blends in a second fold.
        float axis = mix(0.5, 0.35, uIntensity);
        if (uv.x > axis) {
            uv.x = axis - (uv.x - axis);
        }
        if (uIntensity > 0.65 && uv.y > 0.5) {
            uv.y = 1.0 - uv.y;
        }
    } else {
        // Kaleidoscope
        vec2 p = aspectCorrect(uv);
        float angle = atan(p.y, p.x);
        float radius = length(p);
        float slices = mix(4.0, 10.0, uIntensity);
        float slice = 6.2831853 / slices;
        angle = mod(angle, slice);
        angle = abs(angle - slice * 0.5);
        p = vec2(cos(angle), sin(angle)) * radius;
        uv = aspectRestore(p);
    }

    gl_FragColor = texture2D(uTexSampler, clamp(uv, vec2(0.0), vec2(1.0)));
}
