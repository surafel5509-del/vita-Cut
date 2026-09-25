// Composites one overlay bitmap (text, sticker, caption, image overlay) onto the frame with
// full transform + blend mode support. One instance per overlay layer, chained in order.
// uPlacement: xy = center in NDC (-1..1, GL convention), zw = half-size in NDC.
// Coordinates already include canvas aspect correction from the CPU side.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform sampler2D uOverlayTex;

uniform vec4 uPlacement;
uniform float uRotationRad;
uniform float uOpacity;
uniform int uBlend;       // 0 normal, 1 screen, 2 multiply, 3 overlay, 4 add, 5 darken, 6 lighten

vec3 blendColor(vec3 base, vec3 over, int mode) {
    if (mode == 1) return 1.0 - (1.0 - base) * (1.0 - over);            // screen
    if (mode == 2) return base * over;                                   // multiply
    if (mode == 3) {                                                     // overlay
        vec3 cond = step(base, vec3(0.5));
        vec3 low = 2.0 * base * over;
        vec3 high = 1.0 - 2.0 * (1.0 - base) * (1.0 - over);
        return mix(high, low, cond);
    }
    if (mode == 4) return min(base + over, vec3(1.0));                   // add
    if (mode == 5) return min(base, over);                               // darken
    if (mode == 6) return max(base, over);                               // lighten
    return over;                                                          // normal
}

void main() {
    vec4 base = texture2D(uTexSampler, vTexSamplingCoord);
    if (uOpacity <= 0.001) {
        gl_FragColor = base;
        return;
    }

    // Frame coords to NDC (y flipped: texture space is bottom-up in GL).
    vec2 ndc = (vTexSamplingCoord - 0.5) * 2.0;
    vec2 local = ndc - uPlacement.xy;

    // Rotate into overlay space.
    float s = sin(uRotationRad);
    float c = cos(uRotationRad);
    local = vec2(local.x * c + local.y * s, -local.x * s + local.y * c);

    vec2 halfSize = max(uPlacement.zw, vec2(0.00001));
    vec2 overlayUv = local / (halfSize * 2.0) + 0.5;

    if (overlayUv.x < 0.0 || overlayUv.x > 1.0 || overlayUv.y < 0.0 || overlayUv.y > 1.0) {
        gl_FragColor = base;
        return;
    }
    // Overlay bitmaps are top-down (Android); GL textures are bottom-up.
    overlayUv.y = 1.0 - overlayUv.y;

    vec4 over = texture2D(uOverlayTex, overlayUv);
    float alpha = over.a * uOpacity;
    if (alpha <= 0.001) {
        gl_FragColor = base;
        return;
    }

    vec3 blended = blendColor(base.rgb, over.rgb, uBlend);
    gl_FragColor = vec4(mix(base.rgb, blended, alpha), max(base.a, alpha));
}
