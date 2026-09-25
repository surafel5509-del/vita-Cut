// Strobe flash: additive white pulse. The CPU drives uFlash (0..1) from a time envelope so
// users can keyframe pulse rate/intensity; the shader stays trivial and fast.
precision mediump float;

varying vec2 vTexSamplingCoord;
uniform sampler2D uTexSampler;
uniform float uFlash;       // current flash amount 0..1
uniform vec3 uFlashColor;   // default white; creative choice for colored strobes

void main() {
    vec4 color = texture2D(uTexSampler, vTexSamplingCoord);
    color.rgb = mix(color.rgb, uFlashColor, uFlash);
    gl_FragColor = color;
}
