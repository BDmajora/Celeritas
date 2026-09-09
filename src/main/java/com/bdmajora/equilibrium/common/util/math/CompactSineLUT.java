package com.bdmajora.equilibrium.common.util.math;

import net.minecraft.util.math.MathHelper;

// a replacement for the sine lookup table in MathHelper, reducing its size and improving the access
// pattern for the paired sin/cos calls that dominate its callers
// two identities do the work: sin(-x) = -sin(x), which removes the negative half of the domain, and
// sin(x) = sin(pi/2 - x), which removes the supplementary angles
// together they take the table from 65 536 entries (256 KB) down to 16 384 (64 KB), small enough to
// stay resident in L2 rather than being streamed from memory every time an entity rotates
// the reconstruction is branch-free integer arithmetic, so the cycles spent rebuilding the discarded
// quadrants cost far less than the cache misses they avoid
// unlike BetterFps' math algorithms - which trade accuracy for speed and offer a menu of how much
// accuracy to give up - the values here are *bit-for-bit identical* to vanilla's
// that matters more than it might seem: entity positions, projectile arcs and explosion ray directions
// all run through sin, and a client that computes them differently from the server desyncs
// init(float[]) verifies all 65 536 reconstructed values against the vanilla table before it is
// discarded, so a mistake here fails loudly at startup rather than as a rubber-banding bug an hour
// into a session
// coderbot16 wrote the original implementation in Rust (https://gitlab.com/coderbot16/i73/-/tree/master/i73-trig/src)
// jellysquid3 added further optimisations and the port to Java, in Lithium
public class CompactSineLUT {
    // raw float bits rather than floats
    // the sign flip that reconstructs the negative half is a single XOR on the sign bit, which is only
    // expressible on the integer representation, so storing ints avoids converting back and forth
    private static final int[] SINE_TABLE_INT = new int[16384 + 1];

    // sin(pi), the one index neither identity can reach.
    private static float sineTableMidpoint;

    private CompactSineLUT() {
    }

    // builds the compact table from vanilla's, and proves the two agree
    // called from the end of MathHelper's static initialiser, the only moment at which the vanilla
    // table is both fully populated and not yet used by anything
    // vanilla is that fully populated 65 536-entry table
    public static void init(float[] vanilla) {
        if (vanilla == null || vanilla.length != 65536) {
            throw new IllegalStateException("Expected a 65536-entry vanilla sine table, found "
                    + (vanilla == null ? "null" : vanilla.length + " entries"));
        }

        for (int i = 0; i < SINE_TABLE_INT.length; i++) {
            SINE_TABLE_INT[i] = Float.floatToRawIntBits(vanilla[i]);
        }

        sineTableMidpoint = vanilla[vanilla.length / 2];

        for (int i = 0; i < vanilla.length; i++) {
            float expected = vanilla[i];
            float value = lookup(i);

            if (expected != value) {
                throw new IllegalStateException(String.format(
                        "Sine LUT error at index %d (expected: %s, found: %s)", i, expected, value));
            }
        }
    }

    // [VanillaCopy] MathHelper#sin(float)
    public static float sin(float value) {
        return lookup((int) (value * 10430.378F) & 65535);
    }

    // [VanillaCopy] MathHelper#cos(float)
    public static float cos(float value) {
        return lookup((int) (value * 10430.378F + 16384.0F) & 65535);
    }

    private static float lookup(int index) {
        // sin(pi) is its own supplement and its own negation, so neither identity produces it.
        if (index == 32768) {
            return sineTableMidpoint;
        }

        // sin(-x) = -sin(x). Over a domain of 0 <= x <= 2*pi, negate whenever x > pi. Shifting the
        // 15th bit up to the sign bit gives the mask to XOR the result with, with no branch.
        int neg = (index & 0x8000) << 16;

        // All bits set when pi/2 <= x, none otherwise — the 14th bit, sign-extended.
        int mask = (index << 17) >> 31;

        // sin(x) = sin(pi/2 - x), expressed as a conditional reflection about the mask.
        int pos = (0x8001 & mask) + (index ^ mask);

        // Wrapping immediately before the access rather than earlier measurably helps HotSpot fold
        // the preceding bit arithmetic; it is not redundant with the caller's mask.
        pos &= 0x7fff;

        return Float.intBitsToFloat(SINE_TABLE_INT[pos] ^ neg);
    }
}
