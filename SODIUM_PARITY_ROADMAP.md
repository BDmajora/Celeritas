# Impetus ↔ Upstream Sodium — Feature Parity Roadmap & Spec Sheet

> Comparison target: **upstream CaffeineMC Sodium 0.9.1-beta** (MC 1.21.x, package
> `net.caffeinemc.mods.sodium`) vs **Impetus** (Stonecutter multi-version fork of
> **Embeddium** + **Oculus 1.7**, engine package `com.bdmajora.impetus.engine`, wrapper
> `com.bdmajora.impetus`). Impetus's engine descends from the **last FOSS Sodium (~0.5.x)**
> line; upstream Sodium has since rewritten large portions. This document maps every
> upstream subsystem, marks what Impetus already has, and prioritizes the gaps.
>
> **Important scoping note:** Impetus targets *old* Minecraft versions (1.7.10 / 1.12.2 …)
> and adds a whole capability upstream Sodium does not have (**built-in shaders via Oculus/Iris**).
> A meaningful "parity" list therefore excludes upstream code that is purely 1.21-version-plumbing,
> and treats Impetus's shader stack as a *net addition*, not a deficit.

---

## 1. Mod identities

| | Upstream Sodium | Impetus |
|---|---|---|
| Base | CaffeineMC Sodium (current) | Embeddium (FOSS Sodium ~0.5) + Oculus 1.7 |
| Version line | 0.9.1-beta | 2.4 (project_base_version) |
| MC targets | 1.21.x (Fabric + NeoForge) | 1.7.10 / 1.12.2 … via Stonecutter (Forge/legacy) |
| Engine pkg | `net.caffeinemc.mods.sodium` | `com.bdmajora.impetus.engine.impl` + `com.bdmajora.impetus` |
| Shaders | **None** (Iris is a separate mod) | **Bundled** (`com.bdmajora.impetus.shaders`, Oculus/Iris port) |
| Config API for other mods | Yes (public `api.config` builder) | No public config builder API |

---

## 2. Upstream Sodium — full subsystem inventory

Grouped by package; ✅ = Impetus has an equivalent, ⚠️ = partial/older variant, ❌ = missing.

### 2.1 Terrain render engine (`client/render/chunk`)
| Subsystem | Upstream detail | Impetus | Notes |
|---|---|---|---|
| Region-based batching | `region/RenderRegion(Manager)` | ✅ | Present in Embeddium engine |
| Section storage / meshing | `RenderSection(Manager)`, `data/*`, `storage/*` | ✅ | |
| Async chunk build executor | `compile/executor/ChunkBuilder`, job queue/typed jobs | ✅ | |
| **Octree/forest traversal** | `tree/*` (14 cls: BaseForest, Traversable(Bi/Multi)Forest, RemovableTree…) | ❌ | **Not present.** Impetus uses older `occlusion/OcclusionNode` + `SectionVisibilityBuilder` graph culler |
| **Async cull tasking** | `async/AsyncRenderTask`, `CullTask`, `CullResult` | ⚠️ | Impetus has `occlusion/AsyncOcclusionMode` (option) but not the full task/result pipeline |
| Occlusion culler | `occlusion/OcclusionCuller`, `SectionTree`, `RayOcclusionSectionTree`, `DirectionalVisGraph`, `CullType`, `GraphDirection*` | ⚠️ | Impetus: `OcclusionCuller`, `OcclusionNode`, `VisibilityEncoding`, `GraphDirection*` — older BFS, no SectionTree/ray variant |
| **Advanced translucency sorting** | `translucent_sorting/*` — full GFNI + BSP dynamic topological sort: `bsp_tree/*` (13), `data/*` (18: Dynamic/Static/Topo/BSP sorters), `trigger/*` (GFNI triggers, NormalPlanes), `quad/*`, `QuadSplittingMode` | ⚠️ | Impetus now has `TranslucentQuadAnalyzer`, GFNI-style plane-crossing triggers (`TranslucencyTriggerIndex`), and a BSP ordering pass for dynamic sorts. Still missing upstream's full quad splitting / topo sorter package. |
| **Adaptive build/upload estimation** | `compile/estimation/*` (13 cls: JobDurationEstimator, MeshTaskSizeEstimator, Upload/Mesh budgets, Exp-decay linear estimators) | ⚠️ | Impetus has `metrics/RenderSectionMetricsTracker` + `compile/executor/ChunkJobMetricsTracker` only — no predictive resource budgeting |
| Block/fluid render pipeline | `compile/pipeline/BlockRenderer`, `DefaultFluidRenderer`, `BlockRenderCache`, `ShapeComparisonCache` | ✅(version-specific) | Impetus implements per-MC-version (1.12.2 has its own `VintageBlockRenderer`) |
| Deferred chunk updates | `DeferMode`, `ChunkUpdateTypes`, deferred task lists | ✅ | Impetus: `ChunkUpdateType`, `alwaysDeferChunkUpdates` option |
| Multidraw / indirect | `SharedQuadIndexBuffer`, indexed multidraw | ✅ | Impetus adds `multidraw/{Direct,Indirect}MultiDrawEmitter` |
| Compact vertex format | `vertex/format/impl/CompactChunkVertex`, `ChunkVertexEncoder` | ✅ | `useCompactVertexFormat` option |
| Fog | `ChunkShaderFogComponent`, fog modes | ✅ | Impetus `fog/FogService`, `useFogOcclusion` |

