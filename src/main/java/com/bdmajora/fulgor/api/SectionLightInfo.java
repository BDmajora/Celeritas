package com.bdmajora.fulgor.api;

// Implemented by ExtendedBlockStorage. Vanilla's isEmpty() counts blocks and is used both for "send to
// client" and "worth rendering"; Fulgor redefines the first (a blockless section with real light data
// still needs sending, MC-116690) which would otherwise wrongly change the second too. This method
// preserves the original "no blocks" meaning for the renderer.
public interface SectionLightInfo {
    boolean fulgor$hasNoBlocks();
}
