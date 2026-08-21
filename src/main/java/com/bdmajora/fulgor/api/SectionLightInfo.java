package com.bdmajora.fulgor.api;

/**
 * Implemented by {@code ExtendedBlockStorage} to keep "holds no blocks" answerable after Fulgor has
 * redefined "is empty".
 *
 * <p>Vanilla's {@code isEmpty()} counts blocks and is used for two unrelated purposes: deciding
 * whether a section is worth sending to the client, and deciding whether it is worth rendering.
 * Fulgor changes the first — a section with no blocks but real light data must still be sent, or the
 * client relights it wrongly (MC-116690) — and that would silently change the second too, handing
 * Impetus' terrain renderer build tasks for sections that can only ever produce empty geometry.
 *
 * <p>So the two questions get two methods. {@code isEmpty()} keeps the name and takes on the new,
 * light-aware meaning that its callers in the chunk-packet path want; this one preserves the original
 * meaning for the renderer.
 */
public interface SectionLightInfo {
    /** True when the section contains no non-air blocks, regardless of what its light arrays hold. */
    boolean fulgor$hasNoBlocks();
}
