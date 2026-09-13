package com.bdmajora.extras.client.booster;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

// A java.util.Random on xoroshiro128++ with a ziggurat normal: vanilla hands every entity and every particle its own Random, which costs an AtomicLong, a CAS loop on the seed uniquifier and a nanoTime call apiece, and then serves nextGaussian through a synchronized polar loop over StrictMath. This one is two longs seeded from a counter, unsynchronized, and samples a normal with one table lookup nearly every time. Not thread-safe by design, which matches how the game uses per-entity randoms
public final class FastRandom extends Random {
    private static final long serialVersionUID = 1L;
    public static boolean enabled;

    private static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final AtomicLong SEEDS = new AtomicLong(System.nanoTime());
    // Particles are constructed and updated on the client thread only, so they can share one instance and pay nothing per spawn
    private static final FastRandom PARTICLES = new FastRandom();

    // Ziggurat tables for the standard normal, 128 layers (Marsaglia & Tsang)
    private static final int LAYERS = 128;
    private static final double TAIL = 3.442619855899;
    private static final double LAYER_AREA = 9.91256303526217e-3;
    private static final double SCALE = 2147483648.0;
    private static final double[] THRESHOLD = new double[LAYERS];
    private static final double[] WIDTH = new double[LAYERS];
    private static final double[] HEIGHT = new double[LAYERS];

    static {
        double x = TAIL;
        double previous = TAIL;
        double base = LAYER_AREA / Math.exp(-0.5 * TAIL * TAIL);
        THRESHOLD[0] = (x / base) * SCALE;
        THRESHOLD[1] = 0.0;
        WIDTH[0] = base / SCALE;
        WIDTH[LAYERS - 1] = x / SCALE;
        HEIGHT[0] = 1.0;
        HEIGHT[LAYERS - 1] = Math.exp(-0.5 * x * x);
        for (int i = LAYERS - 2; i >= 1; i--) {
            x = Math.sqrt(-2.0 * Math.log(LAYER_AREA / x + Math.exp(-0.5 * x * x)));
            THRESHOLD[i + 1] = (x / previous) * SCALE;
            previous = x;
            HEIGHT[i] = Math.exp(-0.5 * x * x);
            WIDTH[i] = x / SCALE;
        }
    }

    // No initializers: Random's constructor calls setSeed before this class's field initializers would run
    private long state0;
    private long state1;

    public FastRandom() {
        super(SEEDS.getAndAdd(GOLDEN));
    }

    public FastRandom(long seed) {
        super(seed);
    }

    // The shared client-thread instance for particles
    public static FastRandom particles() {
        return PARTICLES;
    }

    // splitmix64 over the seed fills both words; a zero state would be a fixed point so it is nudged
    @Override
    public void setSeed(long seed) {
        long z = seed + GOLDEN;
        this.state0 = mix(z);
        this.state1 = mix(z + GOLDEN);
        if (this.state0 == 0L && this.state1 == 0L) {
            this.state0 = GOLDEN;
        }
    }

    private static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    // xoroshiro128++ step
    @Override
    public long nextLong() {
        long s0 = this.state0;
        long s1 = this.state1;
        long result = Long.rotateLeft(s0 + s1, 17) + s0;
        s1 ^= s0;
        this.state0 = Long.rotateLeft(s0, 49) ^ s1 ^ (s1 << 21);
        this.state1 = Long.rotateLeft(s1, 28);
        return result;
    }

    // Everything Random derives from next() takes the top bits, which are the strongest in this generator
    @Override
    protected int next(int bits) {
        return (int) (this.nextLong() >>> (64 - bits));
    }

    @Override
    public int nextInt() {
        return (int) (this.nextLong() >>> 32);
    }

    @Override
    public boolean nextBoolean() {
        return this.nextLong() < 0L;
    }

    @Override
    public float nextFloat() {
        return (this.nextLong() >>> 40) * 0x1.0p-24F;
    }

    @Override
    public double nextDouble() {
        return (this.nextLong() >>> 11) * 0x1.0p-53D;
    }

    // Ziggurat: a random layer and a 32-bit sample; inside the layer's rectangle (nearly always) the sample scaled by the layer width is the answer, otherwise the wedge or the tail is resolved exactly
    @Override
    public double nextGaussian() {
        for (;;) {
            int sample = (int) (this.nextLong() >>> 32);
            int layer = sample & (LAYERS - 1);
            long magnitude = sample < 0 ? -(long) sample : sample;
            if (magnitude < THRESHOLD[layer]) {
                return sample * WIDTH[layer];
            }

            double x = sample * WIDTH[layer];
            if (layer == 0) {
                double dx;
                double dy;
                do {
                    dx = -Math.log(1.0 - this.nextDouble()) / TAIL;
                    dy = -Math.log(1.0 - this.nextDouble());
                } while (dy + dy < dx * dx);
                return sample > 0 ? TAIL + dx : -TAIL - dx;
            }

            if (HEIGHT[layer] + this.nextDouble() * (HEIGHT[layer - 1] - HEIGHT[layer]) < Math.exp(-0.5 * x * x)) {
                return x;
            }
        }
    }
}
