# Impetus Iris ↔ Upstream Iris — Feature Parity Roadmap & Spec Sheet

> Comparison target: **upstream IrisShaders/Iris (trunk, MC 1.21)** — `net.irisshaders.iris`, ~611 source
> files — vs **Impetus's bundled Iris port** (`com.bdmajora.impetus.iris`, 109 files, in the `forge122` module,
> descended from **Iris/Oculus 1.7** for **MC 1.12.2**).
>
> **Scoping note:** upstream Iris targets the 1.21 render engine (RenderType/`MultiBufferSource` architecture,
> Sodium 0.6 terrain, `SpriteContents`). A chunk of upstream code is pure 1.21-render-plumbing that has no
> meaning on 1.12.2's fixed-function-adjacent pipeline (most of `layer/`, parts of `compat/`, `pbr/texture` atlas
> internals). This document flags those as *version-specific* rather than gaps. It also builds on
> [forge122/IRIS_PORT_SPEC.md](forge122/IRIS_PORT_SPEC.md), which documents the port's internal architecture.

---

## 1. What the Impetus port already does (verified present & wired)

| Subsystem | Impetus location | Notes |
|---|---|---|
| Shaderpack loading (.zip/folder) | `shaderpack/ShaderPackLoader`, `ShaderPack` | ✅ |
| `shaders.properties` parsing | `shaderpack/ShaderProperties`, `preprocessor/PropertiesPreprocessor` | ✅ |
| GLSL `#include` graph | `shaderpack/include/{IncludeProcessor,AbsolutePackPath}` | ✅ |
| Program set (gbuffers/composite/deferred/shadow/final) | `shaderpack/{ProgramSet,ProgramSource}`, `loading/{ProgramId,ProgramArrayId}` | ✅ 28 programs confirmed loading in log |
| Shader options (bool/string, profiles) | `shaderpack/option/**` (16 files) | ✅ near-complete |
| ID/material map (`block.properties`) | `shaderpack/materialmap/**`, `material/BlockMaterialMapping` | ✅ |
| GLSL transformation | `terrain/{ModernPackTransformer,ImpetusTerrainTransformer}` + `glsl-transformation-lib` | ⚠️ different approach (external lib) vs upstream `pipeline/transform` |
| Program compile/link | `gl/program/**`, `gl/shader/**` | ✅ |
| Render targets (colortex ping-pong, depthtex, noise) | `targets/**`, `gl/framebuffer/IrisFramebuffer` | ✅ |
| Builtin uniforms | `uniforms/{Camera,Celestial,Common,Matrix,SystemTime,EyeBrightness}` | ✅ |
| Shadow pass | `pipeline/IrisShadowRenderer`, `StubShadowMap` | ✅ renders (per session history) |
| Terrain vertex extension | `vertices/{IrisChunkVertexType,IrisVertexAttributes,NormalHelper}` + `at_midBlock` | ✅ (the flicker fix) |
| Custom textures / images | `pipeline/{CustomTextureManager,CustomImageManager}`, `shaderpack/texture/**` | ✅ basic |
| Compute shaders | `gl/shader` (compute path), `pipeline` dispatch | ⚠️ basic (see gaps) |
| Shader select + config GUI | `gui/modern/{ShaderPackSelectScreen,ShaderPackConfigScreen,IrisOptionPages}` | ✅ (the tabs you saw) |

---

## 2. Gap analysis (upstream has, Impetus lacks) — file counts are upstream

Grouped by value. ✅ present · ⚠️ partial · ❌ missing.

