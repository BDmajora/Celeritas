# Coartatio — memory-compression subsystem for Impetus (1.12.2)

> *coartatio* (Latin) — "a compressing, a narrowing".

Coartatio is Impetus' third subsystem, sitting beside the renderer (`impl/`, Sodium/Embeddium
lineage) and the shader pipeline (`iris/`, Iris lineage). It is a backport of
[Hydrogen](https://github.com/jellysquid3/hydrogen-fabric) merged with the parts of **FoamFix**,
**LoliASM**, **FerriteCore** and **ModernFix** that solve the same problems better on 1.12.2.

Its job is a single sentence: **make the heap smaller without changing observable behaviour.**

---

## 1. Why this lives inside Impetus instead of beside it

Every one of these mods works by rewriting the *same* data structures Impetus' terrain renderer
reads on its hot path — `BakedQuad.vertexData`, `SimpleBakedModel.faceQuads`,
`BlockStateContainer.StateImplementation`, `NBTTagCompound.tagMap`. Running Impetus next to
FoamFix means two coremods racing to redefine `createState`, two `@Mixin(BakedQuad.class)` classes
with different assumptions about whether `vertexData` may be mutated, and no way for the renderer
to know a quad's vertex array is now shared.

Owning the code means:

- **One `vertexData` policy.** Coartatio pools quad vertex arrays; `BakedQuadMixin` (renderer side)
  caches `flags`/`normal` derived from them. Those caches stay correct only because Coartatio
  guarantees pooled arrays are never mutated. That invariant is unenforceable across mod boundaries.
- **One model-bake lifecycle.** Pool fill, pool drain and stats reporting hang off the same
  `ModelManager` reload that Impetus already hooks.
- **One config surface and one crash report.** Feature toggles live with Impetus' toggles, and a
  bad interaction is one bug report, not a compatibility matrix.

## 2. Source layout

```
src/main/java/com/bdmajora/coartatio/
├── Coartatio.java              logger, feature gate, F3 stat line
├── CoartatioConfig.java        java.util.Properties config, readable at coremod time
├── util/
│   ├── ClassDefineTool.java    define a class into a foreign package's classloader (Java 8 + 9+)
│   └── HashStrategies.java     shared fastutil Hash.Strategy constants
├── dedup/
│   ├── DeduplicationCache.java canonicalising pool with stats + optional size cap
│   ├── StringPool.java         named string pools (NBT keys, resource paths, …)
│   ├── ResourceLocationCaches.java
│   └── ModelCaches.java        quad vertex data + model variant strings
├── collections/
│   ├── CollectionHelper.java
│   ├── FixedArrayList.java     array-backed immutable List
│   └── ArrayBackedLinkedMap.java  array-backed insertion-ordered Map
├── nbt/
│   └── TagMap.java             array-map → hash-map promoting NBT backing map
├── state/
│   ├── ConditionCanonicalizer.java  multipart predicate flattening + interning
│   └── predicate/…             flattened Predicate<IBlockState> implementations
└── mixin/
    ├── CoartatioMixinPlugin.java
    ├── util/, nbt/, client/model/, client/model/multipart/
```

Registered from `ImpetusLoadingPlugin.getMixinConfigs()` as `mixins.coartatio.json`, package
`com.bdmajora.coartatio.mixin`, with an explicit mixin list so `CoartatioMixinPlugin` can
veto individual features from config. (The `mixins.impetus.json` plugin auto-discovers by scanning
its package and therefore *cannot* be config-gated — plugin-supplied mixins bypass
`shouldApplyMixin`. Coartatio deliberately uses the declared-list form instead.)

---

## 3. Feature inventory

Legend: **[done]** written, **[next]** designed and scheduled, **[hold]** deliberately deferred.

### Phase 1 — foundation and safe deduplication  **[done]**

| # | Feature | Origin | 1.12.2 target |
|---|---------|--------|---------------|
| 1.1 | Config + mixin gate + logger + F3 stats | ModernFix | — |
| 1.2 | `ResourceLocation` domain/path interning | Hydrogen `MixinIdentifier`, LoliASM | `ResourceLocation.namespace/path` |
| 1.3 | `ModelResourceLocation` variant interning | Hydrogen `MixinModelIdentifier` | `ModelResourceLocation.variant` |
| 1.4 | NBT backing map: `HashMap` → array/hash hybrid + key interning | FoamFix `FoamNBTTagCompound`, LoliASM `LoliTagMap`, Hydrogen `MixinNbtCompound` | `NBTTagCompound.tagMap` |
| 1.5 | Quad vertex-data pooling | Hydrogen `MixinBakedQuad`, LoliASM `LoliVertexDataPool` | `BakedQuad.vertexData` |
| 1.6 | Baked model list compaction | Hydrogen `MixinBasicBakedModel` / `MixinWeightedBakedModel` | `SimpleBakedModel`, `WeightedBakedModel` |
| 1.7 | Multipart selector map compaction | Hydrogen `MixinMultipartBakedModel` | `MultipartBakedModel.selectors` |
| 1.8 | Multipart condition flattening + interning | Hydrogen `state/`, FerriteCore, LoliASM `CanonicalConditions` | `ConditionAnd/Or/PropertyValue` |
| 1.9 | Pool lifecycle tied to resource reload | LoliASM | `ModelManager.onResourceManagerReload` |

### Hydrogen coverage audit

Every file in `hydrogen-fabric`, accounted for. Hydrogen is 14 mixins plus support classes.

| Hydrogen | Coartatio | Status |
|---|---|---|
| `util.MixinIdentifier` | `ResourceLocationMixin` | ported |
| `client.model.MixinModelIdentifier` | `ModelResourceLocationMixin` | ported, **variant interned whole** rather than split into a property array — 1.12.2 variants are dominated by `normal`/`inventory`, so splitting adds a `String[]` header per instance for little gain. Revisit as 3.5 with measurements. |
| `nbt.MixinNbtCompound` | `NBTTagCompoundMixin` + `TagMap` | ported and **improved** — Hydrogen only swaps in `Object2ObjectOpenHashMap`; we add array storage for small compounds and key interning |
| `client.model.MixinBakedQuad` | `CoartatioBakedQuadMixin` | ported |
| `client.model.MixinBasicBakedModel` | `SimpleBakedModelMixin` | ported |
| `client.model.MixinWeightedBakedModel` | `WeightedBakedModelMixin` | ported |
| `client.model.MixinMultipartBakedModel` | `MultipartBakedModelMixin` | ported. Hydrogen also swaps `stateCache`; 1.12.2's `MultipartBakedModel` has no such field, so N/A |
| `...json.MixinAndMultipartModelSelector` | `ConditionAndMixin` | ported |
| `...json.MixinOrMultipartModelSelector` | `ConditionOrMixin` | ported |
| `...json.MixinSimpleMultipartModelSelector` | `ConditionPropertyValueMixin` | ported, plus FerriteCore-style interning Hydrogen lacks |
| `state.MixinState` (`withTable`) | `PropertyValueMapper` | **superseded** — Hydrogen de-duplicates the table's arrays; we delete the table |
| `state.MixinState` (`entries`) | — | **gap → 2.5** |
| `client.model.MixinModelLoader` | — | **gap → 3.1** |
| `chunk.MixinChunkSerializer` | — | **gap → 4.1** |
| `chunk.MixinWorldChunk` | — | **gap → 4.2** |
| `FixedArrayList`, `CollectionHelper` | same | ported |
| `ImmutablePairArrayList` | `ArrayBackedLinkedMap` | adapted — the 1.12.2 field is a `Map`, not a `List<Pair>` |
| `DeduplicationCache`, `IdentifierCaches`, `ModelCaches` | same | ported, plus size caps and phase scoping |
| `AllPredicate`/`AnyPredicate` | `CompositePredicate` | merged into one class |
| `SingleMatchOne`/`SingleMatchAny` | same | ported |
| `AllMatchOneObject` | `AllMatchOne` | ported |
| `AllMatchOneBoolean` | — | **skipped deliberately.** A `boolean[]` saves ~12 bytes over `Object[]` on a predicate set that is interned down to a few hundred instances. Not worth a second code path. |
| `AllMatchAnyObject` | — | **skipped deliberately.** Falls back to `CompositePredicate` over interned `SingleMatchAny`s; flattening AND-of-OR saves one indirection on a rare shape. |
| `FastImmutableTable(Cache)`, `StatePropertyTableCache` | — | superseded by `PropertyValueMapper` |
| `ClassDefineTool`, `ClassConstructors`, `Hydrogen*ImmutableMap*` | — | **gap → 2.5** |
| `ModelCacheReloadListener` | `ModelManagerMixin` | ported |

Four real gaps remain, in value order: **3.1** (`ModelLoader.stateModels` alone holds ~21k entries in
a `HashMap` on the measured pack, alongside `ModelBakery`'s eight more), **4.1**, **2.5**, **4.2**.

### Phase 2 — block state compression

The big one. In a 300-mod pack `BlockStateContainer` is routinely 250–400 MB of live heap, and
almost all of it is the per-state `ImmutableTable propertyValueTable` plus the per-state
`ImmutableMap properties`.

| # | Feature | Origin | Status |
|---|---------|--------|--------|
| 2.1 | `PropertyValueMapper` — bit-pack every property of a block into one `int`, share one `IBlockState[]` per block | FoamFix, FerriteCore | **[done]** |
| 2.2 | `CoartatioBlockState extends StateImplementation` holding that `int` | FoamFix `FoamyBlockState` | **[done]** |
| 2.3 | `CoartatioExtendedBlockState` for Forge's `ExtendedBlockState` | FoamFix | **[done]** |
| 2.4 | Lazy `getPropertyValueTable()` rebuild | *new* | **[done]** |
| 2.5 | Compact `ImmutableMap` for `StateImplementation.properties` | Hydrogen `HydrogenImmutableReferenceHashMap` | **[next]** — needs `ClassDefineTool` (§4) |
| 2.6 | Blanket bail-out list | FoamFix | **[done]** — `blockStateBlacklist`, defaults to `jds.bibliocraft` |

Shipped behind `optimizeBlockStates`, **default off**. It is the only feature that changes the
identity of objects the whole game holds references to, so it wants a deliberate opt-in and a
testing session before the default flips.

**No access transformers were needed** — verified by compiling against
`build/rfg/recompiled_minecraft-1.12.2.jar`, where `StateImplementation` is already `public` with a
`protected` two-argument constructor and a `protected propertyValueTable`. Forge's own
`merged_at.cfg` is what publishes them:

```
public    net.minecraft.block.state.BlockStateContainer$StateImplementation
protected net.minecraft.block.state.BlockStateContainer$StateImplementation <init>(Block, ImmutableMap)
protected net.minecraft.block.state.BlockStateContainer$StateImplementation field_177238_c  # propertyValueTable
```

**No coremod either.** FoamFix rewrites `new BlockStateContainer(...)` call sites with a
`ConstructorReplacingTransformer` so it can substitute its own *container* subclass. That is
unnecessary here: `createState` is a Forge-added extension point, so a `@Inject(cancellable = true)`
at its head substitutes the *state* and leaves the container vanilla. Both injections carry
`remap = false` — methods Forge adds by patch are not in the obfuscation map.

Three deviations from FoamFix worth keeping straight:

- **Value indices resolve through the block's own property instance.** FoamFix keys its per-property
  value tables by identity, so an equal-but-distinct `IProperty` — which vanilla accepts, since the
  state's property map is `equals`-keyed — silently builds a second mapping with a possibly
  different ordering. Here the property *name* resolves to this block's entry and the value is
  looked up there.
- **Allocation is bounded.** Bit ranges pad to powers of two, so the shared array can exceed the
  state count. FoamFix only rejects past 31 bits, which permits a multi-gigabyte array; this rejects
  anything over 2²⁰ slots and leaves that block on vanilla states.
- **`getPropertyValueTable()` still works.** FoamFix returns `null` and breaks any mod reading the
  table — a recurring crash, because it is public API on `IBlockProperties`. Coartatio rebuilds it
  on demand from the mapper and caches it into the inherited field. That costs vanilla's memory, but
  only for the states somebody actually asks about; `PropertyValueMapper.statistics()` reports the
  count so we can see whether it is being abused.

### Phase 3 — model graph  **[next]**

| # | Feature | Origin |
|---|---------|--------|
| 3.1 | `ModelBakery`/`ModelLoader` map swaps (`HashMap` → `Object2ObjectOpenHashMap`) | Hydrogen `MixinModelLoader` |
| 3.2 | Post-bake deduplicator: `ItemCameraTransforms`, `ItemOverrideList`, `float[][]`, `ImmutableList` singletons | FoamFix `Deduplicator`, LoliASM | 
| 3.3 | `MultipartBakedModel` instance interning | LoliASM `MultipartBakedModelCache` |
| 3.4 | Drop `ModelBakery` intermediate state after bake | FoamFix `ModelLoaderCleanup`, ModernFix |
| 3.5 | `ModelResourceLocation` property-array split | Hydrogen | Measure first — may be a net loss at 1.12.2 variant counts |

### Phase 4 — world and runtime  **[next]**

| # | Feature | Origin |
|---|---------|--------|
| 4.1 | Strip pending-chunk NBT down to `Entities`/`TileEntities` | Hydrogen `MixinChunkSerializer` | `AnvilChunkLoader.chunksToRemove` retains whole chunk tags |
| 4.2 | Null out empty `ExtendedBlockStorage` after load | Hydrogen `MixinWorldChunk` |
| 4.3 | `ClassInheritanceMultiMap` array backing | FoamFix `FoamyClassInheritanceMultiMap` | Impetus already mixes this class for `forEach` |
| 4.4 | `EntityDataManager` array-backed map | FoamFix `FoamyArrayBackedDataManagerMap` |
| 4.5 | `ItemStack` capability lazy-init | LoliASM |

### Phase 5 — instrumentation and polish  **[hold]**

Heap-diff harness, `/impetus coartatio stats` command, per-feature savings in the F3 overlay,
opt-in aggressive tier.

---

## 4. The one genuinely dirty part

Hydrogen's own README calls its Guava trick "things too dirty to put in Lithium". Phase 2.5 needs
a subclass of `com.google.common.collect.ImmutableMap`, whose constructor is package-private. The
class therefore has to be *defined into the same runtime package* as Guava — same package name and
same classloader.

`ClassDefineTool` handles this with a three-tier fallback:

1. `MethodHandles.privateLookupIn(...).defineClass(bytes)` — Java 9+, used when running under
   lwjgl3ify/RetroFuturaBootstrap.
2. `ClassLoader.defineClass` via `setAccessible` — Java 8, the normal 1.12.2 case.
3. Give up, log once, and leave the feature disabled.

Two hard rules for anything injected this way:

- **No Coartatio imports.** The injected class may only reference Guava and `java.*`. If Guava
  turns out to be loaded by a parent classloader that cannot see our jar, an import would produce
  `NoClassDefFoundError` at first use rather than a clean startup failure.
- **Define in reverse dependency order**, innermost helper first, so a partially-completed
  injection can never leave a half-linked class behind.

Everything else in Coartatio is ordinary Mixin work and degrades to vanilla behaviour when disabled.

---

## 5. Correctness rules

These are the invariants that make the difference between "smaller heap" and "corrupted world".

1. **Pooled arrays are immutable.** `BakedQuad.vertexData` is only pooled when
   `getClass() == BakedQuad.class`. `UnpackedBakedQuad` re-packs its array lazily and
   `BakedQuadRetextured` rewrites UVs in its constructor *after* `super(...)` returns — pooling
   either one silently corrupts every quad sharing that array. This is the single most dangerous
   thing in the mod and the check is deliberately conservative.
2. **Pools are bounded.** `ResourceLocation` paths are not a closed set (skin downloads, dynamic
   registries). Every pool takes a cap; past it, dedup stops and the pool stops growing.
3. **Pools do not outlive their phase.** Model-bake pools are dropped at the end of the reload that
   filled them. Dropping the pool does not drop the arrays — quads keep their canonical instance,
   we just stop paying for the hash set.
4. **`equals`/`hashCode` contracts are preserved.** Interning strings must not change
   `ResourceLocation.equals`; replacing `tagMap` must not change `NBTTagCompound.equals`. Every
   replacement collection implements the `Map`/`List` contract, including cross-implementation
   equality.
5. **Every feature is individually switchable**, and a disabled feature does not load its mixin.
6. **Every `@Shadow` name comes from `mcp-srg.srg`, never from a decompile.** See §7.

---

## 7. Mapping names are not verifiable by the compiler

A `@Shadow` naming a field that does not exist on the target **compiles cleanly**. The mixin simply
declares a field of its own; nothing resolves it against the target until Mixin applies the config
at runtime, at which point the game dies during startup. The reobfuscator does not catch it either —
an unmatched name is indistinguishable from a mixin's own private field, so it passes the name
through unmapped and says nothing.

This shipped once already. `ResourceLocation`'s fields are `resourceDomain`/`resourcePath` in the
OptiFine decompile used as a reference, but this project builds against `mcp_stable/39`, where they
are:

```
FD: net/minecraft/util/ResourceLocation/namespace  net/minecraft/util/ResourceLocation/field_110626_a
FD: net/minecraft/util/ResourceLocation/path       net/minecraft/util/ResourceLocation/field_110625_b
```

Compiled clean, crashed on launch, and every other mixin in the subsystem had remapped correctly —
which is what made it findable: the reobfuscated jar showed twelve `field_*` shadows and two that
had kept their source names.

**`tools/shadowcheck.py` now checks this.** It walks the compiled mixin classes, reads each
`@Mixin` target, and verifies every `@Shadow` member exists on that target in the MCP-named dev jar.
Run it after any build that touches mixins:

```
tools/shadowcheck.py                              # coartatio (default)
tools/shadowcheck.py com.bdmajora.impetus.mixin   # renderer
```

The check is verified against the real defect: reintroducing the old field names makes it fail with
both names, while `compileJava` still succeeds.

Two rules follow, and they matter increasingly as Phases 3 and 4 add shadows:

- **Look names up in `mcp-srg.srg`**, at
  `~/.gradle/caches/minecraft/de/oceanlabs/mcp/mcp_stable/39/rfg_srgs/mcp-srg.srg`. A decompiled
  source tree is fine for understanding *what a class does* and useless for *what its members are
  called*.
- **Members Forge adds by patch keep their source names** and must carry `remap = false` in mixin
  annotations. `createState` and `getPropertyValueTable` are both in this category; confirm by
  grepping the SRG and finding nothing.

---

## 6. Sequencing

```
Phase 1  ─── done ───────────────────────────────────────────────┐
Phase 2  2.1 2.2 2.3 2.4 2.6 done → verify → flip default → 2.5  │ largest win, highest risk
Phase 3  3.1 → 3.4 → 3.2 → 3.3 → (measure) 3.5                   │ needs Phase 1 pools in place
Phase 4  4.1 → 4.2 → 4.3 → 4.4 → 4.5                             │ independent of 2 and 3
Phase 5  after 2–4 land                                          │
```

Phases 3 and 4 are independent of Phase 2 and can be worked in any order.

**Phase 2 verification order**, before `optimizeBlockStates` defaults to on:

1. Boot vanilla-only with the flag on; confirm `PropertyValueMapper.statistics()` reports every
   block packed and nothing skipped unexpectedly.
2. Place, break, rotate and update-tick blocks with many properties — fences, redstone, doors,
   stairs — which is what exercises `withProperty` and the packed layout.
3. Load a pack with heavy `ExtendedBlockState` use (any pipe or cable mod) to exercise
   `CoartatioExtendedBlockState`'s clean/dirty transitions.
4. Watch for `tables rebuilt on demand` climbing in the statistics line; a large number means some
   mod reads `getPropertyValueTable()` per state and is undoing the saving.

A Phase 2 failure and a Phase 1 failure look identical in a crash log, so keep the flag off until
Phase 1 has survived a full modpack load on its own.
