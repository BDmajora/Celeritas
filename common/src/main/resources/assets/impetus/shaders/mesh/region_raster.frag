#version 460
#extension GL_ARB_shading_language_include : enable
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_shader_buffer_load : require

#import <impetus:mesh/scene.glsl>

// The whole point of this shader is the side effect. Colour writes are off; reaching main() at all is the
// evidence that some part of the region's box passed the depth test.
//
// early_fragment_tests forces the depth test to run before the shader, or the write below would happen for
// occluded fragments too. Combined with GL_REPRESENTATIVE_FRAGMENT_TEST_NV this runs roughly once per visible
// primitive rather than once per covered pixel.
layout(early_fragment_tests) in;

void main() {
    regionVisibility[gl_PrimitiveID] = uint8_t(1);
}
