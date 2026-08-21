package com.bdmajora.fulgor.lighting;

import com.bdmajora.fulgor.Fulgor;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.IBlockAccess;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Reaches AtomicStryker's Dynamic Lights without compiling against it.
 *
 * <p>Dynamic Lights reports luminance for things that are not blocks at all — an entity holding a
 * torch, a dropped glowstone — by intercepting the light lookup rather than by overriding anything on
 * {@code Block}. So when it is installed the engine cannot ask the block state; it has to ask the mod,
 * which is what Phosphor-Forge and its descendants all do.
 *
 * <p>Bound through a {@link MethodHandle} rather than {@code Method.invoke} because this sits on the
 * per-neighbour path of every light update, and a {@code static final} handle called with
 * {@code invokeExact} inlines like a direct call. If the class or method is missing — a Dynamic Lights
 * fork with a different API — the bridge reports itself unavailable and the engine falls back to
 * vanilla luminance rather than failing.
 */
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
            // invokeExact is declared to throw Throwable, so this catch is unavoidable rather than
            // defensive. Anything arriving here came out of Dynamic Lights; rethrow it with the
            // position attached, since a bare stack trace through a MethodHandle says very little.
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