### 2.1 Visual-fidelity gaps (shaders render wrong/flat without these)
| ID | Feature | Upstream | Impetus | Impact |
|---|---|---|---|---|
| **G1** ✅ | **PBR / LabPBR** — normal + specular map loading, `PBRTextureManager`, `PBRAtlasTexture`, `LabPBRTextureFormat`, PBR-aware mipmaps | `pbr/**` (28) | ❌ 0 | **Highest.** Any pack using normal/specular maps (nearly all modern packs) renders flat — no bump, no reflections/roughness/metalness. |
| **G2** ✅ | **Custom uniforms** — user `uniform.*` / `variable.*` expressions from `shaders.properties`, parsed & evaluated per frame | `uniforms/custom/**` (13) | ❌ 0 (`parsing/` present, not wired to custom uniforms) | **High.** Packs defining custom uniforms fall back to defaults → broken effects (waving, TAA params, custom toggles). |
| **G3** ✅ | **Colorspace conversion** — sRGB↔DCI-P3/Display-P3/Rec.2020 output conversion pass | `pathways/colorspace/**` (4) | ❌ 0 (renders sRGB only) | Medium. Wrong colors on wide-gamut monitors / packs expecting a target color space. |
| **G4** ✅ | **CenterDepthSampler** — `centerDepthSmooth` uniform (smoothed center-screen depth) | `pathways/CenterDepthSampler` | ❌ (1 stray ref) | Medium. Depth-of-field & auto-exposure/eye-adaptation shaders break. Already flagged "next" in project memory. |
| **G5** ✅ | **Colortex mipmap generation** — `mipmapEnabled` per buffer for composite sampling | in `targets`/pipeline | ⚠️ flagged missing in memory | Medium. Bloom/blur passes that sample mipmapped colortex look wrong. |
| **G6** ✅(vanilla) | **Horizon renderer** — void/sky horizon geometry for `gbuffers_skybasic` | `pathways/HorizonRenderer` | ❌ | Low-Med. Sky/void edge artifacts under some packs. |
| **G7** ✅ | **Hand rendering in pipeline** — first-person hand routed through `gbuffers_hand` (solid/translucent) | `pathways/HandRenderer` | ❌ | Med. Held items/hand may render unshaded or z-fight under shaders. |
| **G8** ✅(entities path) | **Lightning handler** — shader-correct lightning bolt rendering | `pathways/LightningHandler` | ❌ | Low. |

### 2.2 Modern-pack capability gaps
| ID | Feature | Upstream | Impetus | Impact |
|---|---|---|---|---|
| **G9** ✅ | **SSBOs** — shader storage buffers (`shaderStorageBufferObject`) | `gl/buffer/**` (4) | ❌ ~0 | Med. Newer packs (compute-heavy) require SSBOs. |
| **G10** ✅ | **Image load/store** — `gl/image` bindings, `ImageClearPass`, `ImageLimits` for compute | `gl/image/**` (5) | ⚠️ minimal (4 refs) | Med. Compute passes writing to images are limited. |
| **G11** ✅ | **Feature flags** — pack `iris.features.required/optional` gating with user-facing "unsupported" messaging | `features/FeatureFlags` + gui | ⚠️ 2 refs | Low-Med. Packs that declare required features aren't validated. |
| **G12** ✅ | **Full compute dispatch** — indirect dispatch, barriers, workgroup sizing parity | `gl/` + pipeline (6) | ⚠️ basic (14 refs) | Med for compute packs. |

### 2.3 Version-specific (largely N/A on 1.12.2 — document, don't chase)
| ID | Feature | Upstream | Why lower priority on 1.12.2 |
|---|---|---|---|
| V1 | `layer/**` RenderType state-shards (10) | 1.15+ `RenderType`/`MultiBufferSource` arch | 1.12.2 uses the fixed-function terrain override Impetus already has; not portable as-is |
| V2 | `compat/dh` Distant Horizons (part of 35) | DH LOD terrain under shaders | Only relevant if DH is used on 1.12.2 (rare); defer |
| V3 | `compat/sodium` 0.6 terrain vertex integ (part of 35) | Impetus already has its own terrain vertex path (`vertices/`) | Covered by the port's own integration |
| V4 | `pbr/texture` atlas-extension internals (part of 28) | `SpriteContents`/atlas API is 1.21-specific | The *concept* (G1) matters; the exact classes don't port |

---

## 3. Prioritized roadmap

### Tier 1 — biggest visual wins, self-contained
| ID | Item | Effort | Risk | Notes |
|---|---|---|---|---|
| **R1** | **PBR / LabPBR** (G1): load `_n`/`_s` maps, build normal+specular atlases parallel to the block atlas, bind as `normals`/`specular` samplers, expose `MC_NORMAL_MAP`/`MC_SPECULAR_MAP` defines | XL | Med | Coordinate with the existing `TextureAtlasMixin`; must not disturb the base-atlas filtering just added. Adapt LabPBR decoding; skip upstream's `SpriteContents` atlas classes (V4). |
| **R2** | **Custom uniforms** (G2): wire `parsing/` into a `CustomUniforms` holder — parse `uniform.<type> <name> = <expr>`, evaluate per update-frequency, upload | L | Low | Port the expression evaluator + cached-uniform types; self-contained, no render-path risk. |
| **R4** | **CenterDepthSampler** (G4) + **colortex mipmaps** (G5) | M | Low-Med | Both feed composite passes; do together. Memory already flags these as next. |

