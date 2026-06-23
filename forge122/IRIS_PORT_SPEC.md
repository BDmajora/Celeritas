# Iris-on-Celeritas (1.12.2) — Porting Spec

> **Status:** Phase 1 (Foundation) complete. Shader-pack loading & parsing implemented under
> `forge122/src/main/java/org/taumc/celeritas/iris/`. **No rendering behavior changes yet** — with no pack selected,
> Celeritas renders identically to before.

This document supersedes the original "Port the Iris Shaders mod to Minecraft 1.12.2" brief. That brief was written
without access to the real codebase and contained a number of concrete inaccuracies that would not compile or run as
written. Everything below was **verified against the actual `Celeritas` source** (commit on branch `stonecutter` /
`claude/exciting-nobel-906926`).

---

## 0. Corrections from the original brief (read this first)

| # | Original brief said | Reality in this repo | Impact |
|---|---|---|---|
| 1 | "Java 8 — NO `var`, NO `record`, NO text blocks." | Build uses **Jabel** (`sourceCompatibility=21`, `options.release=8`) **+ Lombok**. Modern *syntax* (`var`, records, text blocks, switch expressions) compiles to Java 8 bytecode. **But** because forge122 uses `--release 8`, Java 9+ *library* APIs (`Set.of`, `List.of`, `Stream.toList()`, `Path.of`, ...) are **not** available in forge122 sources — use Java 8 equivalents (`Arrays.asList`, `Collectors.toList()`). Only the bytecode-downgraded `common` module may use newer library APIs. | The real constraint is "Java 8 *bytecode + library API*, Java 21 *syntax*." Match the surrounding code (e.g. existing forge122 code uses `Collectors.toList()`, never `Set.of`/`.toList()`). |
| 2 | "Use raw `org.lwjgl.opengl.GL20.*` / `GL30.*` directly." | All GL goes through the Celeritas abstraction **`org.taumc.celeritas.lwjgl.*`**: call functions via `LWJGLServiceProvider.LWJGL` (a `LWJGLService`), constants via generated classes `org.taumc.celeritas.lwjgl.GL11..GL46`. This is what provides LWJGL2/3 compatibility. | Raw LWJGL calls would break the LWJGL3/lwjgl3ify path. **The `LWJGLService` interface currently lacks many functions Iris needs** (see §5.1) and must be extended first. |
| 3 | "`ChunkShaderTextureService` SPI; replace `VintageChunkShaderTextureService`." | **No such interface exists.** The real chunk-shader API is `ChunkShaderInterface` + `DefaultChunkShaderInterface` + enum `ChunkShaderTextureSlot{BLOCK, LIGHT}` (in `org.embeddedt.embeddium.impl.render.chunk.shader`). | Texture-slot binding for Iris must extend `ChunkShaderTextureSlot` / implement a custom `ChunkShaderInterface`, not a nonexistent service. |
| 4 | Lists three SPI services incl. `VintageChunkShaderTextureService`, `VintageRenderVisualsService`. | Only real SPI interfaces are **`FogService`** and **`RenderVisualsService`**. The `META-INF/services` entries point at `Vintage*Service` classes that **do not exist in source** (stale). Only `GLStateManagerFogService` exists. | Don't model new code on those names. If `RenderVisualsService` is actually loaded via `ServiceLoader`, its missing impl is a latent runtime bug to be aware of. |
| 5 | AT: `EntityRenderer field_175074_T # shaderGroup` (and a v2 `field_147712_T`). | **Both SRG names are fabricated** — neither exists in MCP `stable_39`. Real: `EntityRenderer.shaderGroup` = **`field_147707_d`**. | Wrong AT = build failure. See §6 for verified names. |
| 6 | AT: `Framebuffer field_147621_h # framebufferWidth`, `field_147622_i # framebufferHeight`. | Both fabricated. Real: `framebufferWidth` = **`field_147621_c`**, `framebufferHeight` = **`field_147618_d`** (and these are already `public` in vanilla — likely no AT needed). | See §6. |
| 7 | AT: `OpenGlHelper field_153161_i # framebufferObjectsSupported`. | Fabricated. Real: `OpenGlHelper.framebufferSupported` = **`field_148823_f`**. *But* FBO management should go through the LWJGL abstraction (§5.1), not `OpenGlHelper`, for LWJGL3 parity. | See §6. |
| 8 | "`Minecraft.getMinecraft().mcDataDir`." | This mapping set has **no `mcDataDir`** — the field is **`gameDir`**. Use the existing helper **`PlatformUtil.getGameDir()`**. | `mcDataDir` would not compile. |
| 9 | "Hook `RenderManager.shouldRender()`" (v1) / "`Entity.isInRangeToRender3d()`" (v2). | The brief is internally inconsistent. Celeritas's actual entity gathering is in `RenderGlobalMixin`; the exact hook must be read from that mixin, not assumed. | Verify against `RenderGlobalMixin` before wiring shadow-entity culling. |
| 10 | Single-module mental model. | Multi-module Gradle: **`common/`** holds the shared Embeddium impl (`org.embeddedt.embeddium.impl.*`) + Celeritas abstractions; **`forge122/`** is the Forge 1.12.2 platform (mixins, MC hooks) and depends on `common` (the `downgraded` configuration). | Put shared, MC-free logic where it belongs; Iris currently lives in `forge122`. |

