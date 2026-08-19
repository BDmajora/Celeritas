package com.bdmajora.coartatio.mixin.core;

import net.minecraftforge.registries.ObjectHolderRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Trims the {@code @ObjectHolder} reference list once scanning is finished.
 *
 * <p>Forge collects one {@code ObjectHolderRef} per annotated field — 1168 of them on a five-mod
 * instance, many thousands on a large pack — into an {@code ArrayList} grown by repeated doubling,
 * which leaves up to half the backing array empty.
 *
 * <p>Deliberately a trim and not a clear. ModernFix's {@code object_holder_cleanup} works on 1.16+,
 * where the refs are consumed once; on 1.12.2 {@code applyObjectHolders} runs again on every registry
 * change — notably when joining a server whose registry IDs differ — so the list has to survive for
 * the session. Trimming is the whole of what is safely available here, and it is a small win; it is
 * included for completeness rather than impact.
 *
 * <p>{@code remap = false}: {@code ObjectHolderRegistry} is a Forge class.
 */
@Mixin(value = ObjectHolderRegistry.class, remap = false)
public abstract class ObjectHolderRegistryMixin {
    // Not @Final: Forge declares this as a plain assigned field.
    @Shadow
    private List<Object> objectHolders;

    @Inject(method = "findObjectHolders", at = @At("RETURN"))
    @SuppressWarnings("unchecked")
    private void coartatio$trimHolders(CallbackInfo ci) {
        if (this.objectHolders instanceof ArrayList) {
            ((ArrayList<Object>) this.objectHolders).trimToSize();
        }
    }
}
