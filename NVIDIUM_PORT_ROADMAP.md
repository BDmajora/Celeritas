# Mesh-shader terrain backend ("Nvidium port") roadmap

A GPU-driven terrain backend for Impetus, modelled on Nvidium (Cortex/MCRcortex, LGPL-3.0). It replaces the
CPU-side visibility graph and `glMultiDrawElements*` path with an NVIDIA mesh-shader pipeline where the GPU
decides what to draw.

This is a **third** draw backend alongside the existing OpenGL multidraw path and the planned Vulkan one; it is
never the only path. `DrawBackend` already models that choice — see
`common/src/main/java/com/bdmajora/impetus/engine/impl/gpu/device/backend/DrawBackend.java`.

## Why it is fast

Nvidium's throughput comes from four things, in rough order of importance:

1. **No CPU visibility graph.** Sodium/Impetus walk the section graph on the CPU every frame, build per-pass
   render lists, and emit a multidraw command per section. Nvidium rasterises region and section bounding
   boxes into the depth buffer with `NV_representative_fragment_test`, and the fragment shader writes a
   visibility byte per section. The next frame's draw commands are produced *by the GPU* from those bytes.
2. **Bindless buffers.** Every buffer is made resident (`NV_shader_buffer_load`) and passed to shaders as a raw
   64-bit pointer inside one UBO. No binding points, no descriptor churn, no rebinding between phases.
3. **Mesh shaders instead of an index buffer.** The task shader picks which quad ranges of a section survive
   backface culling and dispatches meshlets; the mesh shader reads four packed vertices per quad straight out
   of an SSBO-style pointer and emits up to two triangles, dropping sub-pixel quads. There is no index buffer
   and no vertex attribute fetch at all.
4. **One 80 GB sparse virtual buffer** (`ARB_sparse_buffer`) for all terrain geometry, with 1 MB pages
   committed on demand. Section allocation becomes pointer arithmetic in a flat address space instead of
   per-region arenas that must be defragmented.

## Hard constraints in Impetus

| Constraint | Consequence |
|---|---|
| Root project is Forge 1.12.2, Java 8 target | Backend code lives in `:common` (Java 17 source, downgraded by jvmdowngrader), not in the MC-specific `src/` tree |
| Default runtime is **LWJGL 2** | LWJGL 2.9.3 has no `NVMeshShader`, no `NVShaderBufferLoad`, no `ARBSparseBuffer`. The backend is **unreachable** on LWJGL 2 and must degrade silently |
| LWJGL 3 only via lwjgl3ify / RetroFuturaBootstrap | The backend is opt-in and gated behind `LWJGLService` capability probes, exactly like the existing extension gates |
| All GL goes through `LWJGLService` | New entry points are added there as `default` methods that throw, overridden only in `LWJGL3Service`. `LWJGL2Service` needs no change beyond the extension enum switch |
| NVIDIA Turing+ only | `NV_mesh_shader` + `NV_representative_fragment_test` is Turing and newer. AMD/Intel/Pascal fall through to the existing path |
| Umbra (shader packs) owns the terrain program when a pack is loaded | The mesh backend and Umbra are **mutually exclusive**. Nvidium has the same restriction against Iris |

## Region geometry already matches

`RenderRegion.REGION_WIDTH/HEIGHT/LENGTH` is `8 x 4 x 8 = 256` sections — byte-for-byte the same region shape
Nvidium uses. The section-id encoding `regionId << 8 | sectionIndexInRegion` therefore ports unchanged, and so
does the 256-entry `pos2id`/`id2pos` compaction inside a region.

---

## Phases

### Phase 0 — capability plumbing *(implemented)*

* Extend `GLExtension` with `NV_mesh_shader`, `NV_shader_buffer_load`, `NV_vertex_buffer_unified_memory`,
  `NV_uniform_buffer_unified_memory`, `NV_representative_fragment_test`, `NV_bindless_multi_draw_indirect`,
  `NV_gpu_shader5`, `NV_fragment_shader_barycentric`, `ARB_sparse_buffer`.
* Add the NV/ARB enum constants the generated `GL11..GL46` wrappers cannot supply (`GLNv`).
* Add DSA-buffer, bindless-buffer, sparse-buffer and mesh-draw entry points to `LWJGLService`, implemented in
  `LWJGL3Service`, defaulted to `UnsupportedOperationException` for `LWJGL2Service`.