### 2.2 GPU / device layer (`client/gpu`)
| Subsystem | Upstream | Impetus |
|---|---|---|
| GL buffer arena / segments | `arena/GlBufferArena`, segments, pending uploads | ✅ |
| Staging buffers | `MappedStagingBuffer`, `MojangStagingBuffer` | ✅ (`useAdvancedStagingBuffers`) |
| **Device abstraction w/ Vulkan backend** | `gpu/device/{backend,batch,context}` incl. `VKDrawContext`, `VKMultiDrawContext`, `VKIndirectDrawBatch`, `GPULimits`, `EnumBitField` | ❌ | Impetus is GL-only; no device/backend abstraction layer |

### 2.3 Fabric Rendering API integration (`render/frapi`, `frapi` module)
| Subsystem | Upstream | Impetus |
|---|---|---|
| **FRAPI / Indigo mesh pipeline** | Full `frapi` module + `client/render/frapi/*` (14 cls) — renders FRAPI/Indigo modded models inside Sodium | ❌ | No `frapi` module. (Less relevant pre-1.15 Forge, but a mod-compat gap on newer targets) |

### 2.4 Immediate-mode / model rendering (`render/immediate`, `render/model`)
| Subsystem | Upstream | Impetus |
|---|---|---|
| Immediate model encoder (entities, items) | `render/immediate/model/*` | ⚠️ version-specific |
| Vertex format registry & serializers | `api/vertex/*`, `render/vertex/serializers` | ✅ (has `render/vertex/serializers`) |
| Entity/particle/GUI batch mixins | `mixin/features/render/{entity,particle,gui,immediate}` | ✅ mostly |

### 2.5 Configuration & GUI (`client/config`, `client/gui`)
| Subsystem | Upstream | Impetus |
|---|---|---|
| **Public Config API** | `api/config/**` + `client/config/{structure,builder,value}` (40+ cls) — other mods register options programmatically | ❌ | Impetus exposes `com.bdmajora.impetus.api.options.*` event-bus hooks (`OptionGUIConstructionEvent`) but not the full builder API |
| **Searchable options menu** | `config/search/BigramSearchIndex`, `SearchQuerySession` | ✅ | Impetus has live localized option-name/tooltip search in the GUI (framework-native, not the upstream bigram index classes) |
| Tabbed / framework GUI | `gui/screen`, widgets | ✅ | Impetus has its own `gui/frame/tab/*`, `framework/*`, `widgets/*` |
| **Theming / color themes** | `gui/{ColorTheme,ButtonTheme,Colors}`, `api/config/structure/ColorThemeBuilder` | ⚠️ | Impetus has `DefaultColors` plus per-mod accent lookup; no public color theme builder API |
| Config corruption recovery | `gui/screen/ConfigCorruptedScreen` | ❓ verify |
| FPS percentile / debug overlay entries | `SodiumDebugEntry`, `SodiumFpsPercentilesEntry` | ⚠️ | Impetus has metrics trackers; verify overlay |

