package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.client.DynamicLightSource;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops tracking an entity the server has told the client to forget.
 *
 * <p>{@code Entity#onRemovedFromWorld} covers most removals, but an entity dropped by
 * {@code removeEntityFromWorld} — the usual path for something leaving tracking range — does not
 * reliably go through it. Without this, walking away from a burning mob would leave its light
 * behind.
 */
@Mixin(WorldClient.class)
public abstract class WorldClientMixin {
    @Inject(method = "removeEntityFromWorld", at = @At("HEAD"))
    private void impetus$untrackDynamicLight(int entityId, CallbackInfoReturnable<Entity> cir) {
        Entity entity = ((WorldClient) (Object) this).getEntityByID(entityId);

        if (entity instanceof DynamicLightSource) {
            ((DynamicLightSource) entity).impetus$setDynamicLightEnabled(false);
        }
    }
}