* `MeshShaderSupport` probes the whole set once and reports a human-readable reason when unsupported.

### Phase 1 — GL object layer *(implemented)*

* `BindlessBuffer` — immutable device-only storage, made resident, exposes its GPU address.
* `MappedUploadBuffer` — persistent, coherent-explicit client-mapped staging buffer.
* `SparseBindlessBuffer` — `GL_SPARSE_STORAGE_BIT_ARB` allocation with refcounted 1 MB page commitment.
* `MeshProgram` — compiles `TASK`/`MESH` stages, reusing `ShaderParser` and `ShaderLoader` so `#import` and
  `ShaderConstants` work the same as in the existing chunk shaders.
* `ShaderType.MESH` / `ShaderType.TASK`.

### Phase 2 — allocators and streaming *(implemented)*

* `SegmentedAllocator` — the buddy-ish free-list allocator Nvidium uses to hand out quad ranges; 32-bit
  addresses, coalescing free, `expand()` for append-in-place uploads.
* `IdAllocator` — dense id reuse with a shrinking high-water mark.
* `UploadStream` — ring of persistent-mapped staging allocations, flushed and copied once per frame, freed
  behind a `GlFence` so the CPU never writes memory the GPU is still reading.
* `QuadArena` — quad-granularity allocation on top of the sparse (or fallback dense) geometry buffer.

### Phase 3 — section and region stores *(implemented)*

* `MeshRegionStore` — per-region 256-slot section metadata held in native memory, uploaded whole when dirty.
  Packs the region header (position, extent, section count) into two `uint64`s.
* `MeshSectionStore` — maps section keys to region slots and geometry allocations; writes the 32-byte section
  header (chunk position, bounding box, quad base address, per-facing quad ranges).

### Phase 4 — the render pipeline *(implemented, untested on hardware)*

Per frame, in order:

1. CPU: frustum-cull **regions only** (a few hundred boxes, not tens of thousands of sections), sort them
   front-to-back, and write the visible region id list plus the scene UBO (which is mostly a table of GPU
   pointers).
2. GPU: `regionRasterizer` draws one box per visible region with depth test on, colour and depth writes off,
   and `GL_REPRESENTATIVE_FRAGMENT_TEST_NV` on. The fragment shader writes `regionVisibility[i] = 1`.
3. GPU: `sectionRasterizer` — a task shader reads `regionVisibility` and emits one meshlet per section in
   surviving regions; the mesh shader draws each section's box; the fragment shader writes
   `sectionVisibility[i]`. The same task shader writes the *next* frame's indirect draw commands.
4. GPU: `terrainRasterizer` — `glMultiDrawMeshTasksIndirectNV` over the command buffer produced last frame.
   The task shader picks the quad ranges whose facing points at the camera, the mesh shader emits triangles.

Note the one-frame lag: step 4 uses the previous frame's commands. That is Nvidium's design, and it is why
`prevRegionCount` is tracked.

### Phase 5 — build-pipeline integration *(not implemented)*

The remaining work, and the only part that touches existing code:

* **Vertex format.** `MeshChunkVertex` (16 bytes, implemented) must be selected by
  `ImpetusWorldRenderer.chooseVertexType()` when the backend is active, so chunk builds already produce
  mesh-format geometry and no repacking is needed on the render thread.
* **Repackaging on the build thread.** `BuiltSectionMeshParts` gives a vertex buffer plus
  `Map<ModelQuadFacing, VertexRange>`. Convert to `(quadCount, geometry, short[8] perFacingQuadCounts,
  min, size)` — Nvidium's `RepackagedSectionOutput` — inside `ChunkBuilderMeshingTask`, not in
  `uploadChunks`, or the 1% lows suffer.
* **Bounding box.** `min`/`size` are in 1/16-block units over the section, derived from the decoded vertex
  positions. Needed by the section rasteriser's box.
* **Hook `RenderSectionManager`.** When the backend is active: skip `RenderRegionManager.uploadMeshes`, skip
  the occlusion graph walk entirely, route `ChunkBuildOutput` to `MeshSectionStore.upload`, and route
  `renderLayer` for the solid passes to `MeshRenderPipeline.renderFrame`.
* **Chunk build ordering.** Nvidium replaces Sodium's graph-driven build queue with
  `AsyncOcclusionTracker`, a background thread that walks the visibility graph purely to decide *what to
  build* (never what to draw). Impetus already has `chunk/async` and `AsyncOcclusionMode` — reuse that rather
  than porting Nvidium's tracker.

