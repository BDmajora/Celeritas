// Values of u_FogShape. 0 and 1 are Sodium's own; 2 and 3 are the extra shapes the Extras page
// offers, and are named after Sodium Extra's even though the formulas differ — Sodium fogs
// cylindrically by default and Impetus fogs spherically, so "the spherical one" is the baseline
// here rather than the alternative. See ExtrasConfig.FogShape.
const int FOG_SHAPE_SPHERICAL = 0;
const int FOG_SHAPE_CYLINDRICAL = 1;
const int FOG_SHAPE_RADIAL = 2;
const int FOG_SHAPE_PLANAR = 3;

vec4 _linearFog(vec4 fragColor, float fragDistance, vec4 fogColor, float fogStart, float fogEnd) {
#ifdef USE_FOG
    if (fragDistance <= fogStart) {
        return fragColor;
    }
    float factor = fragDistance < fogEnd ? smoothstep(fogStart, fogEnd, fragDistance) : 1.0; // alpha value of fog is used as a weight
    vec3 blended = mix(fragColor.rgb, fogColor.rgb, factor * fogColor.a);

    return vec4(blended, fragColor.a); // alpha value of fragment cannot be modified
#else
    return fragColor;
#endif
}

float _linearFogValue(float fragDistance, float fogStart, float fogEnd) {
#ifdef USE_FOG
    if (fragDistance <= fogStart) {
        return 0.0;
    } else if (fragDistance >= fogEnd) {
        return 1.0;
    }

    return smoothstep(fogStart, fogEnd, fragDistance);
#else
    return 1.0;
#endif
}

vec4 _exp2Fog(vec4 fragColor, float fragDistance, vec4 fogColor, float fogDensity) {
#ifdef USE_FOG
    float dist = fragDistance * fogDensity;
    float factor = clamp(1.0 / exp2(dist * dist), 0.0, 1.0);
    vec3 blended = mix(fogColor.rgb, fragColor.rgb, factor * fogColor.a);

    return vec4(blended, fragColor.a); // alpha value of fragment cannot be modified
#else
    return fragColor;
#endif
}

float getFragDistance(int fogShape, vec3 position) {
    // Use the maximum of the horizontal and vertical distance to get cylindrical fog if fog shape is cylindrical
    if (fogShape == FOG_SHAPE_CYLINDRICAL) {
        return max(length(position.xz), abs(position.y));
    } else if (fogShape == FOG_SHAPE_RADIAL) {
        // Horizontal only: altitude never contributes, so looking straight up or down out of a fogged
        // world shows clear sky. The distinctive one of the four.
        return length(position.xz);
    } else {
        return length(position);
    }
}

// Planar fog needs the view-space depth, which the world-space position alone cannot give.
// Callers that have the modelview result on hand use this overload; the rest fall back above.
float getFragDistance(int fogShape, vec3 position, float viewDepth) {
    if (fogShape == FOG_SHAPE_PLANAR) {
        return viewDepth;
    }
    return getFragDistance(fogShape, position);
}