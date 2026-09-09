package com.bdmajora.impetus.engine.impl.render.mesh;

import com.bdmajora.impetus.engine.impl.render.chunk.ChunkRenderMatrices;
import com.bdmajora.impetus.engine.impl.render.mesh.gl.BindlessBuffer;
import com.bdmajora.impetus.engine.impl.render.mesh.region.MeshRegionStore;
import com.bdmajora.impetus.engine.impl.render.mesh.region.MeshSectionStore;
import com.bdmajora.impetus.engine.impl.render.mesh.renderers.RegionRasterizer;
import com.bdmajora.impetus.engine.impl.render.mesh.renderers.SectionRasterizer;
import com.bdmajora.impetus.engine.impl.render.mesh.renderers.TerrainRasterizer;
import com.bdmajora.impetus.engine.impl.render.mesh.util.QuadArena;
import com.bdmajora.impetus.engine.impl.render.mesh.util.UploadStream;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.impl.MeshChunkVertex;
import com.bdmajora.impetus.engine.impl.render.viewport.Viewport;
import com.bdmajora.impetus.lwjgl.GL11;
import com.bdmajora.impetus.lwjgl.GL42;
import com.bdmajora.impetus.lwjgl.GL43;
import com.bdmajora.impetus.lwjgl.GLNv;
import it.unimi.dsi.fastutil.ints.IntAVLTreeSet;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import org.joml.Matrix4f;

import java.util.BitSet;

import static com.bdmajora.impetus.lwjgl.LWJGLServiceProvider.LWJGL;

// The GPU-driven terrain frame
//
// Three phases, and the CPU only participates in the first:
//   1. Frustum-cull REGIONS on the CPU (hundreds of boxes, not tens of thousands of sections), sort them
//      front-to-back and write the scene UBO, which is mostly a table of GPU pointers.
//   2. Rasterise the surviving region boxes; the fragment shader marks which regions were reached.
//   3. Rasterise the section boxes of those regions; the fragment shader marks which sections were reached, and
//      the task shader writes the indirect draw commands.
// The terrain draw itself runs FIRST, from the commands phase 3 wrote LAST frame. That inversion is what lets
// the whole thing run without a single GPU-to-CPU readback, at the cost of one frame of latency on newly
// visible geometry
public class MeshRenderPipeline {
    // Cap on live regions, and the thing that sizes every per-region buffer
    // A region is 8x4x8 sections, and 1.12.2's world height fixes the vertical span at four region layers, so
    // render distance 64 needs roughly 17*17*4 ~ 1200. 4096 leaves headroom without the section metadata buffer
    // (256 headers of 32 bytes per region) turning into hundreds of megabytes of VRAM
    private static final int MAX_REGIONS = 4096;

    // Scene uniform block, std140. Layout must match scene.glsl exactly, field for field
    private static final int SCENE_BYTES = align(
            16 * 4    // mat4 MVP
                    + 16      // ivec4 cameraChunk
                    + 16      // vec4 subChunkOffset
                    + 16      // vec4 fogColour
                    + 8 * 8   // eight 64-bit device pointers
                    + 8       // vec2 screenSize
                    + 4 + 4   // fog start, fog end
                    + 4       // int fogShape
                    + 2       // uint16 regionCount
                    + 1,      // uint8 frameId
            16);

    private final UploadStream uploadStream;
    private final MeshSectionStore sections;

    private final BindlessBuffer sceneUniform;
    private final BindlessBuffer regionVisibility;
    private final BindlessBuffer sectionVisibility;
    private final BindlessBuffer terrainCommands;

    private final RegionRasterizer regionRasterizer;
    private final SectionRasterizer sectionRasterizer;
    private final TerrainRasterizer terrainRasterizer;

    // Which regions were inside the frustum last frame, so a region leaving it can have its stale section
    // visibility bytes cleared instead of staying "visible" forever
    private final BitSet regionsInFrustum = new BitSet(MAX_REGIONS);

