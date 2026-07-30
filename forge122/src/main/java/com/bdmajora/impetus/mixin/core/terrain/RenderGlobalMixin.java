package com.bdmajora.impetus.mixin.core.terrain;

import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.block.BlockLeaves;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.culling.ICamera;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.BlockRenderLayer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.WorldProvider;
import com.bdmajora.impetus.ImpetusVintage;
import com.bdmajora.impetus.engine.impl.gl.device.RenderDevice;
import com.bdmajora.impetus.engine.impl.render.terrain.SimpleWorldRenderer;
import com.bdmajora.impetus.engine.impl.render.viewport.ViewportProvider;
import com.bdmajora.impetus.iris.Iris;
import com.bdmajora.impetus.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bdmajora.impetus.impl.render.entity.EntityGatherer;
import com.bdmajora.impetus.impl.render.terrain.ImpetusWorldRenderer;

import java.util.*;

@Mixin(RenderGlobal.class)
public abstract class RenderGlobalMixin implements SimpleWorldRenderer.Provider<ImpetusWorldRenderer> {

    @Shadow
    @Final
    private Map<Integer, DestroyBlockProgress> damagedBlocks;

    @Shadow @Final private Minecraft mc;
    @Shadow
    @Final
    private RenderManager renderManager;
    @Shadow
    private int countEntitiesRendered;

    @Shadow
    protected abstract boolean isOutlineActive(Entity entityIn, Entity viewer, ICamera camera);

    @Shadow
    private WorldClient world;
    @Shadow
    @Final
    private Set<TileEntity> setTileEntities;
    private ImpetusWorldRenderer renderer;

