// The one uniform block the whole mesh backend shares.
// Bound by GPU address rather than by binding point, so it survives program changes and every phase of the frame
// sees the same block without rebinding anything.
//
// Most of it is a table of raw device pointers. That is the point of the backend: shaders reach geometry and
// metadata through pointers instead of descriptors, so there is nothing to bind between draws.

#define Vertex uvec4

// Laid out so the section rasterizer touches one cache line per section: position, extent and draw ranges all in
// 32 bytes.
struct Section {
    ivec4 header;
    // header.x -> [0..3] minX, [4..7] sizeX, [8..31] chunk x
    // header.y -> [0..3] minY, [4..7] sizeY, [8..16] chunk y, [17] hidden, [18..25] index within region
    // header.z -> [0..3] minZ, [4..7] sizeZ, [8..31] chunk z
    // header.w -> first quad of this section in the geometry arena
    ivec4 renderRanges;
    // Eight uint16 quad counts: six directional, then unassigned, then the section's own base quad offset.
    // Counts rather than absolute offsets, so a section can hold far more quads than 16 bits could address.
};

struct Region {
    uint64_t a;
    uint64_t b;
};

// Extent in sections, minus one, over the occupied part of the region only.
ivec3 unpackRegionSize(Region region) {
    return ivec3((region.a >> 59) & 7, region.a >> 62, (region.a >> 56) & 7);
}

// Section coordinates of the region's occupied corner. Each field is sign-extended out of its 24-bit slot by
// shifting it to the top of a 64-bit word and back down arithmetically.
ivec3 unpackRegionPosition(Region region) {
    int x = int(int64_t(region.a << 16) >> 40);
    int y = (int(region.a) << 8) >> 8;
    int z = int(int64_t(region.b) >> 40);
    return ivec3(x, y, z);
}

// Highest live section index in the region, so the task shader dispatches exactly that many meshlets.
int unpackRegionCount(Region region) {
    return int((region.a >> 48) & 255);
}

// A section with no geometry writes a zeroed header. The index field has to be masked off first, because a
// section at index 0 is legitimately all-zero apart from that.
bool sectionEmpty(ivec4 header) {
    header.y &= ~(0x1FF << 17);
    return header == ivec4(0);
}

layout(std140, binding = 0) uniform SceneData {
    // Projection * modelview, already translated by the camera's offset within its own section, so shader-side
    // positions never exceed a few hundred blocks.
    mat4 MVP;

    // The section the camera is in. Every position in this pipeline is relative to it.
    ivec4 cameraChunk;
    // Camera offset within that section, negated. Needed to tell whether the camera is inside a box.
    vec4 subChunkOffset;
    vec4 fogColour;

    // ---- device pointers ----
    // The visible region ids for this frame, written immediately after this struct in the same allocation.
    readonly restrict uint16_t *regionIndices;
    readonly restrict Region *regionData;
    restrict Section *sectionData;
    // One byte per visible region, and one per section. Written by the occlusion fragment shaders, read by the
    // terrain task shader the following frame.
    restrict uint8_t *regionVisibility;
    restrict uint8_t *sectionVisibility;
    // Indirect draw commands, written by the section rasterizer's task shader.
    writeonly restrict uvec2 *terrainCommandBuffer;
    // All terrain geometry: four consecutive vertices per quad.
    readonly restrict Vertex *terrainData;
    // Reserved; the statistics counters land here once they are wired up.
    uint32_t *statisticsBuffer;

    // Half the framebuffer size, so clip space maps to pixels with one multiply.
    vec2 screenSize;

    float fogStart;
    float fogEnd;
    bool isCylindricalFog;

    uint16_t regionCount;
    uint8_t frameId;
};