    // Reused across frames; the sort key is (distance << 16) | regionId, so iteration order is front-to-back
    private final IntSortedSet visibleRegions = new IntAVLTreeSet();

    private final Matrix4f mvp = new Matrix4f();

    private int previousRegionCount;
    private int frameId;

    public MeshRenderPipeline(long uploadBufferSize, long geometryBudget) {
        this.uploadStream = new UploadStream(uploadBufferSize);

        MeshRegionStore regions = new MeshRegionStore(MAX_REGIONS, this.uploadStream);
        QuadArena arena = new QuadArena(geometryBudget, MeshChunkVertex.STRIDE);
        this.sections = new MeshSectionStore(regions, arena, this.uploadStream);

        // The visible region id list lives immediately after the scene struct, inside the same allocation, so the
        // shader reaches it by pointer arithmetic off the UBO's own address
        this.sceneUniform = new BindlessBuffer(SCENE_BYTES + MAX_REGIONS * 2L);
        this.regionVisibility = new BindlessBuffer(MAX_REGIONS);
        this.sectionVisibility = new BindlessBuffer(MAX_REGIONS * (long) MeshRegionStore.SECTIONS_PER_REGION);
        this.terrainCommands = new BindlessBuffer(MAX_REGIONS * 8L);

        this.regionVisibility.clear();
        this.sectionVisibility.clear();
        this.terrainCommands.clear();

        this.regionRasterizer = new RegionRasterizer();
        this.sectionRasterizer = new SectionRasterizer();
        this.terrainRasterizer = new TerrainRasterizer();
    }

    public MeshSectionStore getSections() {
        return this.sections;
    }

    public UploadStream getUploadStream() {
        return this.uploadStream;
    }