### 2.6 Driver compatibility & platform (`client/compatibility`, `client/platform`, `src/boot`)
| Subsystem | Upstream | Impetus |
|---|---|---|
| **GPU vendor workarounds** | `compatibility/workarounds/{amd,intel,nvidia}` + `Workarounds` | ⚠️ | `Workarounds` registry landed for NVIDIA threaded optimization, Intel no-error safety, and overlay injection detection. |
| **Graphics adapter probing** | `environment/probe/GraphicsAdapterProbe`, D3DKMT (`platform/windows/api/d3dkmt/*`, 7 cls) | ⚠️ | Best-effort OS probe exists: Linux `/sys/class/drm`, Windows PowerShell CIM. No native D3DKMT binding yet. |
| **Win32 platform layer** | `platform/windows/api/*` (User32, Gdi32, Kernel32, version, msgbox) — 22 cls | ❌ | Swing fallback is used instead of native Win32 APIs. |
| **Native crash MessageBox** | `platform/MessageBox`, boot-time `LaunchWarn`, `desktop/*` browser handlers | ⚠️ | JVM-level crash dialog installed via `StartupChecks`; not native Win32. |
| **Pre/Post-launch bug checks** | `compatibility/checks/{Pre,Post}LaunchChecks`, `ModuleScanner`, `GraphicsDriverChecks` | ⚠️ | Async startup checks cover GL context info, adapter probe, Pojav, outdated NVIDIA driver, Intel no-error risk, and RTSS overlay warnings. |
| No-error GL context | GL_KHR_no_error path | ✅ (`useNoErrorGLContext`) |

### 2.7 Resource / content checks (`client/checks`)
| Subsystem | Upstream | Impetus |
|---|---|---|
| **Resource-pack scanner** | `checks/ResourcePackScanner`, `SodiumResourcePackMetadata` — warns about incompatible core-shader packs | ❌ | Not present (partly moot: Impetus *is* a shader host) |

### 2.8 In-game console / notifications (`client/console`, `gui/console`)
| Subsystem | Upstream | Impetus |
|---|---|---|
| **Toast/console message system** | `console/{Console,ConsoleSink,message/*}`, `gui/console/ConsoleRenderer` | ⚠️ | Impetus has `gui/console/message/MessageLevel` scaffold + `NotificationSettings` option — verify full renderer |

### 2.9 Install analytics
| Subsystem | Upstream | Impetus |
|---|---|---|
| Install fingerprint / donation prompt | `data/fingerprint/{FingerprintMeasure,HashedFingerprint}` | ✅ | Impetus keeps donation-prompt options (`forceDisableDonationPrompts`, `hasSeenDonationPrompt`) |

### 2.10 World / lighting / color
| Subsystem | Upstream | Impetus |
|---|---|---|
| Cloned chunk sections / biome cache | `world/cloned/*`, `world/biome/*` | ✅ |
| Smooth + flat lighting | `model/light/{smooth,flat,data}` | ✅ |
| Biome blend | configurable radius | ✅ (`legacyBiomeBlendRadius`) |
| Color providers / blenders | `model/color/*`, `model/quad/blender` | ✅ |

---

## 3. Impetus — features NOT in upstream Sodium (net additions)

These are Impetus advantages; keep and protect them during any parity work:

1. **Bundled shader pipeline** — `com.bdmajora.impetus.shaders.ImpetusShaders`, Oculus/Iris 1.7 port, Complementary/LIGHT support, shadow pass, colored voxel lighting, `at_midBlock` voxelization (see `forge122/IRIS_PORT_SPEC.md`). Upstream Sodium has **no** shader support.
2. **Legacy MC support** — 1.7.10 / 1.12.2 targets via Stonecutter; upstream is 1.21-only.
3. **Forge/legacy loader support** — via unimined/MDG plugins.
4. **Render-pass optimization & consolidation** — `useRenderPassOptimization`, `useRenderPassConsolidation` (Embeddium-specific).
5. **Faster clouds** — `useFasterClouds`.
6. **Chunk fade-in duration control**, **memory tracing**, **CPU render-ahead limit** options.
7. **Event-bus option API** — `com.bdmajora.impetus.api.eventbus` + `OptionGUIConstructionEvent` for downstream mods.
8. **Published `impetus-common` Maven artifact** — MC-abstracted core for reuse.

---

## 4. Gap summary (what upstream has that Impetus lacks)

**Engine-level (rendering correctness / perf):**
- G1. Full upstream GFNI + BSP **dynamic translucency sorting** *(partial: trigger index + BSP order landed; quad splitting/topo sort remain)*
- G2. `tree/` **octree/forest traversal** + `SectionTree`/`RayOcclusionSectionTree` occlusion
- G3. Full **async cull-task pipeline** (`async/*`)
- G4. **Adaptive mesh/upload estimation & budgeting** (`compile/estimation/*`)
- G5. **Vulkan-capable GPU device abstraction** (`gpu/device/*`)