    @Redirect(method = "loadRenderers", at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;renderDistanceChunks:I", ordinal = 1))
    private int nullifyBuiltChunkStorage(GameSettings settings) {
        // Do not allow any resources to be allocated
        return 0;
    }

    @Redirect(method = "loadRenderers", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockLeaves;setGraphicsLevel(Z)V"))
    private void useConfiguredLeavesGraphicsLevel(BlockLeaves leaves, boolean fancyGraphics) {
        leaves.setGraphicsLevel(ImpetusVintage.options().quality.leavesQuality.isFancy(fancyGraphics));
    }

    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(Minecraft minecraft, CallbackInfo ci) {
        this.renderer = new ImpetusWorldRenderer();
    }

    @Override
    public ImpetusWorldRenderer impetus$getWorldRenderer() {
        return this.renderer;
    }

    @Inject(method = "setWorldAndLoadRenderers", at = @At("RETURN"))
    private void onWorldChanged(WorldClient world, CallbackInfo ci) {
        RenderDevice.enterManagedCode();

        try {
            this.renderer.setWorld(world);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /**
     * @reason Redirect to our renderer
     * @author JellySquid
     */
    @Overwrite
    public int getRenderedChunks() {
        return this.renderer.getVisibleChunkCount();
    }

    /**
     * @reason Redirect the check to our renderer
     * @author JellySquid
     */
    @Overwrite
    public boolean hasNoChunkUpdates() {
        return this.renderer.isTerrainRenderComplete();
    }

    @Inject(method = "setDisplayListEntitiesDirty", at = @At("RETURN"))
    private void onTerrainUpdateScheduled(CallbackInfo ci) {
        this.renderer.scheduleTerrainUpdate();
    }

    /**
     * @reason Redirect the chunk layer render passes to our renderer
     * @author JellySquid
     */
    @Overwrite
    public int renderBlockLayer(BlockRenderLayer blockLayerIn, double partialTicks, int pass, Entity entityIn) {
        RenderDevice.enterManagedCode();

        RenderHelper.disableStandardItemLighting();

        GlStateManager.setActiveTexture(OpenGlHelper.defaultTexUnit);
        GlStateManager.bindTexture(this.mc.getTextureMapBlocks().getGlTextureId());
        GlStateManager.enableTexture2D();

        this.mc.entityRenderer.enableLightmap();

        double d3 = entityIn.lastTickPosX + (entityIn.posX - entityIn.lastTickPosX) * partialTicks;
        double d4 = entityIn.lastTickPosY + (entityIn.posY - entityIn.lastTickPosY) * partialTicks;
        double d5 = entityIn.lastTickPosZ + (entityIn.posZ - entityIn.lastTickPosZ) * partialTicks;

        try {
            this.renderer.drawChunkLayer(blockLayerIn, d3, d4, d5);
        } finally {
            RenderDevice.exitManagedCode();
        }

        this.mc.entityRenderer.disableLightmap();

        return 1;
    }

    /**
     * @reason Redirect the terrain setup phase to our renderer
     * @author JellySquid
     */
    @Overwrite
    public void setupTerrain(Entity entity, double tick, ICamera camera, int frame, boolean spectator) {
        RenderDevice.enterManagedCode();

        try {
            this.renderer.setupTerrain(((ViewportProvider)camera).impetus$createViewport(), ImpetusWorldRenderer.captureCameraState(tick),
                    frame, spectator, false);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    /**
     * @reason Redirect chunk updates to our renderer
     * @author JellySquid
     */
    @Overwrite
    private void markBlocksForUpdate(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean important) {
        this.renderer.scheduleRebuildForBlockArea(minX, minY, minZ, maxX, maxY, maxZ, important);
    }

    // The following two redirects force light updates to trigger chunk updates and not check vanilla's chunk renderer
    // flags
    @Redirect(method = "updateClouds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/chunk/ChunkRenderDispatcher;hasNoFreeRenderBuilders()Z"))
    private boolean alwaysHaveBuilders(ChunkRenderDispatcher instance) {
        return false;
    }

    @Redirect(method = "updateClouds", at = @At(value = "INVOKE", target = "Ljava/util/Set;isEmpty()Z", ordinal = 1))
    private boolean alwaysHaveNoTasks(Set instance) {
        return true;
    }

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void obeyShaderPackCloudMode(float partialTicks, int pass, double x, double y, double z,
            CallbackInfo ci) {
        if (!shouldDispatchVanillaClouds()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderCloudsFancy", at = @At("HEAD"), cancellable = true)
    private void obeyShaderPackFancyCloudMode(float partialTicks, int pass, double x, double y, double z,
            CallbackInfo ci) {
        if (!shouldRenderVanillaClouds(true)) {
            ci.cancel();
        }
    }

    private static boolean shouldDispatchVanillaClouds() {
        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null) {
            return true;
        }
        String mode = pack.getProperties().getCloudMode().orElse("");
        switch (mode) {
            case "off":
            case "none":
            case "false":
                return false;
            default:
                return true;
        }
    }

    private static boolean shouldRenderVanillaClouds(boolean fancy) {
        ShaderPack pack = Iris.getCurrentPack();
        if (pack == null) {
            return true;
        }
        String mode = pack.getProperties().getCloudMode().orElse("");
        switch (mode) {
            case "off":
            case "none":
            case "false":
                return false;
            case "fast":
                return !fancy;
            case "fancy":
                return fancy;
            default:
                return true;
        }
    }

    @Redirect(method = "renderClouds", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldProvider;getCloudHeight()F"))
    private float getConfiguredFastCloudHeight(WorldProvider provider) {
        return getConfiguredCloudHeight(provider);
    }

    @Redirect(method = "renderCloudsFancy", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldProvider;getCloudHeight()F"))
    private float getConfiguredFancyCloudHeight(WorldProvider provider) {
        return getConfiguredCloudHeight(provider);
    }

    private float getConfiguredCloudHeight(WorldProvider provider) {
        return ImpetusVintage.options().quality.cloudHeight;
    }

    @ModifyConstant(method = "renderClouds", constant = @Constant(intValue = -256))
    private int getConfiguredFastCloudDistanceMin(int distance) {
        return -getConfiguredCloudDistanceBlocks();
    }

    @ModifyConstant(method = "renderClouds", constant = @Constant(intValue = 256))
    private int getConfiguredFastCloudDistanceMax(int distance) {
        return getConfiguredCloudDistanceBlocks();
    }

    @ModifyConstant(method = "renderCloudsFancy", constant = @Constant(intValue = -3))
    private int getConfiguredFancyCloudDistanceMin(int distance) {
        return -getConfiguredCloudDistanceTiles();
    }

    @ModifyConstant(method = "renderCloudsFancy", constant = @Constant(intValue = 4), require = 0)
    private int getConfiguredFancyCloudDistanceMax(int distance) {
        return getConfiguredCloudDistanceTiles();
    }

    private int getConfiguredCloudDistanceBlocks() {
        return Math.max(8, ImpetusVintage.options().quality.cloudDistance) * 16;
    }

    private int getConfiguredCloudDistanceTiles() {
        return Math.max(2, (int)Math.ceil(getConfiguredCloudDistanceBlocks() / 96.0D));
    }

    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void onReload(CallbackInfo ci) {
        RenderDevice.enterManagedCode();

        try {
            this.renderer.reload();
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    @Inject(method = "renderEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/RenderHelper;enableStandardItemLighting()V", shift = At.Shift.AFTER, ordinal = 1), cancellable = true)
    public void impetus$renderTileEntities(Entity entity, ICamera camera, float partialTicks, CallbackInfo ci, @Local(ordinal = 0) int pass) {
        this.renderer.renderBlockEntities(new ImpetusWorldRenderer.TileEntityRenderContext(damagedBlocks, partialTicks));

        /*
         * Normally, setTileEntities will be empty because we suppress vanilla chunk rendering. However, some mods
         * inject a custom renderer into the set. So we render any TE we find in it.
         * https://github.com/pau101/Fairy-Lights/blob/8a92f770d69be6fa164d24d7a023d828249423bb/src/main/java/com/pau101/fairylights/client/ClientProxy.java#L203
         */
        synchronized(this.setTileEntities) {
            if (!this.setTileEntities.isEmpty()) {
                TileEntityRendererDispatcher.instance.preDrawBatch();
                for (var te : this.setTileEntities) {
                    if (te.shouldRenderInPass(pass)) {
                        TileEntityRendererDispatcher.instance.render(te, partialTicks, -1);
                    }
                }
                TileEntityRendererDispatcher.instance.drawBatch(pass);
            }
        }

        this.mc.entityRenderer.disableLightmap();
        this.mc.profiler.endSection();
        ci.cancel();
    }

    /**
     * @reason Replace the debug string
     * @author JellySquid
     */
    @Overwrite
    public String getDebugInfoRenders() {
        return this.renderer.getChunksDebugString();
    }

    private final EntityGatherer impetus$entityGatherer = new EntityGatherer();

    private List<Entity>[] impetus$collectedEntities;

    /**
     * @author embeddedt
     * @reason reimplement entity render loop because vanilla's relies on the renderInfos list
     */
    @Inject(method = "renderEntities", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/RenderGlobal;renderInfos:Ljava/util/List;", ordinal = 0))
    private void renderEntities(Entity renderViewEntity, ICamera camera, float partialTicks, CallbackInfo ci,
                                @Local(ordinal = 1) List<Entity> outlineEntityList,
                                @Local(ordinal = 2) List<Entity> multipassEntityList,
                                @Local(ordinal = 0) double renderViewX,
                                @Local(ordinal = 1) double renderViewY,
                                @Local(ordinal = 2) double renderViewZ) {
        int pass = net.minecraftforge.client.MinecraftForgeClient.getRenderPass();
        if (pass == 0 || impetus$collectedEntities == null) {
            impetus$entityGatherer.clear();
            impetus$collectedEntities = impetus$entityGatherer.getLoadedEntityList(world);
        }
        EntityPlayerSP player = this.mc.player;
        BlockPos.MutableBlockPos entityBlockPos = new BlockPos.MutableBlockPos();
        // Apply entity distance scaling
        Entity.setRenderDistanceWeight(MathHelper.clamp((double)this.mc.gameSettings.renderDistanceChunks / 8.0D, 1.0D, 2.5D)
                * (ImpetusVintage.options().quality.entityDistance / 100.0D));

        for(Entity entity : impetus$collectedEntities[pass]) {
            // Do regular vanilla checks for visibility
            if(!this.renderManager.shouldRender(entity, camera, renderViewX, renderViewY, renderViewZ) && !entity.isRidingOrBeingRiddenBy(player)) {
                continue;
            }

            // Check if any corners of the bounding box are in a visible subchunk
            if(!ImpetusWorldRenderer.instance().isEntityVisible(entity)) {
                continue;
            }

            boolean isSleeping = renderViewEntity instanceof EntityLivingBase && ((EntityLivingBase) renderViewEntity).isPlayerSleeping();

            if ((entity != renderViewEntity || this.mc.gameSettings.thirdPersonView != 0 || isSleeping)
                    && (entity.posY < 0.0D || entity.posY >= 256.0D || this.world.isBlockLoaded(entityBlockPos.setPos(entity))))
            {
                ++this.countEntitiesRendered;
                this.renderManager.renderEntityStatic(entity, partialTicks, false);

                if (this.isOutlineActive(entity, renderViewEntity, camera))
                {
                    outlineEntityList.add(entity);
                }

                if (this.renderManager.isRenderMultipass(entity)) {
                    multipassEntityList.add(entity);
                }
            }
        }
    }
}
