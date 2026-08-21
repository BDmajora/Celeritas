# Fulgor — lighting subsystem for Impetus (1.12.2)

> *fulgor* (Latin) — "brightness, a flash of light".

Fulgor is Impetus' fourth subsystem, beside the renderer (`impl/`, Sodium/Embeddium lineage), the
shader pipeline (`iris/`, Iris lineage) and the memory compressor (`coartatio/`, Hydrogen lineage).
It is a backport of [Phosphor](https://github.com/CaffeineMC/phosphor-fabric), built on the 1.12.2
line that Phosphor itself started — Phosphor-Forge, then [Hesperus] — and folding in the corrections
[Alfheim] made to it.

Its job is a single sentence: **make light propagation cost what it should, without changing what the
light looks like.**

---

## 1. Why 1.12.2 lighting is slow

Vanilla's `World.checkLightFor` is not a scheduler. It is a full propagation, run synchronously with
the block change that triggered it, and it re-derives the same positions once for every path that
reaches them. Three consequences:

- **Repeated work.** Placing one block schedules its six neighbours, each of which schedules its six.
  The overlap is not detected; a position reachable eight ways is evaluated eight times.
- **Intermediate states are paid for.** A `/fill`, an explosion, a quarry or a chunk of world
  generation passes through thousands of lighting states on the way to one final state, and vanilla
  computes every one of them.
- **Work is thrown away.** When propagation would cross into a chunk that is not loaded, vanilla
  drops it silently and never revisits it. That is not a performance problem, it is where the light
  seams along chunk borders in freshly generated terrain come from.

Phosphor's answer, which Fulgor inherits: record the position, propagate nothing, and resolve the
whole accumulated batch the next time something actually reads light. Alfheim's answer on top of
that: collapse the repeats before they ever reach the queue.

## 2. Why this lives inside Impetus instead of beside it

The same argument as Coartatio's, with one addition that is specific to lighting.

Impetus' terrain renderer does not read the world through `IBlockAccess`. `WorldSlice.prepare` copies
whole `NibbleArray`s out of chunk sections so a worker thread can mesh them off the main thread. That
copy bypasses `Chunk.getLightFor`, which is the *only* hook a deferred lighting engine has to know it
is being read — so an external Phosphor and an internal Sodium fork will silently bake stale light
into chunk meshes, and neither mod can detect it.

Owning both sides means:

- **One flush point.** `FulgorRenderBridge.flushPendingLightUpdates` runs in `WorldSlice.prepare`,
  before anything is copied. There is no way to express that from outside either mod.
- **One meaning for "empty section".** Fulgor widens `ExtendedBlockStorage.isEmpty()` so blockless
  sections with real light still reach the client (§5, MC-116690). The renderer asks the *narrow*
  question through `SectionLightInfo`, so it does not start queueing build tasks for sections that
  can only produce empty geometry. Split from outside, one of those two would be wrong.
- **One config surface and one crash report.** Feature toggles live with Impetus' toggles, and a bad
  interaction is one bug report rather than a compatibility matrix.

## 3. Source layout

```
src/main/java/com/bdmajora/fulgor/
├── Fulgor.java                 logger, optional-mod detection, counters, F3 line
├── FulgorConfig.java           java.util.Properties config, readable at coremod time
├── FulgorRenderBridge.java     the two seams Impetus' renderer needs
├── api/
│   ├── LightingEngineProvider.java  World and Chunk → engine
│   ├── ChunkLightingData.java       boundary flags, light-initialised, uncached light read
│   ├── LightInfoBlock.java          per-block "can my light vary with position"
│   ├── LightUpdateProcessor.java    RenderGlobal drain hook
│   └── SectionLightInfo.java        the narrow emptiness question
├── collections/
│   └── DeduplicatedLongQueue.java   pooled-segment FIFO with a deduplication set
├── lighting/
│   ├── LightingEngine.java          the batched propagator, one per World
│   ├── LightingHooks.java           chunk-level and boundary operations
│   ├── NeighborLightFlags.java      boundary flag indexing + NBT round trip
│   ├── LightUtil.java               opacity/luminance with the fast path and compat
│   ├── WorldChunkSlice.java         5×5 chunk snapshot for recheckGaps
│   ├── DynamicLightsBridge.java     MethodHandle into Dynamic Lights
│   └── FluidLightCompat.java        Fluidlogged API's second state per position
├── gui/
│   ├── FulgorOptionPages.java       the Lighting page in video options
│   └── FulgorStatsCommand.java      /fulgor
└── mixin/
    ├── FulgorMixinPlugin.java
    ├── block/BlockMixin.java
    ├── client/{MinecraftMixin, RenderGlobalMixin}.java
    ├── network/SPacketChunkDataMixin.java
    └── world/{World, Chunk, ChunkSkylight, ChunkProviderServer,
                AnvilChunkLoader, ExtendedBlockStorage}Mixin.java
```

Registered from `ImpetusLoadingPlugin.getMixinConfigs()` as `mixins.fulgor.json`, package
`com.bdmajora.fulgor.mixin`, with an explicit mixin list so `FulgorMixinPlugin` can veto individual
features from config — the same declared-list form Coartatio uses, and for the same reason: a feature
suspected of causing a bug should be removable without a rebuild.

---

## 4. Feature inventory

Legend: **[done]** written, **[next]** designed and scheduled, **[hold]** deliberately deferred.

### Phase 1 — the engine  **[done]**

| # | Feature | Origin | 1.12.2 target |
|---|---------|--------|---------------|
| 1.1 | Config + mixin gate + logger + counters | ModernFix, Coartatio | — |
| 1.2 | Deferred propagation: `checkLightFor` records, `getLightFor` resolves | Phosphor | `World`, `Chunk` |
| 1.3 | Descending brighten/darken sweep, one pass per batch | Phosphor | `LightingEngine` |
| 1.4 | Pooled-segment long FIFO | Phosphor `PooledLongQueue` | `DeduplicatedLongQueue` |
| 1.5 | Update deduplication | Alfheim `DeduplicatedLongQueue` | same |
| 1.6 | Off-thread access detection | Phosphor | `LightingEngine.lock()` |
| 1.7 | Flush before save, unload, serialise | Phosphor | `ChunkProviderServer`, `AnvilChunkLoader`, `SPacketChunkData` |
| 1.8 | Flush before terrain meshing | *new* | `WorldSlice.prepare` |

### Phase 2 — correctness  **[done]**

| # | Feature | Fixes | 1.12.2 target |
|---|---------|-------|---------------|
| 2.1 | Boundary-check flags, recorded and replayed on load | MC-3329, MC-117067, MC-117094 | `Chunk.onLoad`, `AnvilChunkLoader` |
| 2.2 | Seed only the new section's skylight, not the whole column | perf; MC-102162 | `Chunk.setBlockState`, `Chunk.setLightFor` |
| 2.3 | Chunk declared lit only when its 3×3 neighbourhood is | MC-3329 | `Chunk.checkLight` |
| 2.4 | Send blockless sections whose lighting is non-trivial | MC-116690 | `ExtendedBlockStorage.isEmpty` |
| 2.5 | Drain render light updates unconditionally | MC-80966 | `RenderGlobal.updateClouds` |

### Phase 3 — hot-path work  **[done]**

| # | Feature | Origin | Notes |
|---|---------|--------|-------|
| 3.1 | Per-block "is my light position-dependent" cache | Phosphor `BlockStateLightInfo` | See §6 |
| 3.2 | Read block states straight from the section | Phosphor `LightingEngineHelpers` | Skips `Chunk.getBlockState`'s bounds checks and crash-report handling |
| 3.3 | 5×5 chunk snapshot for `recheckGaps` | Phosphor | 1024 provider lookups → 25 |
| 3.4 | Long queue instead of `HashSet<BlockPos>` for render updates | Alfheim | No boxing, no iterator |
| 3.5 | Skip light processing while paused | Alfheim | Config-gated, live |

---

## 5. Phosphor coverage audit

phosphor-fabric targets 1.19, whose lighting is a `LightingProvider`/`LevelPropagator` architecture
that does not exist on 1.12.2 — so this is a mapping of *intent*, not of files. The 1.12.2 line
(Phosphor-Forge → Hesperus → Alfheim) is where the direct ancestry is.

| Phosphor (fabric) | Fulgor | Status |
|---|---|---|
| `MixinChunkLightProvider` — batched propagation | `LightingEngine` | ported from the 1.12.2 ancestor, which is the same algorithm before the 1.14 rewrite |
| `MixinLevelPropagator` — level-ordered sweep | `LightingEngine.propagate` | ported |
| `block.MixinAbstractBlockState` + `BlockStateLightInfo` | `BlockMixin` + `LightInfoBlock` | **adapted** — 1.19 caches the value on the state's `ShapeCache`; 1.12.2 has no such cache and Coartatio is actively removing per-state storage, so the *override flag* is cached per block instead. See §6 |
| `MixinShapeCache` | — | **N/A** — no `VoxelShape` occlusion on 1.12.2; opacity is a scalar |
| `util.LightUtil.unionCoversFullCube` | — | **N/A** — same reason |
| `MixinSkyLightStorage`, `SkyLightStorageAccess` | `LightingHooks.relightSkylightColumn` + `NeighborLightFlags` | **adapted** — 1.19 tracks skylight readiness per section in the storage; 1.12.2 has no storage layer, so the equivalent state lives on the chunk |
| `MixinBlockLightStorage`, `LightStorageAccess` | — | **N/A** — 1.12.2 stores light in `ExtendedBlockStorage` directly |
| `EmptyChunkNibbleArray`, `ReadonlyChunkNibbleArray`, `SkyLightChunkNibbleArray` | — | **N/A** — copy-on-write nibble arrays exist to serve 1.14+'s threaded light provider |
| `DoubleBufferedLong2IntHashMap`, `DoubleBufferedLong2ObjectHashMap` | — | **N/A** — same reason |
| `MixinServerLightingProvider` — off-thread lighting | — | **[hold]** — see §7 |
| `MixinChunkNibbleArray`, `MixinChunkToNibbleArrayMap` | — | **N/A** |
| `MixinWorldChunk`, `MixinProtoChunk`, `MixinChunkStatus` | `ChunkMixin`, `ChunkSkylightMixin` | ported to 1.12.2's single chunk type |
| `LightProviderUpdateTracker` | — | **[hold]** — a debug facility for the threaded provider |
| `math.DirectionHelper`, `ChunkSectionPosHelper` | `LightingEngine` bit layout | ported inline; the encoding is the 1.12.2 ancestor's |

### 1.12.2 ancestry audit

| Hesperus / Alfheim | Fulgor | Status |
|---|---|---|
| `mod.world.lighting.LightingEngine` | `lighting.LightingEngine` | ported, with Alfheim's deduplication and `byte`-narrowed levels |
| `mod.collections.PooledLongQueue` | `collections.DeduplicatedLongQueue` | **merged** — Phosphor's pooled segments (low GC) with Alfheim's dedup set (less work). Alfheim dropped the segment pool and allocates a fresh `LongOpenHashSet` per pass; this keeps both |
| `mod.world.lighting.LightingHooks` | `lighting.LightingHooks` + `NeighborLightFlags` | ported, split so the NBT round trip and flag indexing are separable |
| `mod.world.lighting.LightingEngineHelpers` | `lighting.LightUtil` | ported and **extended** with the block-info fast path |
| `mod.world.WorldChunkSlice` | `lighting.WorldChunkSlice` | ported, plus bounds checks Alfheim's version omits |
| `api.IChunkLighting` + `api.IChunkLightingData` | `api.ChunkLightingData` | **merged** — two interfaces on one class with one lifetime |
| `api.ILightingEngine` | — | **dropped** — an interface over a single implementation. `LightingEngine` is final and referenced directly |
| `api.ILightingEngineProvider` | `api.LightingEngineProvider` | ported |
| `mixins.lighting.common.MixinWorld` | `world.WorldMixin` | ported |
| `mixins.lighting.common.MixinChunk` | `world.ChunkMixin` | ported |
| `MixinChunk$Vanilla` | `world.ChunkSkylightMixin` | ported, and **retargeted off `@At("NEW")`** — see §6 |
| `MixinChunk$Sponge` | — | **dropped** — see §7 |
| `MixinExtendedBlockStorage` | `world.ExtendedBlockStorageMixin` | ported, plus `SectionLightInfo` so the renderer keeps the original question |
| `MixinChunkProviderServer` | `world.ChunkProviderServerMixin` | ported |
| `MixinAnvilChunkLoader` | `world.AnvilChunkLoaderMixin` | ported |
| `MixinSPacketChunkData` | `network.SPacketChunkDataMixin` | ported |
| `mixins.lighting.client.MixinMinecraft` | `client.MinecraftMixin` | ported and **extended** — Hesperus flushes the world engine, Alfheim drains the render queue; Impetus' renderer needs both, in that order |
| `mixins.lighting.client.MixinRenderGlobal` | `client.RenderGlobalMixin` | Hesperus' is an empty debug stub; Alfheim's is the real one and is what was ported |
| Alfheim `ChunkCacheMixin`, `BlockMixin`, `BlockSlabMixin`, `BlockStairsMixin`, `BlockModelRendererMixin` | — | **[hold]** — the MC-92 non-full-block relighting work. See §7 |
| Alfheim `BlockStateContainerMixin` (`ILightInfoProvider`) | — | **[hold]** — same feature, and it collides with Coartatio. See §7 |
| Alfheim `AlfheimPlugin` Cubic Chunks refusal | `FulgorMixinPlugin` | ported |
| Alfheim `AlfheimPlugin` config hijacking | `ImpetusLoadingPlugin` | ported and **widened** to Alfheim itself |

---

## 6. Deviations worth keeping straight

**The block light-info cache is per block, not per state.** Phosphor caches the resolved light value
on the block state's `ShapeCache`. On 1.12.2 there is no shape cache, and adding a field to
`StateImplementation` would fight Coartatio, whose entire purpose is removing per-state storage — a
modded instance has hundreds of thousands of states. What Fulgor caches instead is one byte per
*block* recording whether that block's class overrides Forge's position-aware
`getLightValue`/`getLightOpacity`. When it does not, the engine calls `state.getLightValue()` and
skips two virtual dispatches on `Block`, which in a large pack is a megamorphic call site that never
inlines. Less than Phosphor gets on 1.19; free of memory cost, which is the constraint that matters
here.

The flags are derived by reflection on first use rather than at construction: blocks are constructed
during mod loading while ASM coremods may still be rewriting their classes, and the first light query
happens once a world exists. Both method names are Forge additions and therefore not obfuscated, so
looking them up by name works identically in development and production.

**Deduplication reset ordering is a correctness invariant, not a detail.** The set must be empty when
the *next* cycle's enqueues begin. The engine fills a light level's queue during the levels above it
and then drains it, so it resets before draining; the renderer's queue is only filled after its drain,
so it resets after. Getting it backwards does not corrupt anything — it silently drops a position
updated on two consecutive cycles, which surfaces much later as one stale block. Documented on
`DeduplicatedLongQueue` because the two call sites genuinely differ.

**Queue sets start small.** There are thirty-four queues per engine and an engine per world — four in
a single-player game with the Nether and the End loaded. Alfheim's 16384-entry initial capacity would
be roughly 9 MB of mostly-empty hash table per world before a block is placed. Fulgor starts at 512
and lets fastutil grow, then shrinks any set that exceeded 32768 entries back down after the pass that
grew it.

**`ChunkSkylightMixin` intercepts a local store, not a constructor.** Hesperus and Alfheim seed a newly
created `ExtendedBlockStorage` with `@Redirect(at = @At(value = "NEW", args = "class=..."))`. Impetus
reobfuscates mixin annotations at build time through tiny-remapper's `MixinExtension`, and
`@At("NEW")`'s `args` form is not a shape worth betting the subsystem on. A `@ModifyVariable` on the
second store to the section local reaches the same object at the same moment with no class reference
to remap.

**`getLightFor` flushes one light type; `getLightSubtracted` flushes both.** Sky and block light
propagate independently, and a block-light read has no reason to pay for a pending skylight batch.
`getLightSubtracted` genuinely reads both, so it flushes both.

**`WorldProvider.hasSkyLight()` is read live, never cached.** It is tempting to hoist out of the
neighbour loop, but it is populated by `WorldProvider.registerWorld`, which runs *after* the `World`
constructor the engine is built in. Caching it at construction would make every dimension look like it
has sky.

**The engine clears its `updating` flag in a `finally`.** A pass calls `World.notifyLightSet` and
block light-value methods, both of which reach foreign code and can throw. Upstream leaves the flag
set, which turns one mod's exception into permanently dead lighting for the rest of the session.

---

## 7. Deliberately not done

**Sponge support.** Hesperus ships a `MixinChunk$Sponge` variant with hard-coded local-variable
indices against SpongeForge's `bridge$setBlockState`. Impetus is `clientSideOnly`; SpongeForge is a
dedicated-server platform. The variant would be three fragile injectors maintained against a
combination that cannot occur. If Impetus ever ships a server-side artifact this is the first thing to
revisit.

**MC-92 and non-full-block lighting.** Alfheim's largest addition beyond Phosphor: slabs, stairs and
similar are lit as though they filled their block, and fixing it means overwriting
`Block.getPackedLightmapCoords`, `Block.getAmbientOcclusionLightValue`, `ChunkCache.getLightForExt`
and `BlockModelRenderer`'s AO path, plus threading an `ILightInfoProvider` through
`BlockStateContainer.StateImplementation`. Two problems: the AO path is Impetus' own — the renderer
does not use `BlockModelRenderer` — so it would have to be reimplemented against `LightDataCache`
rather than ported; and the `StateImplementation` mixin collides directly with Coartatio's
`CoartatioBlockState`. It is a genuine visual improvement and it belongs in a phase of its own, after
measurement, not smuggled in with the engine.

**Off-thread lighting.** Phosphor's `MixinServerLightingProvider` and [Pulsar]'s whole design move
propagation off the server thread. On 1.12.2 that requires either Cleanroom (Pulsar's target) or a
copy-on-write light storage layer that does not exist, and it changes when light is *observable*,
which interacts with every mod that reads light during a block update. Batching plus deduplication
captures most of the win with none of that exposure. Revisit with measurements showing the server
thread is still lighting-bound.

**Per-world statistics.** The counters are process-wide. In single-player the client world and the
integrated server's worlds both contribute, which is the total the frame time is paying for; splitting
them would be more precise and less useful.

---

## 8. Interactions with the rest of Impetus

| Impetus component | Interaction |
|---|---|
| `WorldSlice.prepare` | Flushes pending updates before copying light arrays, and asks `SectionLightInfo` rather than `isEmpty()` |
| `VintageRenderSectionManager.isSectionVisuallyEmpty` | Same emptiness question, same reason |
| `RenderGlobalMixin` (Impetus, terrain) | Already `@Overwrite`s `markBlocksForUpdate` and redirects two calls in `updateClouds`. Fulgor redirects a *third*, `Set.isEmpty()` ordinal 0, which short-circuits the branch the other two live in — they become unreachable while Fulgor's render queue is on, and take over again when it is off |
| Coartatio `world.ChunkMixin` | Also targets `Chunk`, on `setStorageArrays`; no method overlap. Its own light-reproducibility guard and Fulgor's widened `isEmpty()` agree — both keep sections whose light is not reproducible |
| Coartatio `world.AnvilChunkLoaderMixin` | Strips chunk NBT at `checkedReadChunkFromNBT__Async` RETURN, which runs *after* `readChunkFromNBT` where Fulgor reads its tags. Order is correct and load-bearing |
| Coartatio block states | Fulgor never adds per-state storage; see §6 |

## 9. Mod compatibility

| Mod | Handling |
|---|---|
| Cubic Chunks | Hard refusal. Every assumption Fulgor makes — 16 sections, an 8-bit y field, one heightmap per column — is wrong there, and the failure mode is silent corruption rather than a crash |
| Phosphor / Hesperus | `mixins.phosphor.json` suppressed via MixinBooter's `IMixinConfigHijacker`, with a log line telling the user to remove it |
| Alfheim | `mixins.alfheim.json`, same treatment |
| Dynamic Lights | Luminance queried through it, bound as a `MethodHandle` so there is no compile dependency. Disables the block-info fast path |
| Fluidlogged API | Opacity and luminance take the maximum of the block and its fluid state. Disables the block-info fast path |
| Anything reading light off-thread | Turned away on the client (there is a tick coming that will do it); warned about and then serialised on the server |

---

## 10. Configuration

`config/impetus-fulgor.cfg`, plain `java.util.Properties` so `FulgorMixinPlugin` can read it during
coremod setup. Also exposed as the **Lighting** page in Impetus' video options.

| Key | Default | Restart |
|---|---|---|
| `enabled` | `true` | yes |
| `deferredLightUpdates` | `true` | yes |
| `deduplicateUpdates` | `true` | yes |
| `cacheBlockLightInfo` | `true` | yes |
| `fixChunkBoundaryLighting` | `true` | yes |
| `sendNonTrivialSectionLight` | `true` | yes |
| `optimizeRenderLightUpdates` | `true` | yes |
| `skipUpdatesWhilePaused` | `true` | no |
| `maxScheduledUpdates` | `4194304` | yes |
| `warnOnIllegalThreadAccess` | `true` | no |
| `logStatistics` | `false` | no |
| `showDebugOverlay` | `false` | no |

`maxScheduledUpdates` is a memory bound, not a performance knob: it forces a flush when a producer
schedules without ever reading, which would otherwise grow a queue without limit.

## 11. Instrumentation

`/fulgor` and the optional F3 line report three numbers: positions scheduled, positions collapsed as
duplicates, and positions actually evaluated. The one to watch is the collapsed share — vanilla,
Phosphor and Hesperus would all have evaluated everything scheduled, so whatever fraction is collapsed
is lighting work that never happened.

**What it deliberately does not claim.** These are counts, not time. A count is honest about what the
engine skipped and says nothing about frame time; the profiler section (`fulgor` → `sort` / `seed` /
`propagate`) is where the cost actually shows up.

---

## 12. Verification checklist

Not yet run — this is what "working" has to mean before Fulgor is called done.

- [ ] Generate a fresh world and fly the border of the generated region; no light seams at chunk edges.
- [ ] Hollow out a large volume underground, leave, return; the interior is still dark (MC-116690).
- [ ] Place and break a torch rapidly under chunk-builder load; the rebuild is not deferred (MC-80966).
- [ ] `/fill` a large volume with glowstone, then with air; compare tick time against `enabled=false`.
- [ ] Save, quit and reload mid-propagation; light matches what it was before the save.
- [ ] Nether and End: no sky light, no skylight seams, `hasSkyLight()` respected per dimension.
- [ ] With Dynamic Lights installed: dropped torches still light their surroundings.
- [ ] With Fluidlogged API installed: fluidlogged sea lanterns still glow, fluidlogged blocks still darken.
- [ ] `/fulgor` reports a non-trivial collapsed share after a bulk edit.

[Hesperus]: https://github.com/jellysquid3/Hesperus-forge
[Alfheim]: https://github.com/Desoroxxx/Alfheim
[Pulsar]: https://github.com/SumireLabs/Pulsar
