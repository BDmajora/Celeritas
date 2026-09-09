package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.DynamicLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// drops every tracked light source when the world changes
// without this the set would hold entities from the world just left: they would never be ticked
// again, so they would never go dark, and they would keep contributing light at whatever
// coordinates they happened to die at in the new world
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private void impetus$clearDynamicLights(WorldClient world, String loadingMessage, CallbackInfo ci) {
        DynamicLights.engine().clearLightSources();
    }
}
