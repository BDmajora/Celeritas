package com.bdmajora.impetus.mixin.core.shader;

import com.bdmajora.impetus.umbra.devtool.ShaderStateProbe;
import com.bdmajora.impetus.umbra.devtool.ShadowMapDump;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.File;

/**
 * Ties the shadow map dump to F2, so a screenshot captures what the player sees and what the sun sees in the same
 * moment. Comparing the two is the only practical way to tell whether geometry that should be casting a shadow is
 * actually present in the shadow map — depth statistics alone cannot distinguish "holds the terrain overhead" from
 * "holds only the walls the player can see".
 * <p>
 * The screenshot runs after the frame is complete, so this only flags the request; the next shadow pass performs the
 * readback, while it still owns the shadow framebuffer.
 * <p>
 * {@link ShadowMapDump#request()} is a no-op unless {@code -Dimpetus.umbra.shadowDump=true}. It shares a key with
 * vanilla screenshots, so left on it makes every F2 allocate hundreds of megabytes and disturb the shadow pass's GL
 * state; see that method for the full cost breakdown.
 */
@Mixin(ScreenShotHelper.class)
public class ScreenshotShadowDumpMixin {
    @Inject(method = "saveScreenshot(Ljava/io/File;Ljava/lang/String;IILnet/minecraft/client/shader/Framebuffer;)"
            + "Lnet/minecraft/util/text/ITextComponent;", at = @At("HEAD"), require = 0)
    private static void impetus$requestShadowMapDump(File gameDirectory, String screenshotName, int width, int height,
                                                     Framebuffer buffer, CallbackInfoReturnable<ITextComponent> cir) {
        // Unlike the shadow dump this needs no flag: it reads already-computed CPU state, so it costs a few hundred
        // microseconds and a log line. Take one screenshot where the scene looks wrong and one where it looks right,
        // then diff the two blocks — the ROTATION-INVARIANT section is the part that should be identical.
        ShaderStateProbe.dump("screenshot " + screenshotName);
        ShadowMapDump.request();
    }
}
