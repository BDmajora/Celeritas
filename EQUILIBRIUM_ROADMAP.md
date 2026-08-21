# Equilibrium — Impetus' general performance subsystem

Equilibrium is a backport of [Lithium](https://github.com/CaffeineMC/lithium) to 1.12.2. It occupies
the place BetterFps and OptiFine's non-rendering patches hold on this version, but it takes Lithium's
algorithms and — more importantly — Lithium's rule: **an optimization that changes observable
behaviour is a bug, not a trade-off.**

It is the fourth subsystem in Impetus, and the four divide the game between them:

| Subsystem      | Upstream           | Owns                              |
| -------------- | ------------------ | --------------------------------- |
| Impetus        | Sodium             | Terrain rendering                 |
| Coartatio      | Hydrogen / FoamFix | Memory and model/state dedup      |
| Fulgor         | Phosphor / Alfheim | Lighting                          |
| **Equilibrium**| **Lithium**        | **Everything the game tick does** |

Where two could plausibly claim a class, the option tree says who wins. Coartatio owns
`BlockStateContainer`, `NBTTagCompound` and chunk serialisation; Fulgor owns `Chunk`'s light methods
and `World.checkLightFor`; Equilibrium owns block access, collisions, ray casting and explosions.

---

## How it works

The architecture is Lithium's, ported directly.

- **`config/EquilibriumOptions`** — the option tree. Every rule is named after the mixin package it
  governs, so `mixin.world.explosions` controls everything under
  `com.bdmajora.equilibrium.mixin.world.explosions`. Adding a mixin under an existing rule needs no
  wiring.
- **`config/Option`, `config/EquilibriumConfig`** — a direct port of Lithium's `Option` and
  `LithiumConfig`: user overrides, mod overrides, a parent-wins-when-disabled resolution walk, and a
  dependency fixpoint that turns off any option whose prerequisites were disabled.
- **`mixin/EquilibriumMixinPlugin`** — a port of `LithiumMixinPlugin`. Resolves each mixin's package
  path against the tree and refuses to apply anything the tree does not recognise.
- **`config/ModCompatibility`** — replaces Lithium's `PlatformMixinOverrides`. Lithium reads a
  `lithium:options` block from mod metadata; on 1.12.2 nothing has parsed any metadata yet when this
  runs, so conflicting mods are detected by class presence instead.

Two divergences from Lithium, both deliberate:

1. **The tree is declared in Java, not generated.** Lithium generates it from `@MixinConfigOption`
   annotations via a Gradle plugin, because it ships three loader-specific option sets. We ship one.
2. **A user override beats a mod override.** Lithium takes the opposite view, but its overrides come
   from metadata the user chose to install; ours come from detection the user never asked for, and
   being silently overruled is worse than a compatibility warning.

### Configuration

`config/equilibrium.properties`, written on first launch with every option, its description, its
default and its dependencies as comments. Options the user has not touched stay commented out, so the
file keeps reporting the current default rather than freezing today's into it.

`-Dequilibrium.disable_all_mixins=true` turns the whole subsystem off for one launch — the fastest
way to answer "is this us" when a modpack crashes on startup. `-Dequilibrium.debug_mixins=true` logs
every apply/skip decision.

### Surfaces

- **Optimizations tab** in Impetus' video options. Built by walking the tree, so it never goes stale.
  Every switch is flagged `REQUIRES_GAME_RESTART`, because every switch is read during coremod setup.
  Kept separate from the existing Performance tab: that one trades fidelity for frame rate and applies
  immediately, this one trades nothing and applies next launch.
- **`/equilibrium`** — what is active, and for anything that is not, who turned it off.
- A summary line per category in the log at init.

---

## What shipped

Each of these was verified against the actual 1.12.2 Forge sources before it was written.

### `mixin.world` — the largest wins on this version

| Option | What it does |
| --- | --- |
| `explosions.block_raycast` | `Explosion.doExplosionA` fires 1352 rays and steps each 0.3 blocks, allocating a `BlockPos` and doing a full `World.getBlockState` per step. At that step size two of every three steps land in the block the previous one did. Now: work happens only when the ray crosses into a new block, and reads go through a chunk-section cursor. |
| `explosions.entity_raycast` | `World.getBlockDensity` traces ~45 rays per entity, all converging on the explosion's centre, and vanilla resolves every block of the shared path from scratch on every ray. Now: one cursor shared across all of them. |
| `raycast` | `World.rayTraceBlocks` allocates a `Vec3d` and a `BlockPos` on each of up to 200 steps. Now: the position is three doubles, and objects are materialised only where block code receives them. This is under mob line-of-sight, player targeting, projectiles and fishing rods. |
| `inline_block_access` | `World.getChunk(int, int)` — the funnel every block, tile-entity and light read passes through — consults a validated one-entry cache before the provider's hash map. |
| `inline_height` | `World.getHeight` tested whether the chunk was loaded and then fetched the chunk that test had just found. Now one lookup. |
| `tick_scheduler` | The pending scheduled-tick set becomes a fastutil open hash set. One fewer allocation per scheduled tick, and the duplicate check stops chasing a pointer. |
| `block_entity_ticking.sleeping.brewing_stand` | `TileEntityBrewingStand.update` calls `canBrew()` unconditionally, and Forge routes that into `BrewingRecipeRegistry.canBrew`, which walks every brewing recipe in the pack. An empty stand now answers from its own ingredient slot, which is the first thing the registry checks anyway. |
| `block_entity_ticking.sleeping.furnace` | A furnace that is unlit, has nothing part-cooked, and is missing either fuel or input skips its tick. Every branch it would have taken is a no-op. |

### `mixin.block.hopper`

The single worst tick cost in a 1.12.2 storage system, and the one that took the most care to get
right.

A hopper resolves the inventory it faces and the one above it on **every** transfer attempt — which
is every tick, not every eight, because the cooldown is only set after a *successful* transfer. Each
resolution is a block read, a tile entity lookup that constructs a tile entity as a side effect if the
block wants one and has none, and — whenever neither turns up an inventory — a full entity query over
the block. Two idle hoppers side by side run six entity queries a tick between them; a storage hall
runs thousands, forever, to discover nothing.

What is cached and what deliberately is not:

- **Cached:** the tile entity, keyed on the identity of the block state that was there when it was
  found. Block states are singletons, so the comparison is a field load and it answers "has this block
  changed" exactly. An invalidated tile entity fails a separate check, covering removal that leaves
  the state unchanged.
- **Not cached — chests.** `BlockChest.getContainer` inspects all four horizontal neighbours to decide
  whether it is looking at half of a double chest, and placing the other half changes neither chest's
  block state. A cache keyed on state would keep serving a single-chest view of a double chest. Chests
  take the vanilla path every time.
- **Not cached — the entity fallback.** A chest minecart can roll into the block without anything
  nearby changing. Instead of caching it, the whole query is skipped when the world provably holds no
  live `IInventory` entity.

That last point is where the correctness risk lives, and it is worth stating how it is closed.
`World.onEntityAdded` and `onEntityRemoved` look like a matched pair and are not: `loadEntities` skips
the former when Forge's `EntityJoinWorldEvent` is cancelled, while `unloadEntities` queues everything
for the latter regardless. A plain counter would drift *below* the truth and hoppers would silently
stop seeing chest minecarts — a bug that surfaces hours later in someone's world. So the entity itself
records which world counted it. A world only decrements for an entity that names it, and an entity
appearing in a second world increments that world regardless. Under-counting is structurally
impossible; over-counting is possible and costs nothing but the vanilla query it was avoiding.

`getInventoryAtPosition` is left alone — it is public, static, and called by droppers, dispensers and
a great deal of mod code with nowhere to hang a cache. Hopper minecarts also take the uncached path,
since a cache keyed on a fixed position is meaningless for something that moves; they still get the
entity-query skip, which is the part that was costing them.

### The rest

| Option | What it does |
| --- | --- |
| `entity.collisions.movement` | `World.getCollisionBoxes` nests x, z, y — so consecutive reads run down a column and sixteen of them share a chunk section. Now resolved once per column instead of once per block. |
| `entity.fast_retrieval` | Entity queries tested `isChunkLoaded` and then called `getChunk`, two traversals of the chunk provider per column. Now one. |
| `entity.fast_hand_swing` | Skips the swing update for entities that are not swinging. The division vanilla performs needs the swing duration, and that needs two potion-effect lookups. |
| `entity.fast_elytra_check` | Every living entity wrote flag 7 every tick to record that it is still not elytra flying. The write was already a no-op inside the data manager; the table lookup it needed was not. |
| `chunk.no_validation` | `Chunk.getBlockState` asked the world its type on every block read, to find out whether it is the debug world. Resolved once at construction instead. |
| `math.sine_lut` | Lithium's `CompactSineLUT`: a 64 KB sine table reconstructing the 256 KB one through trigonometric identities. Bit-for-bit identical to vanilla, and all 65 536 entries are verified at startup before the original is dropped. |
| `math.fast_util` | `EnumFacing.getOpposite` loses a modulo; `EnumFacing.random` stops cloning the values array twice per call. |
| `math.fast_blockpos` | Each direction's offsets become fields instead of being derived from its axis on every read; `BlockPos.up/down/north/...` construct directly. |
| `alloc.enum_values.*` | Piston extension, piston structure resolution and redstone wire stop copying `EnumFacing.values()` — respectively per neighbour update, per moved block, and per wire per power level. |
| `alloc.entity_tracker` | Tracked players move to a `ReferenceOpenHashSet`. Identity already *is* equality for `EntityPlayerMP`. |
| `alloc.deep_passengers` | Collecting an entity's passengers stops allocating a `HashMap` for the overwhelming majority of entities, which carry nobody. |
| `block.redstone_wire` | `calculateCurrentChanges` read each horizontal neighbour twice and re-read the block above the wire once per direction. Now once each, with the block above resolved lazily so a flat dust line never pays for it. |
| `collections.mob_spawning` | The eligible-chunk set becomes a fastutil open hash set. It is rebuilt every spawn cycle and probed once per candidate chunk per player. |
| `ai.goal_selector` | The two AI task sets become `ObjectLinkedOpenHashSet`. Insertion order is preserved, which matters — it is how 1.12.2 expresses goal priority. |
| `util.chunk_access` | Support layer. Gives `World` the validated chunk cache the rest of the above reads through. |
| `util.block_entity_retrieval` | Support layer. Looks up an existing tile entity without constructing one as a side effect of asking. |
| `util.data_storage` | Support layer. The per-world inventory-entity count described above. |

#### On the chunk cache and threads

The cache is a single unsynchronised reference, and that is safe because it is *validated*, not
trusted: the coordinates it is checked against come from the chunk object itself. A torn or stale read
fails the comparison and falls through to the provider, so the worst outcome of a race is a cache
miss. It also clears `unloadQueued` on a hit, exactly as `ChunkProviderServer.getLoadedChunk` does —
that flag is how the server learns a chunk is still in use, and a cache that skipped it would let
chunks unload out from under the code reading them.

---

## What did not survive the backport, and why

Each of these was checked against the 1.12.2 sources, not assumed.

### No 1.12.2 counterpart exists

`shapes.*` (no `VoxelShape` — 1.12.2 uses plain AABBs), `ai.sensor.*`, `ai.task.*`, `ai.poi.*`,
`ai.useless_behaviors`, `ai.useless_sensors`, `collections.brain` (the brain/sensor AI system arrived
in 1.14), `ai.raid` (1.14), `collections.chunk_tickets` and
`minimal_nonvanilla.world.expiring_chunk_tickets` (the ticket system is 1.14), `world.game_events`
(1.19), `entity.fast_powder_snow_check` (1.17), `block.flatten_states` (flattening is 1.13),
`block_entity_ticking.sleeping.{campfire,crafter,sculk_*}`, `chunk.no_locking`
(`BlockStateContainer` does not lock on 1.12.2), `entity.inactive_navigations` (1.12.2's `World` keeps
no navigation listener list), `world.temperature_cache` (1.12.2's `Biome.getTemperature` has no cache;
the 1024-entry map Lithium deletes was added later).

### The structure Lithium installs is already there

These are not judgement calls about whether the win is worth having — the specific data structure or
early-out that Lithium's patch introduces is already what 1.12.2 ships.

- **`world.chunk_ticking.random_block_ticking`** — `ExtendedBlockStorage` has counted its
  randomly-tickable blocks in `tickRefCount` since 1.8, and `WorldServer.updateBlocks` already skips
  sections where it is zero.
- **`chunk.palette`** — `BlockStatePaletteHashMap` is backed by `IntIdentityHashBiMap`, which is
  already an open-addressed identity map. `BlockStatePaletteLinear` scans at most sixteen references.
  Bolting a hash map onto either would most likely be slower, and shipping a switch that makes things
  worse is not better than shipping no switch.
- **`entity.fast_retrieval` (chunk-internal half)** — `Chunk.getEntitiesWithinAABBForEntity` already
  clamps to the section range the query box covers. The half that *was* missing — the doubled chunk
  lookup in `World` — did ship, above.
- **`block.fluid.flow`** — `BlockLiquid.getFlow` already samples through a pooled mutable position.
- **`profiler`** — every `Profiler` entry point already returns immediately when `profilingEnabled` is
  false.
- **`alloc.chunk_random`** — 1.12.2's random tick loop predates the allocating implementation Lithium
  replaces.
- **`chunk.serialization`** — Coartatio owns `AnvilChunkLoader` in this project.
- **`collections.entity_filtering`** — Coartatio owns `ClassInheritanceMultiMap`.

### Still outstanding

- **`ai.pathing`** — `WalkNodeProcessor.getPathNodeType` resolves a block's node type through a chain
  of `instanceof` tests, once per candidate node. Lithium replaces it with a per-block table. This is
  a real and sizeable win on 1.12.2 and it is the next thing to build; it is not here because it wants
  a cache keyed on `Block` with a correct invalidation story for mods that vary node type by state.
- **`block_pattern_matching`**, **`gen.cached_generator_settings`**,
  **`world.combined_heightmap_update`**, **`entity.framed_maps`** — each has a plausible 1.12.2
  target that was not measured closely enough to write against.
- **`entity.sprinting_particles`** — on 1.12.2 `WorldServer.spawnParticle` broadcasts to nearby
  players, so the server-side call is not discarded the way it is on modern versions. Skipping it
  would remove particles other players see, which makes it a behaviour change rather than an
  optimization.

## Interactions with other mods

`ModCompatibility` disables options rather than fighting for the same method:

| Mod | Detected by | Disables |
| --- | --- | --- |
| Cubic Chunks | `CubicChunksCoreContainer` | `chunk`, `util.chunk_access`, `world.inline_block_access`, `entity.collisions`, and the rest of the chunk-shaped assumptions |
| SpongeForge | `SpongeImpl` | `world.tick_scheduler`, `world.explosions`, and the block-entity/inventory options |
| BetterFps | `BetterFpsTweaker` | `math.sine_lut` — its math transformer rewrites the same two methods |
| FoamFix | `FoamFixCore` | `chunk.palette` (currently unused; reserved) |

Adding an entry is one line in `ModCompatibility.CONFLICTS`.

---

## Testing this port

Nothing here has been run yet — it was written against the decompiled 1.12.2 Forge sources in
`build/rfg/minecraft-src`. Before trusting it:

1. Build, and read the log for `Loaded configuration file for Equilibrium: N options available`. Any
   mixin that failed to apply names itself there.
2. `/equilibrium` in-game to confirm the expected set is active.
3. **Explosions** are the highest-risk change and the easiest to check: light TNT next to a
   half-destroyed structure and confirm the crater matches an unmodded instance. `doExplosionA` is
   fully overwritten, so a mistake shows up as a differently-shaped hole.
4. **Ray casting** — block targeting, mob line-of-sight, fishing. `rayTraceBlocks` decides where a
   player's crosshair lands; a traversal bug shows up as blocks being selected from the wrong face.
5. **Redstone** — `calculateCurrentChanges` is rewritten. Build a dust line over a solid block with a
   block above it and confirm power still climbs and drops correctly.
6. **`math.sine_lut`** verifies itself: a wrong table throws at startup rather than desyncing later.
