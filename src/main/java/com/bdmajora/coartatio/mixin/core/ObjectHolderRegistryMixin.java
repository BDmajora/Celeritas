package com.bdmajora.coartatio.mixin.core;

import net.minecraftforge.registries.ObjectHolderRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// Trims the @ObjectHolder reference list once Forge has finished scanning for them
// Forge collects one ObjectHolderRef per annotated field — 1168 of them on a five-mod instance, many thousands
// on a large pack — into an ArrayList grown by repeated doubling, so up to half the backing array is empty
// A trim and NOT a clear, deliberately. ModernFix's object_holder_cleanup drops the refs outright, but that is
// a 1.16+ patch where they are consumed once; on 1.12.2 applyObjectHolders runs again on every registry change,
// notably when joining a server whose registry IDs differ, so the list has to survive the whole session
// Trimming is all that is safely available here, and it is a small win — included for completeness rather than
// for impact
// remap = false because ObjectHolderRegistry is a Forge class and its names are not in the MCP mapping set
@Mixin(value = ObjectHolderRegistry.class, remap = false)
public abstract class ObjectHolderRegistryMixin {
    // Not @Final: Forge declares this as a plain assigned field, and marking it final would fail to resolve
    @Shadow
    private List<Object> objectHolders;

    // At RETURN, so every ref has been added and the list will not grow again this scan
    // The instanceof guard is not paranoia: another mod may already have replaced the field with its own List
    @Inject(method = "findObjectHolders", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void coartatio$trimHolders(CallbackInfo ci) {
        if (this.objectHolders instanceof ArrayList) {
            ((ArrayList<Object>) this.objectHolders).trimToSize();
        }
    }
}
