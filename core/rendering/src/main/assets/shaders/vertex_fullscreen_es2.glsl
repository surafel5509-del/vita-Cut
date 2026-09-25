// Fullscreen quad vertex shader shared by all Vita Cut effect programs.
// Attribute/uniform names follow the Media3 effect pipeline conventions.
attribute vec4 aFramePosition;
attribute vec4 aTexPosition;
uniform mat4 uTransformationMatrix;
uniform mat4 uTexTransformationMatrix;
varying vec2 vTexSamplingCoord;

void main() {
    gl_Position = uTransformationMatrix * aFramePosition;
    vec4 texPosition = uTexTransformationMatrix * aTexPosition;
    vTexSamplingCoord = texPosition.xy;
}
