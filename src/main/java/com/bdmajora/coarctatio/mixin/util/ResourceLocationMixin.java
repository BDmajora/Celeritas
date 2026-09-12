package com.bdmajora.coarctatio.mixin.util;

import com.bdmajora.coarctatio.dedup.ResourceLocationCaches;
import net.minecraft.util.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Interns the two strings behind every ResourceLocation (Hydrogen's MixinIdentifier); hundreds of thousands exist on a modded instance, and equals/hashCode/compareTo all go through String.equals so it is safe
@Mixin(ResourceLocation.class)
public class ResourceLocationMixin {
    // namespace and path are the field names in mcp_stable/39, NOT resourceDomain/resourcePath; a @Shadow on a missing field compiles fine and only fails at Mixin resolution, so these were checked against mcp-srg.srg
    @Mutable
    @Shadow
    @Final
    protected String namespace;

    @Mutable
    @Shadow
    @Final
    protected String path;

    // The varargs constructor every other one funnels through, so the (String) and (String, String) forms are both covered
    @Inject(method = "<init>(I[Ljava/lang/String;)V", at = @At("RETURN"))
    private void coarctatio$internComponents(int unused, String[] parts, CallbackInfo ci) {
        this.namespace = ResourceLocationCaches.DOMAINS.deduplicate(this.namespace);
        this.path = ResourceLocationCaches.PATHS.deduplicate(this.path);
    }
}
