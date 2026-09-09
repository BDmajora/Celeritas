#version 460
#extension GL_ARB_shading_language_include : enable
#extension GL_NV_gpu_shader5 : require
#extension GL_NV_shader_buffer_load : require

#import <impetus:mesh/scene.glsl>

// Same trick as the region pass: no colour output, the write is the result.
// The low byte of the payload is the shifted visibility history with bit 0 already set, so storing it both marks
// the section visible and advances its history in one write.
layout(early_fragment_tests) in;

void main() {
    sectionVisibility[gl_PrimitiveID >> 8] = uint8_t(gl_PrimitiveID);
}
