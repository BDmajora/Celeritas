package com.bdmajora.extras.mixin.render.entity;

import com.bdmajora.extras.client.ItemFrameLodState;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.block.model.IBakedModel;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.client.ForgeHooksClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Item-frame LOD on Forge's emissive-item render path.
 *
 * <p>{@code ForgeModContainer.allowEmissiveItems} defaults to true, so most item rendering never
 * reaches {@code RenderItem.renderModel} at all — it goes through {@code renderLitItem}, which makes
 * the same per-face {@code getQuads} calls. Hooking both is what makes the LOD work regardless of
 * that setting.
 */
@Mixin(ForgeHooksClient.class)
public class ForgeHooksClientMixin {
    // Not remap = false, tempting as it looks on a Forge class: the enclosing method is Forge's and
    // needs no remapping, but IBakedModel.getQuads is Minecraft's and becomes func_188616_a in
    // production. Suppressing remapping wholesale would leave the redirect looking for a method
    // name that does not exist outside the development environment.
    @Redirect(
            method = "renderLitItem(Lnet/minecraft/client/renderer/RenderItem;Lnet/minecraft/client/renderer/block/model/IBakedModel;ILnet/minecraft/item/ItemStack;)V",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/block/model/IBakedModel;getQuads(Lnet/minecraft/block/state/IBlockState;Lnet/minecraft/util/EnumFacing;J)Ljava/util/List;")
    )
    private static List<BakedQuad> impetus$lodQuads(IBakedModel model, IBlockState state, EnumFacing side, long rand) {
        return ItemFrameLodState.filterLodQuads(model, state, side, rand);
    }
}