### Tier 2 — correctness / broader pack support
| ID | Item | Effort | Risk |
|---|---|---|---|
| **R3** | **Colorspace conversion** (G3): final-stage sRGB→target conversion (fragment path first; compute path optional) | M | Low |
| **R7** | **Hand rendering** (G7) through `gbuffers_hand` | M | Med (touches entity/hand render hook) |
| **R9** | **SSBOs** (G9) + **image load/store** (G10) completion | L-M | Med |
| **R6** | **Horizon renderer** (G6) | S | Low |

### Tier 3 — polish / niche
| ID | Item | Effort |
|---|---|---|
| R11 | Feature-flag validation + "unsupported pack" messaging (G11) | S |
| R8 | Lightning handler (G8) | S |
| R12 | Compute dispatch parity — indirect/barriers (G12) | M |

### Deferred (version-specific)
V1 (RenderType layers), V2 (Distant Horizons) — only if those mods/architectures become targets.

---

## 4. Recommended sequencing

1. **R2 (custom uniforms)** first — low risk, self-contained, unblocks many packs' custom effects, no render-path coupling.
2. **R1 (PBR)** — the headline visual feature; do after R2 so custom-uniform-driven PBR toggles work. Guard against the block-atlas filtering/`at_midBlock` work.
3. **R4 (centerDepth + colortex mipmaps)** and **R3 (colorspace)** — composite-stage correctness, done as a pair.
4. **R7/R6/R9** as capability follow-ups.
5. Validate each against a normal-mapped pack (e.g. Complementary + PBR resource pack) and confirm no regression of the hard-won water/shadow/`at_midBlock` stability.

## 5. Verification checklist before acting

- [ ] Confirm the concept isn't already partially present under a different name (grep `com.bdmajora.impetus.iris` for the concept, not the upstream class name — e.g. shadows/samplers *are* present despite 0 files in the upstream-named dir).
- [ ] Confirm relevance to 1.12.2 (skip `layer/` RenderType shards and 1.21 atlas internals).
- [ ] Every change goes through the `com.bdmajora.impetus.lwjgl` GL abstraction, never raw `org.lwjgl.opengl.GL*`.
- [ ] No regression to water rendering, shadow pass, or the `at_midBlock` voxelization fix.

---

## 6. Implementation status (2026-07-16)

All Tier 1–3 items landed (original implementations; compile-verified):

- **R2 Custom uniforms** — `uniforms/custom/` (original expression parser: arithmetic/comparison/boolean/ternary,
  GLSL math set, `smooth()` with per-call state, vec2/3/4); `UniformCollector` interface captures the full builtin
  uniform surface for expression inputs; wired into all four program-compile paths + per-frame update.
- **R4 centerDepthSmooth** — `pipeline/CenterDepthSampler` (1-px depth read from a depth-only FBO on depthtex0,
  exponential half-life smoothing, `centerDepthHalflife` const respected). Colortex mipmaps were already present.
- **R1 PBR/LabPBR** — `pbr/PBRAtlasManager` stitches `_n`/`_s` companions into normals/specular atlases mirroring
  the block-atlas layout (rebuilt per resource reload, animated sprites contribute frame 0); bound on gbuffer
  units 2/3 over the neutral defaults; `MC_NORMAL_MAP`/`MC_SPECULAR_MAP` defines; `pbr/TextureFormatLoader` reads
  `optifine/texture.properties` → `MC_TEXTURE_FORMAT_*` defines. Follow-up: PBR animation frames.
- **R3 Colorspace** — `pipeline/ColorSpaceConverter` (sRGB→DCI-P3/Display-P3/Rec.2020/AdobeRGB fragment pass on
  the presentation target), persisted in `optionsshaders.txt`, live cycler in the Iris Settings tab.
- **R7 Hand** — `gbuffers_hand` bound around vanilla's post-composite `renderHand` (classic OptiFine 1.12 order);
  renderStage 16 exposed; no-op without a hand program.
- **R9 SSBOs** — `gl/buffer/ShaderStorageBufferHolder`: `bufferObject.<index>` directives (fixed + screen-relative),
  zero-filled, bound per frame, resize-aware. Image load/store clears+barriers already existed.
- **R11 Feature flags** — `features/FeatureFlags` (honest usable set), `IRIS_FEATURE_*` defines, required-flag
  validation with in-game notification.
- **R12 Compute parity** — `workGroupsRender` screen-relative dispatch, `indirect.<pass>` indirect dispatch via
  new `glDispatchComputeIndirect` service method (both LWJGL backends), barriers already ALL_BARRIER_BITS.
- **R6 Horizon / R8 Lightning** — satisfied by 1.12.2 architecture: vanilla still draws the sky void plane
  (adding Iris's disc would z-fight), and lightning is an entity rendered through the gbuffers entities phase.