**Compatibility / robustness:**
- G6. **GPU driver workarounds** (NVIDIA/AMD/Intel) + native adapter probing (partial: standalone probe/workaround registry landed; no D3DKMT)
- G7. **Pre/Post-launch bug checks**, Win32 platform layer, native crash MessageBox (partial: async checks + Swing crash dialog landed)
- G8. **Resource-pack compatibility scanner**

**UX / API:**
- G9. **Public Config builder API** (`api/config/**`) for third-party mods
- G10. **Searchable** options menu
- G11. Per-mod **color theming**
- G12. Full **in-game console/toast** renderer
- G13. **FRAPI/Indigo** modded-model pipeline (matters only on 1.15+ targets)

---

## 5. Prioritized roadmap

### Tier 1 — high value, self-contained, version-agnostic
| ID | Item | Why | Effort | Risk |
|---|---|---|---|---|
| R1 | **Port GFNI + BSP translucency sorting** (`translucent_sorting/*`) | Partial: GFNI-style trigger planes + BSP dynamic ordering landed; upstream quad splitting/topo sorter still pending | XL | Med |
| R2 | **GPU driver workarounds + adapter probe** (`compatibility/workarounds`, `environment/probe`) | Partial: workaround registry, Linux/Windows adapter probe, Intel no-error risk, NVIDIA old-driver warning, RTSS detection landed | M | Low |
| R3 | **Pre/Post-launch bug checks + crash MessageBox** | Partial: async startup checks + guarded crash dialog landed; native Win32 MessageBox/module scanner still pending | M | Low |

### Tier 2 — engine modernization
| ID | Item | Why | Effort | Risk |
|---|---|---|---|---|
| R4 | **Octree/forest traversal + SectionTree occlusion** (`tree/*`, `occlusion/SectionTree`) | Faster culling, fewer overdraws at high render distance | XL | High — replaces core culler; must not regress the shader `at_midBlock` voxelization fix |
| R5 | **Async cull-task pipeline** (`async/*`) | Removes main-thread culling stalls | L | Med — builds on R4 |
| R6 | **Adaptive build/upload estimation** (`compile/estimation/*`) | Smoother frame pacing, less chunk-load stutter | L | Low-Med |

### Tier 3 — UX / API / compat
| ID | Item | Why | Effort | Risk |
|---|---|---|---|---|
| R7 | **Public Config builder API** (`api/config/**`) | Lets other mods add options; ecosystem parity | L | Low — Impetus already has an event-bus seam to build on |
| R8 | **Searchable options menu** (`config/search`) | ✅ Done via framework-native live search | S | Low |
| R9 | **Resource-pack scanner** | Warn on incompatible packs (adapt for shader coexistence) | S | Low |
| R10 | **In-game console/toast renderer** | Surfaces the notifications Impetus already has settings for | S | Low |
| R11 | Per-mod **color theming** | Accent-color pass landed; full public theme builder still absent | S | Low |

### Tier 4 — only if targeting modern MC
| ID | Item | Why | Effort |
|---|---|---|---|
| R12 | **FRAPI/Indigo pipeline + `frapi` module** | Modded-model compat on 1.15+; irrelevant to 1.7.10/1.12.2 | XL |
| R13 | **Vulkan device abstraction** (`gpu/device/*`) | Future backend; upstream itself is still incubating this | XL |

---

## 6. Recommended sequencing

1. **R2 + R3** first (low risk, high stability payoff, no engine coupling).
2. **R1 (translucency sorting)** — biggest quality win; do before R4 so the culler rewrite isn't blamed for sort artifacts. Coordinate with the shader path (per-quad normals already exist).
3. **R7 + R8 + R10** as a parallel UX track (independent of engine work).
4. **R4 → R5 → R6** as one guarded engine-modernization epic, with the Iris `at_midBlock`/voxelization pins from the current session as regression gates.
5. Defer **R12/R13** unless newer MC targets are added.

---

## 7. Verification checklist before acting on any item

- [ ] Confirm the subsystem isn't already partially ported under `com.bdmajora.impetus.engine.impl` with a renamed class (this doc inferred from filenames; grep both trees for the concept, not the class name).
- [ ] Confirm relevance to Impetus's actual MC targets (skip 1.21-only plumbing).
- [ ] Confirm no conflict with the bundled shader stack (esp. R1/R4 vs voxelization).
