package com.bdmajora.equilibrium.common.util.math;

import net.minecraft.util.math.MathHelper;

// Replaces MathHelper's 65 536-entry sine table with 16 384 entries via sin(-x) = -sin(x) and sin(x) = sin(pi/2 - x) so it stays in L2; bit-for-bit identical to vanilla since sin drives positions and a differing client desyncs (coderbot16's Rust, jellysquid3's Lithium port)
public class CompactSineLUT {
    // Raw float bits, because the sign flip that rebuilds the negative half is an XOR on the sign bit
    private static final int[] SINE_TABLE_INT = new int[16384 + 1];

    // sin(pi), the one index neither identity can reach.
    private static float sineTableMidpoint;

    // Static-only
    private CompactSineLUT() {
    }

    // Builds the compact table from vanilla's and verifies all 65 536 values agree; called at the end of MathHelper's static init, the only point vanilla's table is full and unused
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

    // Rebuilds any of the four quadrants from the stored one using branch-free integer arithmetic
    private static float lookup(int index) {
        // sin(pi) is its own supplement and its own negation, so neither identity produces it.
        if (index == 32768) {
            return sineTableMidpoint;
        }

        // sin(-x) = -sin(x): negate when x > pi by shifting the 15th bit up to the sign bit and XORing, with no branch
        int neg = (index & 0x8000) << 16;

        // All bits set when pi/2 <= x, none otherwise — the 14th bit, sign-extended.
        int mask = (index << 17) >> 31;

        // sin(x) = sin(pi/2 - x), expressed as a conditional reflection about the mask.
        int pos = (0x8001 & mask) + (index ^ mask);

        // Wrapping right before the access rather than earlier measurably helps HotSpot fold the preceding bit arithmetic; not redundant with the caller's mask
        pos &= 0x7fff;

        return Float.intBitsToFloat(SINE_TABLE_INT[pos] ^ neg);
    }
}
