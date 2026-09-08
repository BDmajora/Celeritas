package com.bdmajora.fulgor.lighting;

import com.bdmajora.fulgor.Fulgor;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

// Reaches AtomicStryker's Dynamic Lights without compiling against it; it reports luminance for
// non-block light sources (held torches, dropped glowstone) by intercepting the lookup itself.
// Bound via MethodHandle+invokeExact (inlines like a direct call) since this runs per-neighbour on
// every light update; missing class/method just makes the bridge unavailable, falling back to vanilla.
final class DynamicLightsBridge {
    private static final String CLASS_NAME = "atomicstryker.dynamiclights.client.DynamicLights";

    private static final MethodHandle GET_LIGHT_VALUE = resolve();

    private DynamicLightsBridge() {
    }

    static boolean isAvailable() {
        return GET_LIGHT_VALUE != null;
    }

    static int getLightValue(IBlockState state, IBlockAccess world, BlockPos pos) {
        try {
            return (int) GET_LIGHT_VALUE.invokeExact(state.getBlock(), state, world, pos);
        } catch (Throwable t) {
            // invokeExact forces this catch; rethrow with the position since a bare MethodHandle
            // stack trace is useless
            throw new IllegalStateException("Dynamic Lights threw while reporting luminance at " + pos, t);
        }
    }

    private static MethodHandle resolve() {
        if (!Fulgor.hasDynamicLights()) {
            return null;
        }

        try {
            Class<?> clazz = Class.forName(CLASS_NAME);
            MethodType type = MethodType.methodType(int.class, Block.class, IBlockState.class,
                    IBlockAccess.class, BlockPos.class);

            return MethodHandles.lookup().findStatic(clazz, "getLightValue", type);
        } catch (ReflectiveOperationException | LinkageError e) {
            Fulgor.LOGGER.warn("Dynamic Lights is installed but {}.getLightValue could not be bound; "
                    + "block luminance will come from the block state instead", CLASS_NAME, e);
            return null;
        }
    }
}
