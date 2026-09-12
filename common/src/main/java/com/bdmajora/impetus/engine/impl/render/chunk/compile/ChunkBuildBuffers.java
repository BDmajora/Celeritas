package com.bdmajora.impetus.engine.impl.render.chunk.compile;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import lombok.Getter;
import com.bdmajora.impetus.engine.impl.gl.util.VertexRange;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFacing;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderPassConfiguration;
import com.bdmajora.impetus.engine.impl.render.chunk.RenderSection;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.buffers.BakedChunkModelBuilder;
import com.bdmajora.impetus.engine.impl.render.chunk.compile.buffers.ChunkModelBuilder;
import com.bdmajora.impetus.engine.impl.render.chunk.data.BuiltRenderSectionData;
import com.bdmajora.impetus.engine.impl.render.chunk.data.BuiltSectionMeshParts;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.TerrainRenderPass;
import com.bdmajora.impetus.engine.impl.render.chunk.terrain.material.Material;
import com.bdmajora.impetus.engine.impl.common.util.NativeBuffer;
import com.bdmajora.impetus.engine.impl.render.chunk.sorting.TranslucentQuadAnalyzer;
import com.bdmajora.impetus.engine.impl.render.chunk.vertex.format.ChunkVertexEncoder;

import java.nio.ByteBuffer;
import java.util.*;

// a collection of temporary buffers for each worker thread, used to build chunk meshes for the given
// render passes
// makes a best-effort attempt to pick a suitable size for each scratch buffer, but will never try to
// shrink one
public final class ChunkBuildBuffers {
    private static final ModelQuadFacing[] ONLY_UNASSIGNED = new ModelQuadFacing[] { ModelQuadFacing.UNASSIGNED };
    private final Reference2ReferenceOpenHashMap<TerrainRenderPass, BakedChunkModelBuilder> builders = new Reference2ReferenceOpenHashMap<>();

    @Getter
    private final RenderPassConfiguration<?> renderPassConfiguration;

    private BuiltRenderSectionData renderData;
    private int sectionIndex;

    public ChunkBuildBuffers(RenderPassConfiguration<?> configuration) {
        this.renderPassConfiguration = configuration;
    }

    // Prepares every pass builder for a new section
    public void init(BuiltRenderSectionData renderData, int sectionIndex) {
        this.renderData = renderData;
        this.sectionIndex = sectionIndex;
        for (var builder : this.builders.values()) {
            builder.begin(renderData, sectionIndex);
        }
    }

    // The section data being filled
    public BuiltRenderSectionData getSectionContextBundle() {
        return this.renderData;
    }

    // Builder for the pass a material renders in
    public ChunkModelBuilder get(Material material) {
        return this.get(material.pass);
    }

    // One builder per pass, with the pass's vertex type
    private ChunkModelBuilder createBuilder(TerrainRenderPass pass) {
        var vertexType = this.renderPassConfiguration.getVertexTypeForPass(pass);
        var builder = new BakedChunkModelBuilder(vertexType.createEncoder(), vertexType.getVertexFormat().getStride(), pass);
        Objects.requireNonNull(renderData, "Buffers have not been started");
        builder.begin(renderData, sectionIndex);
        this.builders.put(pass, builder);
        return builder;
    }

    // Builder for a pass, created lazily
    public ChunkModelBuilder get(TerrainRenderPass pass) {
        var builder = this.builders.get(pass);
        return builder != null ? builder : createBuilder(pass);
    }

    // Passes that received geometry this section
    public Set<TerrainRenderPass> getBuilderPasses() {
        return this.builders.keySet();
    }

    // creates immutable baked chunk meshes from all non-empty scratch buffers, used after every block
    // has been rendered to pass the finished meshes over to the graphics card
    // can be called multiple times to return multiple copies
    public BuiltSectionMeshParts createMesh(TerrainRenderPass pass, float camX, float camY, float camZ) {
        var builder = this.builders.get(pass);

        if (builder == null || builder.isEmpty()) {
            return null;
        }

        List<ByteBuffer> vertexBuffers = new ArrayList<>();
        var vertexRanges = new EnumMap<ModelQuadFacing, VertexRange>(ModelQuadFacing.class);

        int vertexCount = 0;

        ModelQuadFacing[] facingsToUpload = pass.isSorted() ? ONLY_UNASSIGNED : ModelQuadFacing.VALUES;
        TranslucentQuadAnalyzer.SortState sortState = pass.isSorted() ? builder.getVertexBuffer(ModelQuadFacing.UNASSIGNED).getSortState() : null;

        for (ModelQuadFacing facing : facingsToUpload) {
            var buffer = builder.getVertexBuffer(facing);

            if (buffer.isEmpty()) {
                continue;
            }

            vertexBuffers.add(buffer.slice());
            vertexRanges.put(facing, new VertexRange(vertexCount, buffer.count()));

            vertexCount += buffer.count();
        }

        if (vertexCount == 0) {
            return null;
        }

        var vertexType = renderPassConfiguration.getVertexTypeForPass(pass);
        var primitiveType = renderPassConfiguration.getPrimitiveTypeForPass(pass);

        var mergedBuffer = new NativeBuffer(vertexCount * vertexType.getVertexFormat().getStride());
        var mergedBufferBuilder = mergedBuffer.getDirectBuffer();

        for (var buffer : vertexBuffers) {
            mergedBufferBuilder.put(buffer);
        }

        mergedBufferBuilder.flip();

        NativeBuffer mergedIndexBuffer;

        if (pass.isSorted()) {
            int numPrimitives = vertexCount / primitiveType.getVerticesPerPrimitive();
            // Generate the canonical index buffer
            mergedIndexBuffer = new NativeBuffer(primitiveType.getIndexBufferSize(numPrimitives));

            // Do the initial sort now
            primitiveType.generateSortedIndexBuffer(mergedIndexBuffer.getDirectBuffer(), numPrimitives, sortState, camX, camY, camZ);
        } else {
            mergedIndexBuffer = null;
        }

        return new BuiltSectionMeshParts(mergedBuffer, mergedIndexBuffer, TranslucentQuadAnalyzer.SortState.compacted(sortState), vertexRanges);
    }

    // Frees every builder
    public void destroy() {
        this.sectionIndex = 0;
        this.renderData = null;
        for (var builder : this.builders.values()) {
            builder.destroy();
        }
    }
}
