package com.bdmajora.coartatio.mixin.client.texture;

import com.bdmajora.coartatio.Coartatio;
import com.bdmajora.coartatio.MemoryReport;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.client.resources.IResourceManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Releases sprite pixel data once it is on the GPU.
 *
 * <p>From LoliASM's {@code releaseSpriteFramesCache}. Every stitched sprite keeps its
 * {@code List<int[][]>} of raw pixels — the full mipmap chain — after the atlas has been uploaded.
 * For a static sprite nothing ever reads it again, and a large resource pack has thousands of them.
 *
 * <p>Animated sprites are left alone: {@code updateAnimation} reads the frame data every tick, so
 * clearing theirs would break animation outright. {@code hasAnimationMetadata()} is Forge's own test
 * for that, and {@code clearFramesTextureData()} is Forge's own accessor for doing the release, so
 * this needs no shadowing beyond the sprite map itself.
 *
 * <p>The one compatibility risk is a mod calling {@code getFrameTextureData} after the atlas is
 * built — connected-texture and custom-atlas mods occasionally do. That is why this is its own
 * switch, and why the log line names how many sprites were released: if something breaks, the
 * culprit and the remedy are both one setting away.
 */
@Mixin(TextureMap.class)
public abstract class TextureMapMixin {
    @Shadow
    @Final
    private Map<String, TextureAtlasSprite> mapUploadedSprites;

    @Inject(method = "loadTextureAtlas", at = @At("RETURN"))
    private void coartatio$releaseStaticSpriteData(IResourceManager resourceManager, CallbackInfo ci) {
        int released = 0;
        long bytes = 0;

        for (TextureAtlasSprite sprite : this.mapUploadedSprites.values()) {
            if (sprite == null || sprite.hasAnimationMetadata()) {
                continue;
            }

            // Measured before the release so the report can quote a real figure rather than an
            // estimate: this is one of only two places Coartatio knows exactly what it freed.
            for (int frame = 0; frame < sprite.getFrameCount(); frame++) {
                int[][] mipmaps = sprite.getFrameTextureData(frame);

                if (mipmaps == null) {
                    continue;
                }

                for (int[] level : mipmaps) {
                    if (level != null) {
                        bytes += 16L + level.length * 4L;
                    }
                }
            }

            sprite.clearFramesTextureData();
            released++;
        }

        MemoryReport.recordSpriteBytes(bytes);
        Coartatio.LOGGER.info("Released pixel data for {} static sprites ({})", released, MemoryReport.mib(bytes));
    }
}
