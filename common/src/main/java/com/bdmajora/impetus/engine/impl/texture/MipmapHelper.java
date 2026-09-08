package com.bdmajora.impetus.engine.impl.texture;

import com.bdmajora.impetus.engine.api.util.ColorARGB;
import com.bdmajora.impetus.engine.impl.util.color.ColorSRGB;

// Mipmap downsampling that blends in linear space (unlike OptiFine's sRGB blend, which loses brightness) and
// weights by alpha (unlike vanilla's flat average, which causes dark-edge artifacts on cutout textures)
// Ported from Umbra's MixinMipmapGenerator
public class MipmapHelper {
    public static int weightedAverageColor(int one, int two) {
        int alphaOne = ColorARGB.unpackAlpha(one);
        int alphaTwo = ColorARGB.unpackAlpha(two);

        // In the case where the alpha values of the same, we can get by with an unweighted average.
        if (alphaOne == alphaTwo) {
            return averageRgb(one, two, alphaOne);
        }

        // If one of our pixels is fully transparent, ignore it.
        // We just take the value of the other pixel as-is. To compensate for not changing the color value, we
        // divide the alpha value by 4 instead of 2.
        if (alphaOne == 0) {
            return (two & 0x00FFFFFF) | ((alphaTwo >> 2) << 24);
        }

        if (alphaTwo == 0) {
            return (one & 0x00FFFFFF) | ((alphaOne >> 2) << 24);
        }

        // Use the alpha values to compute relative weights of each color.
        float scale = 1.0f / (alphaOne + alphaTwo);

        float relativeWeightOne = alphaOne * scale;
        float relativeWeightTwo = alphaTwo * scale;

        // Convert the color components into linear space, then multiply the corresponding weight.
        float oneR = ColorSRGB.srgbToLinear(ColorARGB.unpackRed(one)) * relativeWeightOne;
        float oneG = ColorSRGB.srgbToLinear(ColorARGB.unpackGreen(one)) * relativeWeightOne;
        float oneB = ColorSRGB.srgbToLinear(ColorARGB.unpackBlue(one)) * relativeWeightOne;

        float twoR = ColorSRGB.srgbToLinear(ColorARGB.unpackRed(two)) * relativeWeightTwo;
        float twoG = ColorSRGB.srgbToLinear(ColorARGB.unpackGreen(two)) * relativeWeightTwo;
        float twoB = ColorSRGB.srgbToLinear(ColorARGB.unpackBlue(two)) * relativeWeightTwo;

        // Combine the color components of each color
        float linearR = oneR + twoR;
        float linearG = oneG + twoG;
        float linearB = oneB + twoB;

        // Take the average alpha of both alpha values
        int averageAlpha = (alphaOne + alphaTwo) >> 1;

        // Convert to sRGB and pack the colors back into an integer.
        return ColorSRGB.linearToSrgb(linearR, linearG, linearB, averageAlpha);
    }

    // Computes a non-weighted average of the two sRGB colors in linear space, avoiding brightness losses.
    private static int averageRgb(int a, int b, int alpha) {
        float ar = ColorSRGB.srgbToLinear(ColorARGB.unpackRed(a));
        float ag = ColorSRGB.srgbToLinear(ColorARGB.unpackGreen(a));
        float ab = ColorSRGB.srgbToLinear(ColorARGB.unpackBlue(a));

        float br = ColorSRGB.srgbToLinear(ColorARGB.unpackRed(b));
        float bg = ColorSRGB.srgbToLinear(ColorARGB.unpackGreen(b));
        float bb = ColorSRGB.srgbToLinear(ColorARGB.unpackBlue(b));

        return ColorSRGB.linearToSrgb((ar + br) * 0.5f, (ag + bg) * 0.5f, (ab + bb) * 0.5f, alpha);
    }
}