---

## 1. Target environment (verified)

- **Minecraft:** 1.12.2 (Forge), MCP mappings `stable_39`.
- **Java:** source level 21 via Jabel, **emitted as Java 8 bytecode** (`options.release = 8`). Lombok is on the
  annotation-processor path. Use modern *syntax* freely; do not hand-desugar. **But** `--release 8` also pins the
  *library* API to Java 8 in forge122 — no `Set.of`/`List.of`/`Stream.toList()`/`Path.of`; use Java 8 equivalents.
- **GL backend:** LWJGL2 by default, LWJGL3 via `legacy-lwjgl3` / RetroFuturaBootstrap. **Always** go through
  `org.taumc.celeritas.lwjgl` (`LWJGLServiceProvider.LWJGL` + `GLxx` constant classes).
- **Mixins:** SpongePowered Mixin 0.8+ via MixinBooter (`zone.rong:mixinbooter`), MixinExtras available.
- **Mod entry:** `@Mod` class `org.taumc.celeritas.CeleritasVintage`. Coremod / early-mixin loader:
  `org.taumc.celeritas.core.CeleritasLoadingPlugin` (`IFMLLoadingPlugin` + `IEarlyMixinLoader`).
- **Terrain pipeline:** `TerrainRenderPass` (a Lombok `@Builder` class) + `ChunkShaderInterface`. Vertex data:
  `ChunkVertexType` / `ChunkVertexEncoder.Vertex`.

---

## 2. Build & verification reality

- Subprojects are **gated behind a Gradle property**. Building forge122 requires selecting the version:
  ```
  ./gradlew :forge122:compileJava -Pceleritas_target_versions=1.12.2
  ```
  Without it you get `project 'forge122' not found` and `WARNING: No projects were selected`.
- **Building inside a git worktree currently fails at configuration time** — root `build.gradle.kts:11`
  (`tau.versioning.version(...)`) throws `repository not found: .../.git/worktrees/<name>`. Build from a normal
  (non-worktree) checkout, or fix the versioning plugin's worktree handling.
- **Pre-existing blocker unrelated to Iris:** `:common:compileJava` fails with
  `common/.../gui/frame/tab/Tab.java:21: cannot find symbol: class Builder` — Lombok `@Builder` is not being
  processed (reproduces on a pristine checkout with zero Iris changes; most likely Lombok vs. the JDK the Gradle
  daemon runs on). Because `forge122` depends on `common`, **no full forge122 build can go green until this is fixed.**