    // Draws one frame of terrain and computes the visibility the next frame will draw from
    // cameraX/Y/Z are the exact camera position in world space; the block atlas and lightmap are passed as GL
    // texture names because this package deliberately knows nothing about Minecraft
    public void renderFrame(Viewport viewport, ChunkRenderMatrices matrices,
                            double cameraX, double cameraY, double cameraZ,
                            int screenWidth, int screenHeight) {
        MeshRegionStore regions = this.sections.getRegions();

        if (regions.getRegionCount() == 0) {
            return;
        }

        int cameraSectionX = (int) Math.floor(cameraX) >> 4;
        int cameraSectionY = (int) Math.floor(cameraY) >> 4;
        int cameraSectionZ = (int) Math.floor(cameraZ) >> 4;

        int visibleCount = collectVisibleRegions(regions, viewport, cameraSectionX, cameraSectionY, cameraSectionZ);

        if (visibleCount == 0) {
            return;
        }

        writeSceneUniform(matrices, cameraX, cameraY, cameraZ,
                cameraSectionX, cameraSectionY, cameraSectionZ, screenWidth, screenHeight, visibleCount);

        // Everything the shaders are about to read has to be resident before the first draw
        this.sections.commit();
        this.uploadStream.commit();

        // Address-based binding stays live across program changes, unlike a bound UBO, so this is set once for
        // the whole frame
        LWJGL.glEnableClientState(GLNv.GL_UNIFORM_BUFFER_UNIFIED_NV);
        LWJGL.glEnableClientState(GLNv.GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        LWJGL.glEnableClientState(GLNv.GL_ELEMENT_ARRAY_UNIFIED_NV);
        LWJGL.glEnableClientState(GLNv.GL_DRAW_INDIRECT_UNIFIED_NV);
        LWJGL.glBufferAddressRangeNV(GLNv.GL_UNIFORM_BUFFER_ADDRESS_NV, 0,
                this.sceneUniform.getDeviceAddress(), SCENE_BYTES);

        // ---- the terrain draw, from last frame's commands ----
        if (this.previousRegionCount != 0) {
            LWJGL.glEnable(GL11.GL_DEPTH_TEST);
            this.terrainRasterizer.raster(this.previousRegionCount, this.terrainCommands.getDeviceAddress());
            LWJGL.glMemoryBarrier(GL42.GL_FRAMEBUFFER_BARRIER_BIT);
        }

        // ---- visibility for the next frame ----
        // Depth writes stay off: the representative fragment test needs them off, and the occlusion boxes must
        // not pollute the depth buffer the terrain just wrote
        LWJGL.glEnable(GL11.GL_DEPTH_TEST);
        LWJGL.glDepthFunc(GL11.GL_LEQUAL);
        LWJGL.glDepthMask(false);
        LWJGL.glColorMask(false, false, false, false);
        LWJGL.glEnable(GLNv.GL_REPRESENTATIVE_FRAGMENT_TEST_NV);

        this.regionRasterizer.raster(visibleCount);
        LWJGL.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        this.sectionRasterizer.raster(visibleCount);
        LWJGL.glMemoryBarrier(GL43.GL_SHADER_STORAGE_BARRIER_BIT);

        LWJGL.glDisable(GLNv.GL_REPRESENTATIVE_FRAGMENT_TEST_NV);
        LWJGL.glDepthMask(true);
        LWJGL.glColorMask(true, true, true, true);

        // The commands the section rasteriser just wrote are what the next frame draws from
        LWJGL.glMemoryBarrier(GL42.GL_COMMAND_BARRIER_BIT);
        this.previousRegionCount = visibleCount;

        LWJGL.glDisableClientState(GLNv.GL_UNIFORM_BUFFER_UNIFIED_NV);
        LWJGL.glDisableClientState(GLNv.GL_VERTEX_ATTRIB_ARRAY_UNIFIED_NV);
        LWJGL.glDisableClientState(GLNv.GL_ELEMENT_ARRAY_UNIFIED_NV);
        LWJGL.glDisableClientState(GLNv.GL_DRAW_INDIRECT_UNIFIED_NV);
        LWJGL.glDisable(GL11.GL_DEPTH_TEST);

        this.uploadStream.endFrame();
    }

    public void delete() {
        this.regionRasterizer.delete();
        this.sectionRasterizer.delete();
        this.terrainRasterizer.delete();

        this.sceneUniform.delete();
        this.regionVisibility.delete();
        this.sectionVisibility.delete();
        this.terrainCommands.delete();

        this.sections.getArena().delete();
        this.sections.getRegions().delete();
        this.uploadStream.delete();
    }

    // Frustum-culls regions, sorts them front-to-back and writes the id list the shaders index by workgroup
    // Sorting matters for overdraw: the region rasteriser draws boxes with the depth test on, so near regions
    // occlude far ones only if they are drawn first
    private int collectVisibleRegions(MeshRegionStore regions, Viewport viewport,
                                      int cameraSectionX, int cameraSectionY, int cameraSectionZ) {
        this.visibleRegions.clear();

        for (int regionId = 0; regionId < regions.getMaxRegionIndex(); regionId++) {
            if (!regions.regionExists(regionId)) {
                continue;
            }

            if (regions.isRegionVisible(viewport, regionId)) {
                int distance = regions.distanceTo(regionId, cameraSectionX, cameraSectionY, cameraSectionZ);
                this.visibleRegions.add((distance << 16) | regionId);
                this.regionsInFrustum.set(regionId);
            } else if (this.regionsInFrustum.get(regionId)) {
                // Just left the frustum: its sections were never tested this frame, so their visibility bytes
                // still say "visible" and would resurrect the region the moment it comes back
                this.sectionVisibility.clearRange((long) regionId << 8, MeshRegionStore.SECTIONS_PER_REGION);
                this.regionsInFrustum.clear(regionId);
            }
        }

        int count = this.visibleRegions.size();

        if (count == 0) {
            return 0;
        }

        long ptr = this.uploadStream.upload(this.sceneUniform, SCENE_BYTES, count * 2L);
        int index = 0;

        // Explicit primitive iterator: this runs over every visible region every frame, and the boxed form of
        // the enhanced for loop allocates an Integer per region
        for (IntIterator iterator = this.visibleRegions.iterator(); iterator.hasNext(); ) {
            LWJGL.memPutShort(ptr + ((long) (index++) << 1), (short) (iterator.nextInt() & 0xFFFF));
        }

        return count;
    }

    private void writeSceneUniform(ChunkRenderMatrices matrices, double cameraX, double cameraY, double cameraZ,
                                   int cameraSectionX, int cameraSectionY, int cameraSectionZ,
                                   int screenWidth, int screenHeight, int visibleCount) {
        // Geometry is section-relative, so the matrix carries the camera's offset within its own section and the
        // shader only ever adds a small integer section delta. Keeping the big numbers out of the shader is what
        // stops distant terrain shimmering
        float deltaX = (float) -(cameraX - (cameraSectionX << 4));
        float deltaY = (float) -(cameraY - (cameraSectionY << 4));
        float deltaZ = (float) -(cameraZ - (cameraSectionZ << 4));

        long ptr = this.uploadStream.upload(this.sceneUniform, 0, SCENE_BYTES);

        this.mvp.set(matrices.projection())
                .mul(matrices.modelView())
                .translate(deltaX, deltaY, deltaZ)
                .getToAddress(ptr);
        ptr += 16 * 4;

        LWJGL.memPutInt(ptr, cameraSectionX);
        LWJGL.memPutInt(ptr + 4, cameraSectionY);
        LWJGL.memPutInt(ptr + 8, cameraSectionZ);
        LWJGL.memPutInt(ptr + 12, 0);
        ptr += 16;

        LWJGL.memPutFloat(ptr, deltaX);
        LWJGL.memPutFloat(ptr + 4, deltaY);
        LWJGL.memPutFloat(ptr + 8, deltaZ);
        LWJGL.memPutFloat(ptr + 12, 0.0f);
        ptr += 16;

        // Fog is not wired up yet; the shaders compile without RENDER_FOG, so this stays zero
        LWJGL.memSet(ptr, 0, 16);
        ptr += 16;

        MeshRegionStore regions = this.sections.getRegions();

        // The pointer table, in the order scene.glsl declares it
        ptr = putPointer(ptr, this.sceneUniform.getDeviceAddress() + SCENE_BYTES);
        ptr = putPointer(ptr, regions.getRegionBufferAddress());
        ptr = putPointer(ptr, regions.getSectionBufferAddress());
        ptr = putPointer(ptr, this.regionVisibility.getDeviceAddress());
        ptr = putPointer(ptr, this.sectionVisibility.getDeviceAddress());
        ptr = putPointer(ptr, this.terrainCommands.getDeviceAddress());
        ptr = putPointer(ptr, this.sections.getArena().getBuffer().getDeviceAddress());
        ptr = putPointer(ptr, 0L);

        // Half-extents, because the mesh shader maps clip space to pixels with a multiply rather than a
        // multiply-and-halve per vertex
        LWJGL.memPutFloat(ptr, screenWidth / 2.0f);
        LWJGL.memPutFloat(ptr + 4, screenHeight / 2.0f);
        ptr += 8;

        LWJGL.memPutFloat(ptr, 0.0f);
        LWJGL.memPutFloat(ptr + 4, 0.0f);
        LWJGL.memPutInt(ptr + 8, 0);
        ptr += 12;

        LWJGL.memPutShort(ptr, (short) visibleCount);
        LWJGL.memPutByte(ptr + 2, (byte) (this.frameId++));
    }

    private static long putPointer(long ptr, long address) {
        LWJGL.memPutLong(ptr, address);
        return ptr + 8;
    }

    private static int align(int value, int alignment) {
        int remainder = value % alignment;
        return remainder == 0 ? value : value + (alignment - remainder);
    }
}
