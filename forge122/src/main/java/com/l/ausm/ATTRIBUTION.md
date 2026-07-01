# AUSM — Attribution

The `com.l.ausm` packages in Celeritas are derived from **AUSM (Actually Usable Shader Mod)**
by MtcLuna05 — an LGPL v3 Iris-style shader pipeline for Minecraft 1.12.2.

- Upstream: https://github.com/MtcLuna05/AUSM
- License: GNU LGPL v3 (same family as Celeritas' GPL/LGPL v3 — compatible).
- Upstream's own Iris-derivation trail is preserved in AUSM's `IRIS_PORTING_LOG.md`.

## Changes made while grafting into Celeritas (Forge 1.12.2, Java-8 bytecode via Jabel)

AUSM targets the Cleanroom loader with a Java-21 runtime. To run on Celeritas' Forge 1.12.2 /
Java-8 runtime, the copied sources are mechanically adapted:

- Records annotated with `@com.github.bsideup.jabel.Desugar` (Celeritas' Jabel requirement).
- Java 9+ library APIs rewritten to Java 8 equivalents:
  - `Map.of()/List.of()/Set.of()` → `Collections.empty*()`
  - `Map.copyOf/List.copyOf/Set.copyOf(x)` → `Collections.unmodifiable*(new ...(x))`
  - `String.isBlank()` → `trim().isEmpty()`
  - `Collection.toArray(T[]::new)` → `toArray(new T[0])` (Stream.toArray(T[]::new) is left as-is)
  - `Stream…​.toList()` → `.collect(Collectors.toList())`
- Raw `org.lwjgl.opengl.GL*` calls are kept (valid on Celeritas' LWJGL2 backend); routing them
  through `org.taumc.celeritas.lwjgl` for lwjgl3ify parity is a later pass.
- AUSM's terrain path (vanilla fixed-function `RenderChunk`/`RenderGlobal`) is replaced by a
  bespoke bridge into Celeritas' Embeddium chunk renderer.
- Excluded: AUSM's mod-compat mixins (`impl/mixin/compat`) and its GUI (Celeritas has its own
  shader-pack selector); AUSM's `@Mod`/coremod bootstrap (Celeritas' loader drives init).