### Phase 6 — parity features *(not implemented)*

In the order they are worth doing:

1. **Translucency.** Nvidium renders translucent terrain from a second command buffer written in reverse
   region order, and sorts quads inside a region with a compute shader (`region_section_sorter.comp`,
   a bitonic network). Impetus already has a much better CPU translucency sorter
   (`chunk/sorting/`) — decide whether to port the GPU sorter or keep sections translucent-sorted on the CPU
   and only reorder regions on the GPU.
2. **Temporal coherence.** `sectionVisibility` keeps 8 frames of history per section as a shifted byte; the
   temporal task shader redraws sections that were visible recently but failed this frame's test, which
   removes the one-frame popping. Cheap to add once Phase 4 is verified.
3. **Fog.** `RENDER_FOG` specialisation, computing a per-vertex fog lerp in the mesh shader. Impetus's fog
   comes from `ChunkShaderFogComponent.FOG_SERVICE`, so the uniform block needs the 1.12.2 fog mode rather
   than `RenderSystem.getShaderFogShape()`.
4. **Memory pressure handling.** `removeARegion()` — when the geometry arena runs out, evict the region least
   likely to be seen, using a GPU readback of `regionVisibility` (`RegionVisibilityTracker` +
   `DownloadTaskStream`).
5. **Statistics** — region/section/quad counters written by the shaders and read back asynchronously, for
   the F3 overlay.
6. **Region transformations** — Nvidium's `NvidiumAPI` lets other mods apply a per-region affine transform.
   Skip unless something in the Impetus ecosystem wants it.

### Phase 7 — hardening *(not implemented)*

* Linux driver quirk: Nvidium disables the sparse addressable buffer on Linux
  (`SUPPORTS_PERSISTENT_SPARSE_ADDRESSABLE_BUFFER = false`) because page commitment is unreliable there and
  falls back to a fixed-size dense buffer. `MeshShaderSupport` already exposes this; wire it to `OsKind`.
* Interaction with `Workarounds` / `StartupChecks` — refuse to enable on driver versions known to miscompile
  mesh shaders.
* Shadow pass: Umbra's shadow pass re-drives `renderLayer` from the sun. The mesh backend maintains one
  visibility buffer, so a second pass needs its own set — or the backend stays disabled under Umbra.

---

## What is in the tree now

```
common/src/lwjglCommon/java/com/bdmajora/impetus/lwjgl/
  GLExtension.java                     (extended)
  GLNv.java                            NV/ARB enum constants
  LWJGLService.java                    (extended, all new methods default-throw)
common/src/lwjgl3/java/com/bdmajora/impetus/lwjgl/lwjgl3/
  LWJGL3Service.java                   (extended)
common/src/main/java/com/bdmajora/impetus/engine/impl/render/mesh/
  MeshShaderSupport.java               capability probe
  MeshRenderPipeline.java              the whole frame
  gl/DeviceBuffer.java
  gl/BindlessBuffer.java
  gl/MappedUploadBuffer.java
  gl/SparseBindlessBuffer.java
  gl/MeshProgram.java
  util/SegmentedAllocator.java
  util/IdAllocator.java
  util/UploadStream.java
  util/QuadArena.java
  region/MeshRegionStore.java
  region/MeshSectionStore.java
  region/SectionGeometry.java          the repackaged build output
  renderers/RegionRasterizer.java
  renderers/SectionRasterizer.java
  renderers/TerrainRasterizer.java
common/src/main/java/.../chunk/vertex/format/impl/MeshChunkVertex.java
common/src/main/resources/assets/impetus/shaders/mesh/
  scene.glsl  vertex_format.glsl
  region_raster.mesh  region_raster.frag
  section_raster.task section_raster.mesh section_raster.frag
  terrain.task        terrain.mesh        terrain.frag
```

Nothing above is reachable at runtime yet — `MeshShaderSupport.isSupported()` is the only entry point anything
else calls, and Phase 5 is what wires it in. `:common` and the root Forge project both compile.

`SegmentedAllocator` is the one piece that can be verified without a GPU, and it was: 4000 seeds x 3000 random
alloc/free/expand operations, asserting live allocations never overlap and that the high-water mark returns to
zero once everything is freed.

## Credits

The pipeline design, the shader structure, and the region/section metadata packing are Nvidium's, by Cortex
(MCRcortex), used under the LGPL-3.0 — the same licence Impetus is under.
