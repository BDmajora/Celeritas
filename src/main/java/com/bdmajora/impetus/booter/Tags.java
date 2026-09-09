package com.bdmajora.impetus.booter;

// Upstream MixinBooter generates this class at build time via RFG's injectTags. The fork is pinned
// to one CleanMix/MixinExtras pair, so the values are static; bump them alongside the booterLibs
// coordinates in build.gradle.kts.
public final class Tags {

    public static final String MOD_ID = "impetusbooter";
    public static final String MOD_NAME = "ImpetusBooter";
    public static final String VERSION = "11.17";

    // CleanMix and MixinExtras versions bundled in the nested libs jar; surfaced for crash reports
    public static final String CLEANMIX_VERSION = "0.7.2";
    public static final String MIXINEXTRAS_VERSION = "0.5.5";

    private Tags() { }

}