- **What *was* verified for Phase 1:** the Minecraft-free subsystem compiles cleanly at Java-21 source level, and a
  32-assertion functional test passes (folder + zip load; relative & absolute `#include` flattening; OptiFine
  fallback chain; `world0/` override; `shaders.properties` parsing; `#version` detection / `#define` injection).

---

## 3. Integration points (verified APIs)

### 3.1 Service Loader
Real Embeddium SPI interfaces: `FogService` (`...render.chunk.fog`) and `RenderVisualsService`
(`...render.chunk.lists`). Iris should:
- **Fog:** make the fog service return `ChunkFogMode.NONE` when shaders are active (shaders own fog via GLSL).
- **Render visuals:** make it shadow-pass-aware (disable effects during the shadow pass).
- **Textures:** there is no texture *service*; bind extra samplers by extending `ChunkShaderTextureSlot` and
  implementing a custom `ChunkShaderInterface` (see §3.4).

### 3.2 RenderGlobalMixin
The existing `RenderGlobalMixin` (Celeritas) overriding `RenderGlobal.renderBlockLayer()` is the primary terrain
interception point. When a pack is loaded: drive the geometry through Iris G-buffer programs instead of the default
`drawChunkLayer`, then run composite/final passes after all g-buffer layers. When not loaded: unchanged.

### 3.3 EntityRenderer
Mixin `EntityRenderer.renderWorld(float, long)` to: set up the pipeline at frame start, run the shadow pass before
the main render, replace sky rendering, run composites after the main render, and blit the final pass to screen.

### 3.4 Chunk shader interface (replaces the brief's "texture service")
`ChunkShaderInterface` methods: `setupState`, `setProjectionMatrix`, `setModelViewMatrix`, `setRegionOffset`,
`setTextureSlot(ChunkShaderTextureSlot, int)`, `getPrimitiveType`. To bind Iris samplers (normals, specular,
shadowtex, noise, …) extend `ChunkShaderTextureSlot` and provide an Iris `ChunkShaderInterface` implementation that
uploads them; the OptiFine 1.12.2 texture-unit convention is TU0 `tex`, TU1 `texLightmap`, TU2 `texNorm`,
TU3 `texSpec`, TU4 `shadowtex0/shadow`, TU5 `shadowcolor0`, TU6 `noisetex`, TU7 `shadowcolor1`.

### 3.5 Vertex format
Extend Embeddium's `ChunkVertexType` (preferred) to add OptiFine attributes `mc_midTexCoord` (vec2),
`at_tangent` (vec4), `mc_Entity` (vec2 = block id + meta); `normal` already exists as `trueNormal`/`vanillaNormal`.
`overlayId` does not exist in 1.12.2 and is excluded. `mc_midTexCoord` must come from `BakedQuad.getSprite()` center
UV (`(minU+maxU)/2`, `(minV+maxV)/2`) — **not** an average of quad UVs (that breaks animated sprites).

---

## 4. Key 1.12.2 adaptations (unchanged from brief, still correct)

- No post-processing pipeline / `PostChain` — build the FBO chain from scratch.
- Fixed-function lighting for entities/TEs — disable when custom shaders are active.
- Lightmap is TU1 (`texLightmap`); preserve Celeritas's existing binding.
- `BlockRenderLayer` (SOLID, CUTOUT_MIPPED, CUTOUT, TRANSLUCENT) instead of `RenderType`. Map: solid/cutout →
  `gbuffers_terrain` (alpha test for cutout); translucent water → `gbuffers_water`, other translucent →
  `gbuffers_translucent`. Classify water during meshing via `state.getMaterial() == Material.WATER`.
- Multi-pass entities via `MinecraftForgeClient.getRenderPass()` (0 solid / 1 translucent); switch programs per pass.
- Shadow pass must override baked-AO vertex color to white (1,1,1,1) to avoid false self-shadowing.

---

## 5. The LWJGL abstraction (critical for all rendering phases)

