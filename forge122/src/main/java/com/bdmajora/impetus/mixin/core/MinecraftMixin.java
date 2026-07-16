package com.bdmajora.impetus.mixin.core;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import com.bdmajora.impetus.engine.impl.ImpetusRuntimeOptions;
import com.bdmajora.impetus.engine.impl.gui.ImpetusGameOptions;
import com.bdmajora.impetus.engine.impl.render.frame.RenderAheadManager;
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
import com.bdmajora.impetus.ImpetusVintage;

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
    private final RenderAheadManager impetus$renderAheadManager = new RenderAheadManager();

    @Inject(method = "runTick", at = @At("HEAD"))
    private void preRender(CallbackInfo ci) {
        impetus$renderAheadManager.startFrame(ImpetusVintage.options().advanced.cpuRenderAheadLimit);
    }

    @Inject(method = "runTick", at = @At("RETURN"))
    private void postRender(CallbackInfo ci) {
        impetus$renderAheadManager.endFrame();
    }

    /**
     * Inactivity frame-rate limiting: caps the frame rate hard when the window is minimized, and (in AFK mode)
     * when it is merely unfocused. Modern Sodium exposes the same behaviour as its "Inactivity FPS Limit" option.
     */
    @ModifyReturnValue(method = "getLimitFramerate", at = @At("RETURN"))
    private int impetus$applyInactivityFpsLimit(int limit) {
        ImpetusGameOptions.InactivityFpsLimit mode = ImpetusRuntimeOptions.inactivityFpsLimit;

        if (mode == ImpetusGameOptions.InactivityFpsLimit.NO_LIMIT) {
            return limit;
        }

        if (!Display.isVisible()) {
            // Minimized window: both AFK and MINIMIZED modes clamp hard.
            return Math.min(limit, 10);
        }

        if (mode == ImpetusGameOptions.InactivityFpsLimit.AFK && !Display.isActive()) {
            // Unfocused window in AFK mode.
            return Math.min(limit, 30);
        }

        return limit;
    }

    @Redirect(method = "createDisplay", at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/Display;create()V", remap = false))
    private void impetus$retryWindowedWhenFullscreenDisplayCreateFails() throws LWJGLException {
        try {
            Display.create();
            return;
        } catch (LWJGLException exception) {
            if (!this.fullscreen && (this.gameSettings == null || !this.gameSettings.fullScreen)) {
                throw exception;
            }

            ImpetusVintage.logger().warn("Fullscreen OpenGL display creation failed; retrying in windowed mode", exception);
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
