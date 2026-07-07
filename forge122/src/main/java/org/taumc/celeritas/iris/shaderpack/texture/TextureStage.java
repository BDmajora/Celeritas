package org.taumc.celeritas.iris.shaderpack.texture;

import java.util.Optional;

/**
 * The pipeline stage a {@code texture.<stage>.<sampler>} override applies to. Verbatim port of Iris's
 * {@code shaderpack.texture.TextureStage} (which follows OptiFine's shaders.txt custom-texture spec).
 * <p>
 * Celeritas currently runs three of these stages (gbuffers+shadow, deferred, composite+final); the Iris-1.6-exclusive
 * stages are parsed for compatibility so packs declaring them load without warnings, but nothing binds them yet.
 */
public enum TextureStage {
    /** The setup passes. Exclusive to Iris 1.6. */
    SETUP,
    /** The begin pass. Exclusive to Iris 1.6. */
    BEGIN,
    /** The shadowcomp passes. Not documented in shaders.txt, but a valid stage for custom textures. */
    SHADOWCOMP,
    /** The prepare passes. Not documented in shaders.txt, but a valid stage for custom textures. */
    PREPARE,
    /** All of the gbuffer passes, as well as the shadow passes. */
    GBUFFERS_AND_SHADOW,
    /** The deferred passes. */
    DEFERRED,
    /** The composite passes and final pass. */
    COMPOSITE_AND_FINAL;

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