### 5.1 `LWJGLService` must be extended before Phase 2
`org.taumc.celeritas.lwjgl.LWJGLService` (impl in `common/src/lwjgl2` and `common/src/lwjgl3`) currently exposes
buffers, VAOs, shader/program calls, a subset of uniforms, textures, and basic FBO calls
(`glGenFramebuffers`, `glBindFramebuffer`, `glFramebufferTexture2D`, `glCheckFramebufferStatus`). It is **missing**
calls Iris needs, including: `glTexImage2D`, `glTexParameteri/f`, `glDrawArrays`, `glDrawBuffers`, `glReadBuffer`,
`glGenRenderbuffers`/`glFramebufferRenderbuffer`, `glClearDepth`, and `glUniform2f`/`glUniform4f`. Add these to the
interface and **both** the LWJGL2 and LWJGL3 implementations. Constants already exist in the generated `GLxx` classes.

### 5.2 Hot-swap cleanup
On pack change, free all GL resources via the abstraction: delete FBOs/attachments/textures, delete programs, flush &
rebuild the chunk mesh queue (vertex format may have changed), restore default GL state.

---

## 6. Access Transformers (verified SRG names, MCP `stable_39`)

Existing `forge122/src/main/resources/META-INF/celeritas_at.cfg` already exposes the `GlStateManager` fog state and
`EntityRenderer` fog-color fields. Add, **only if the target is not already public** (check first):

```
# EntityRenderer.shaderGroup  (the active ShaderGroup; for restoring vanilla shaders)
public net.minecraft.client.renderer.EntityRenderer field_147707_d
# OpenGlHelper.framebufferSupported  (only if you query OpenGlHelper rather than the LWJGL abstraction)
public net.minecraft.client.renderer.OpenGlHelper field_148823_f
```

`Framebuffer.framebufferWidth` (`field_147621_c`) and `framebufferHeight` (`field_147618_d`) are already `public` in
vanilla — no AT required. If you need the *texture* dimensions instead, those are `framebufferTextureWidth`
(`field_147622_a`) / `framebufferTextureHeight` (`field_147620_b`). **Do not** copy the brief's
`field_175074_T` / `field_147712_T` / `field_147621_h` / `field_147622_i` / `field_153161_i` — none of those exist.

---

## 7. Module structure

### Implemented (Phase 1) — `forge122/src/main/java/org/taumc/celeritas/iris/`
```
iris/
├── Iris.java                       # entry point / global state, wired into CeleritasVintage.onInit
├── package-info.java               # module status + roadmap
├── config/
│   └── IrisConfig.java             # optionsshaders.txt read/write, shaderpacks/ resolution
└── shaderpack/
    ├── ShaderPack.java             # assembles a ProgramSet from a folder/zip's GLSL files
    ├── ShaderPackLoader.java       # folder + .zip reading (java.nio/zip only, MC-free)
    ├── ShaderProperties.java       # shaders.properties parser (+ typed accessors)
    ├── ProgramSource.java          # one program's flattened vsh/gsh/tcs/tes/fsh
    ├── ProgramSet.java             # programs by id + OptiFine fallback resolution
    ├── include/
    │   ├── AbsolutePackPath.java   # normalized pack-relative path (#include resolution)
    │   └── IncludeProcessor.java   # recursive #include flatten + cycle detection
    ├── loading/
    │   ├── ProgramId.java          # 1.12.2 gbuffers_*/shadow/final set + fallback chain
    │   └── ProgramArrayId.java     # composite/deferred/shadowcomp numbered families
    └── preprocessor/
        └── GlslPreprocessor.java   # #version detection + #define injection
```
Plumbing: `mixins.iris.json` (currently inert/empty) registered alongside `mixins.celeritas.json` by
`CeleritasLoadingPlugin`; `Iris.initialize(PlatformUtil.getGameDir().toPath())` called from `CeleritasVintage.onInit`.

