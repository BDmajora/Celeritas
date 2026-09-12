package com.bdmajora.impetus.mixin.core.terrain;

import net.minecraft.client.renderer.block.model.SimpleBakedModel;
import com.bdmajora.impetus.engine.impl.model.quad.BakedQuadView;
import com.bdmajora.impetus.engine.impl.model.quad.properties.ModelQuadFlags;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Mixin(SimpleBakedModel.Builder.class)
public class SimpleBakedModelBuilderMixin {
    // Flags quads built by vanilla's model builder so the light pipeline applies vanilla's shading rules
    @ModifyArg(method = { "addFaceQuad", "addGeneralQuad" }, at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", remap = false), require = 0)
    private Object setVanillaShadingFlag(Object quad) {
        BakedQuadView view = (BakedQuadView)quad;
        view.addFlags(ModelQuadFlags.IS_VANILLA_SHADED);
        return quad;
    }
}