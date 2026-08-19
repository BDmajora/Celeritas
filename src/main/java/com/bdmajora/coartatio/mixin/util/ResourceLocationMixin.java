package com.bdmajora.coartatio.mixin.util;

import com.bdmajora.coartatio.dedup.ResourceLocationCaches;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Interns the two strings behind every {@code ResourceLocation}.
 *
 * <p>Ported from Hydrogen's {@code MixinIdentifier}. A heavily modded instance holds hundreds of
 * thousands of these; the domain in particular is one of a few hundred mod ids repeated over and
 * over, each currently with its own {@code String} object and its own {@code char[]}.
 *
 * <p>Injected into the varargs constructor that every other constructor funnels through, so the
 * public {@code (String)} and {@code (String, String)} forms are both covered by one hook.
 *
 * <p>Safe by construction: {@code equals}/{@code hashCode}/{@code compareTo} on
 * {@code ResourceLocation} all go through {@code String.equals}, which is unaffected by which
 * instance holds the characters.
 *
 * <p>The fields are {@code namespace} and {@code path} in the mapping set this project builds
 * against ({@code mcp_stable/39}), not the older {@code resourceDomain}/{@code resourcePath}. A
 * shadow naming a field that does not exist compiles perfectly happily and only fails when Mixin
 * tries to resolve it against the target, so this is verified against
 * {@code mcp-srg.srg} rather than against a decompile.
 */
@Mixin(ResourceLocation.class)
public class ResourceLocationMixin {
    @Mutable
    @Shadow
    @Final
    protected String namespace;

    @Mutable
    @Shadow
    @Final
    protected String path;

    @Inject(method = "<init>(I[Ljava/lang/String;)V", at = @At("RETURN"))
    private void coartatio$internComponents(int unused, String[] parts, CallbackInfo ci) {
        this.namespace = ResourceLocationCaches.DOMAINS.deduplicate(this.namespace);
        this.path = ResourceLocationCaches.PATHS.deduplicate(this.path);
    }
}