### Planned (later phases)
`gl/program/` (GlProgram/ProgramBuilder over the LWJGL abstraction), `gl/framebuffer/` (IrisRenderTargets),
`pipeline/` (IrisPipeline, ShadowRenderer, CompositeRenderer, GBufferRenderer), `vertices/` (IrisVertexFormats,
transformer), `shadow/`, `sky/`, `compat/`, and `mixin/` (EntityRendererMixin, additions to RenderGlobalMixin,
TextureMapMixin for normal/specular atlas stitching).

---

## 8. Phased plan

- **Phase 1 — Foundation. ✅ DONE.** Shader-pack parsing, `#include` flattening, fallback resolution,
  `shaders.properties`, `#version`/`#define`, config, module + mixin plumbing. No rendering changes.
- **Phase 2 — G-buffer terrain pass.** *Prerequisite:* extend `LWJGLService` (§5.1). Then: GL program builder over
  the abstraction; Iris `ChunkShaderInterface` + extended `ChunkShaderTextureSlot`; `IrisVertexFormats`
  (`mc_midTexCoord` via sprite-center UV, `at_tangent`, `mc_Entity`); meshing-task changes incl. `Material.WATER`
  classification; normal/specular atlas stitching via a `TextureMap` mixin; route `RenderGlobalMixin.renderBlockLayer`
  through Iris programs when a pack is loaded.
- **Phase 3 — Shadow pass.** Single orthographic shadow map (no CSM, matching 1.12.2 OptiFine). Depth-only shadow
  `TerrainRenderPass`; AO→white override; insert before main render in `EntityRenderer.renderWorld`; shadow entity
  culling (verify the real hook in `RenderGlobalMixin`).
- **Phase 4 — Composite + final.** Post chain, render-target ping-pong, temporal uniforms
  (`centerDepthSmooth`, `frameTimeCounter`), final blit.
- **Phase 5 — Non-terrain.** Entity/armor/hand shaders (per-`getRenderPass()`), particles, weather, sky, block
  entities.
- **Phase 6 — Polish.** Shader-options screen (`// option=NAME DEFAULT=VALUE`), strict hot-swap GL cleanup,
  first-person hand, GUI overlays, mod compat, `SodiumGameOptionPages` integration.

---

## 9. Constraints (corrected)

1. Java 21 syntax → Java 8 bytecode (Jabel + Lombok). forge122 sources are restricted to Java 8 *library* APIs
   (`--release 8`); use Java 8 equivalents, not `Set.of`/`List.of`/`.toList()`. Match surrounding code; no manual desugaring.
2. No Fabric APIs — Forge 1.12.2 + Mixin + the `org.taumc.celeritas.lwjgl` abstraction.
3. Zero-cost when disabled: no pack ⇒ Celeritas behaves exactly as today (Phase 1 already honors this).
4. Hot-swappable packs; strict GL-resource cleanup (§5.2).
5. Don't regress entity culling, async occlusion, chunk fade-in, render-pass consolidation, animated-texture filtering.
6. Shadow/build work dispatched off the render thread via Celeritas's chunk build pool where applicable.
7. Default shadow FBO budget ≤ 4096×4096; configurable.
8. LWJGL3 (lwjgl3ify) parity: resolve all GL through `LWJGLService`; never raw `org.lwjgl.opengl.*`.
9. OptiFine attribute/uniform parity (`mc_`/`at_` prefixes; standard 1.12.2 uniforms).

---

## 10. References

- Iris (modern): https://github.com/IrisShaders/Iris — focus `net.coderbot.iris.{pipeline,gl,shaderpack,vertices}`.
- Iris 1.6.x (last with the big Sodium API changes): https://github.com/IrisShaders/Iris/tree/1.6.x
- Embeddium (bundled in `common`): `org.embeddedt.embeddium.impl.render.chunk.{shader,vertex.format}`.
- OptiFine 1.12.2 HD U source — shader-pack conventions (attribute names, uniform list, texture units).
