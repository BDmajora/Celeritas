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

// Interns the two strings behind every ResourceLocation. Ported from Hydrogen's MixinIdentifier
// A heavily modded instance holds hundreds of thousands of these, and the domain in particular is one of a few
// hundred mod ids repeated over and over, each currently carrying its own String object and its own char[]
// Safe by construction: equals, hashCode and compareTo on ResourceLocation all go through String.equals, which
// does not care which instance holds the characters
@Mixin(ResourceLocation.class)
public class ResourceLocationMixin {
    // namespace and path are the field names in the mapping set this project builds against (mcp_stable/39),
    // NOT the older resourceDomain/resourcePath
    // Worth stating because a @Shadow naming a field that does not exist compiles perfectly happily and only
    // blows up when Mixin resolves it against the target, so these were checked against mcp-srg.srg rather than
    // against a decompile
    @Mutable
    @Shadow
    @Final
    protected String namespace;

    @Mutable
    @Shadow
    @Final
    protected String path;

    // The varargs constructor every other constructor funnels through, so the public (String) and
    // (String, String) forms are both covered by this one hook
    @Inject(method = "<init>(I[Ljava/lang/String;)V", at = @At("RETURN"))
    private void coartatio$internComponents(int unused, String[] parts, CallbackInfo ci) {
        this.namespace = ResourceLocationCaches.DOMAINS.deduplicate(this.namespace);
        this.path = ResourceLocationCaches.PATHS.deduplicate(this.path);
    }
}
