package com.bdmajora.extras.mixin.render.entity;

import com.bdmajora.extras.client.ItemFrameLodState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.RenderItem;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Item-frame LOD on the vanilla item render path.
 *
 * <p>Scoped entirely by {@link ItemFrameLodState#active}, so every other item render — inventory,
 * hand, GUI, dropped items — pays only a static boolean read here.
 *
 * <p>With Forge's {@code allowEmissiveItems} on (the default) items go through
 * {@code ForgeHooksClient.renderLitItem} instead; that path is covered by
 * {@link ForgeHooksClientMixin}.
 */
@Mixin(RenderItem.class)
public class RenderItemMixin {
    @Redirect(
            method = "renderModel(Lnet/minecraft/client/renderer/block/model/IBakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/IBakedModel;getQuads(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/EnumFacing;J)Ljava/util/List;")
    )
    private List<BakedQuad> impetus$lodQuads(IBakedModel model, IBlockState state, EnumFacing side, long rand) {
        return ItemFrameLodState.filterLodQuads(model, state, side, rand);
    }
}
