package com.bdmajora.impetus.engine.impl.util.rand;

import it.unimi.dsi.fastutil.HashCommon;

// Minimal SplitMixRandom from DSI Utilities (http://xoshiro.di.unimi.it/) to avoid importing Apache Commons
public class SplitMixRandom {
    private static final long PHI = 0x9E3779B97F4A7C15L;

    private long x;

    public SplitMixRandom() {
        this(XoRoShiRoRandom.randomSeed());
    }

    public SplitMixRandom(final long seed) {
        this.setSeed(seed);
    }

    // Stafford's variant 13 finaliser
    private static long staffordMix13(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;

        return z ^ (z >>> 31);
    }

    // Variant 4, upper 32 bits only, for nextInt
    private static int staffordMix4Upper32(long z) {
        z = (z ^ (z >>> 33)) * 0x62A9D9ED799705F5L;

        return (int) (((z ^ (z >>> 28)) * 0xCB24D0A5C88C35B3L) >>> 32);
    }

    // Advances the state by the golden gamma and mixes
    public long nextLong() {
        return staffordMix13(this.x += PHI);
    }

    // Upper 32 bits of a mixed state
    public int nextInt() {
        return staffordMix4Upper32(this.x += PHI);
    }

    // Unbiased bounded int by rejection
    public int nextInt(final int n) {
        return (int) this.nextLong(n);
    }

    // Unbiased bounded long by rejection
    public long nextLong(final long n) {
        if (n <= 0) {
            throw new IllegalArgumentException("illegal bound " + n + " (must be positive)");
        }

        long t = staffordMix13(this.x += PHI);

        final long nMinus1 = n - 1;

        if ((n & nMinus1) == 0) {
            return t & nMinus1;
        }

        long u = t >>> 1;

        while (u + nMinus1 - (t = u % n) < 0) {
            u = staffordMix13(this.x += PHI) >>> 1;
        }

        return t;
    }

    // 53 random bits scaled to [0, 1)
    public double nextDouble() {
        return (staffordMix13(this.x += PHI) >>> 11) * 0x1.0p-53;
    }

    // 24 random bits scaled to [0, 1)
    public float nextFloat() {
        return (staffordMix4Upper32(this.x += PHI) >>> 8) * 0x1.0p-24f;
    }

    // Sign bit of a long
    public boolean nextBoolean() {
        return staffordMix4Upper32(this.x += PHI) < 0;
    }

    // Fills eight bytes per long
    public void nextBytes(final byte[] bytes) {
        int i = bytes.length, n;

        while (i != 0) {
            n = Math.min(i, 8);
            for (long bits = staffordMix13(this.x += PHI); n-- != 0; bits >>= 8) {
                bytes[--i] = (byte) bits;
            }
        }
    }

    // Mixes the seed so similar seeds diverge
    public void setSeed(final long seed) {
        this.x = HashCommon.murmurHash3(seed);
    }

    // Raw state, for reproducing a sequence exactly
    public void setState(final long state) {
        this.x = state;
    }
}

