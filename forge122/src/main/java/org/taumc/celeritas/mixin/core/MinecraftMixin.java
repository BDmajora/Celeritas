package org.taumc.celeritas.mixin.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import org.embeddedt.embeddium.impl.render.frame.RenderAheadManager;
import org.lwjgl.LWJGLException;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.DisplayMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.taumc.celeritas.CeleritasVintage;

@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Shadow
    private boolean fullscreen;

    @Shadow
    public int displayWidth;

    @Shadow
    public int displayHeight;

    @Shadow
    public GameSettings gameSettings;

    @Unique
    private final RenderAheadManager celeritas$renderAheadManager = new RenderAheadManager();

    @Inject(method = "runTick", at = @At("HEAD"))
    private void preRender(CallbackInfo ci) {
        celeritas$renderAheadManager.startFrame(CeleritasVintage.options().advanced.cpuRenderAheadLimit);
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void postRender(CallbackInfo ci) {
        celeritas$renderAheadManager.endFrame();
    }

    @Redirect(method = "createDisplay", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;create()V", remap = false))
    private void celeritas$retryWindowedWhenFullscreenDisplayCreateFails() throws LWJGLException {
        try {
            Display.create();
            return;
        } catch (LWJGLException exception) {
            if (!this.fullscreen && (this.gameSettings == null || !this.gameSettings.fullScreen)) {
                throw exception;
            }

            CeleritasVintage.logger().warn("Fullscreen OpenGL display creation failed; retrying in windowed mode", exception);
            this.fullscreen = false;

            if (this.gameSettings != null) {
                this.gameSettings.fullScreen = false;
                this.gameSettings.saveOptions();
            }

            Display.setFullscreen(false);
            Display.setDisplayMode(new DisplayMode(Math.max(1, this.displayWidth), Math.max(1, this.displayHeight)));
            Display.create();
        }
    }
}
