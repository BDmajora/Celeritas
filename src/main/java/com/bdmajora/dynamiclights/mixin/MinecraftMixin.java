package com.bdmajora.dynamiclights.mixin;

import com.bdmajora.dynamiclights.DynamicLights;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Drops every tracked source on world change; otherwise entities from the old world are never ticked again, never go dark, and keep glowing at their death coordinates
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "loadWorld(Lnet/minecraft/client/multiplayer/WorldClient;Ljava/lang/String;)V",
            at = @At("HEAD"))
    private void impetus$clearDynamicLights(WorldClient world, String loadingMessage, CallbackInfo ci) {
        DynamicLights.engine().clearLightSources();
    }
}
