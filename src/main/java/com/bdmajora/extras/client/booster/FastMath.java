package com.bdmajora.extras.client.booster;

// Replacements for the MathHelper routines that still pay for something on 1.12.2 (the sine table is already Equilibrium's): angle wrapping without the float remainder, which HotSpot implements as an out-of-line fmod call, and the log2 pair as one leading-zero-count instruction instead of a multiply and a table read
public final class FastMath {
    public static boolean enabled;

    private FastMath() {
    }

    // [-180, 180) like vanilla, via one division and a floor instead of frem; exact for every angle an entity can carry
    public static float wrapDegrees(float value) {
        float turns = (value + 180.0F) * (1.0F / 360.0F);
        int whole = (int) turns;
        if (turns < whole) {
            whole--;
        }
        return value - whole * 360.0F;
    }

    // Double form of the above
    public static double wrapDegrees(double value) {
        double turns = (value + 180.0D) * (1.0D / 360.0D);
        int whole = (int) turns;
        if (turns < whole) {
            whole--;
        }
        return value - whole * 360.0D;
    }

    // ceil(log2(value)) for value > 1, 0 otherwise, which is what vanilla's de Bruijn version returns
    public static int ceilLog2(int value) {
        return value <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(value - 1);
    }

    // floor(log2(value)); -1 for zero like vanilla
    public static int floorLog2(int value) {
        return 31 - Integer.numberOfLeadingZeros(value);
    }
}
