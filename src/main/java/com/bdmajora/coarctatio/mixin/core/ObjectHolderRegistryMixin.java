package com.bdmajora.coarctatio.mixin.core;

import net.minecraftforge.registries.ObjectHolderRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

// Trims (never clears) the @ObjectHolder ref list after Forge's scan, since the doubling-grown ArrayList is up to half empty; on 1.12.2 applyObjectHolders re-runs on every registry change so the list must survive. remap = false, Forge class
@Mixin(value = ObjectHolderRegistry.class, remap = false)
public abstract class ObjectHolderRegistryMixin {
    // Not @Final: Forge declares this as a plain assigned field, and marking it final would fail to resolve
    @Shadow
    private List<Object> objectHolders;

    // At RETURN so every ref has been added and the list will not grow again this scan; the instanceof guard covers another mod having replaced the field
    @Inject(method = "findObjectHolders", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void coarctatio$trimHolders(CallbackInfo ci) {
        if (this.objectHolders instanceof ArrayList) {
            ((ArrayList<Object>) this.objectHolders).trimToSize();
        }
    }
}
