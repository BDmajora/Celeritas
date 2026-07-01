package com.l.ausm.impl.pipeline.pack;

import net.minecraft.block.state.IBlockState;
import net.minecraft.util.BlockRenderLayer;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public final class ShaderBlockLayerOverrides {
    private static final AtomicReference<Map<net.minecraft.block.Block, BlockRenderLayer>> ACTIVE_OVERRIDES =
            new AtomicReference<>(java.util.Collections.emptyMap());

    private ShaderBlockLayerOverrides() {
    }

    public static void install(ShaderBlockIdMap.BlockIdRules rules) {
        if (rules == null || rules.layerOverrides().isEmpty()) {
            clear();
            return;
        }
        ACTIVE_OVERRIDES.set(rules.layerOverrides());
    }

    public static void clear() {
        ACTIVE_OVERRIDES.set(java.util.Collections.emptyMap());
    }

    public static BlockRenderLayer layerFor(IBlockState state) {
        if (state == null || state.getBlock() == null) {
            return null;
        }
        return ACTIVE_OVERRIDES.get().get(state.getBlock());
    }
}
