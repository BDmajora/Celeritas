package com.bdmajora.impetus.umbra.shaderpack.texture;

import java.util.Optional;

// The pipeline stage a `texture.<stage>.<sampler>` override applies to
// Verbatim port of Iris's shaderpack.texture.TextureStage, which follows OptiFine's shaders.txt custom-texture spec
// Only three of these are actually bound here — gbuffers+shadow, deferred, and composite+final. The rest are parsed
// so a pack declaring them loads cleanly instead of warning, but nothing binds them yet
public enum TextureStage {
    // The setup passes; Iris 1.6 exclusive
    SETUP,
    // The begin pass; Iris 1.6 exclusive
    BEGIN,
    // The shadowcomp passes — undocumented in shaders.txt but a valid stage that packs do use
    SHADOWCOMP,
    // The prepare passes; likewise undocumented but valid
    PREPARE,
    // Every gbuffer pass AND the shadow passes — one stage, because OptiFine treats them together
    GBUFFERS_AND_SHADOW,
    // The deferred passes
    DEFERRED,
    // The composite passes and the final pass, again grouped as OptiFine groups them
    COMPOSITE_AND_FINAL;

    // gbuffers, deferred, composite or final; empty for anything else
    public static Optional<TextureStage> parse(String name) {
        switch (name) {
            case "setup":
                return Optional.of(SETUP);
            case "begin":
                return Optional.of(BEGIN);
            case "shadowcomp":
                return Optional.of(SHADOWCOMP);
            case "prepare":
                return Optional.of(PREPARE);
            case "gbuffers":
                return Optional.of(GBUFFERS_AND_SHADOW);
            case "deferred":
                return Optional.of(DEFERRED);
            case "composite":
                return Optional.of(COMPOSITE_AND_FINAL);
            default:
                return Optional.empty();
        }
    }
}
