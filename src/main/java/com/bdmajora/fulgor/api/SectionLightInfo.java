package com.bdmajora.fulgor.api;

// Implemented by ExtendedBlockStorage; Fulgor redefines isEmpty()'s "send to client" meaning (MC-116690), and this preserves the original "no blocks" meaning for the renderer
public interface SectionLightInfo {
    boolean fulgor$hasNoBlocks();
}
