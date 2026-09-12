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
import com.bdmajora.impetus.impl.render.clouds.SodiumCloudRenderer;
import com.bdmajora.impetus.umbra.Umbra;
import com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline;
import com.bdmajora.impetus.umbra.shaderpack.loading.ProgramId;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
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
    @Final
    private net.minecraft.client.renderer.texture.TextureManager renderEngine;
    @Shadow
    private int countEntitiesRendered;
    @Shadow
    private int cloudTickCounter;

    @Shadow
    protected abstract boolean isOutlineActive(Entity entityIn, Entity viewer, ICamera camera);

    @Shadow
    private WorldClient world;
    @Shadow
    @Final
    private Set<TileEntity> setTileEntities;
    private ImpetusWorldRenderer renderer;

    // Zero so vanilla allocates no chunk storage; Impetus owns terrain rendering
    @Redirect(method = "loadRenderers", at = @At(value = "FIELD", target = "Lnet/minecraft/client/settings/GameSettings;renderDistanceChunks:I", ordinal = 1))
    private int nullifyBuiltChunkStorage(GameSettings settings) {
        // Do not allow any resources to be allocated
        return 0;
    }

    // Leaves quality follows the Impetus option rather than the global fancy toggle
    @Redirect(method = "loadRenderers", at = @At(value = "INVOKE", target = "Lnet/minecraft/block/BlockLeaves;setGraphicsLevel(Z)V"))
    private void useConfiguredLeavesGraphicsLevel(BlockLeaves leaves, boolean fancyGraphics) {
        leaves.setGraphicsLevel(ImpetusVintage.options().quality.leavesQuality.isFancy(fancyGraphics));
    }

    // Creates the Impetus renderer alongside vanilla's RenderGlobal
    @Inject(method = "<init>", at = @At("RETURN"))
    private void init(Minecraft minecraft, CallbackInfo ci) {
        this.renderer = new ImpetusWorldRenderer();
    }

    @Override
    public ImpetusWorldRenderer impetus$getWorldRenderer() {
        return this.renderer;
    }

    // set for the duration of setWorldAndLoadRenderers, which calls loadRenderers internally
    // without this, one world change tore the terrain renderer down twice: loadRenderers fired
    // onReload first, rebuilding the section manager for the world we are in the middle of leaving, and
    // the trailing onWorldChanged then destroyed that brand-new manager and built another for the
    // incoming world
    // every chunk mesh was discarded and every terrain program recompiled twice per transition, and on
    // a server that moves you between worlds routinely (MCParks park-hopping) that reads as terrain
    // endlessly unloading
    // the tell in the logs is that a plain config change, which calls loadRenderers on its own, logged
    // a single "ChunkBuilder: Stopping worker threads", while every world change logged them in pairs
    @Unique
    private boolean impetus$changingWorld;

    @Inject(method = "setWorldAndLoadRenderers", at = @At("HEAD"))
    private void impetus$beginWorldChange(WorldClient world, CallbackInfo ci) {
        this.impetus$changingWorld = true;
    }

    // Tears down and rebuilds the renderer for the new world inside a managed-code scope
    @Inject(method = "setWorldAndLoadRenderers", at = @At("RETURN"))
    private void onWorldChanged(WorldClient world, CallbackInfo ci) {
        this.impetus$changingWorld = false;

        RenderDevice.enterManagedCode();

        try {
            this.renderer.setWorld(world);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    // Overwrite: from the Impetus renderer, for the debug screen
    @Overwrite
    public int getRenderedChunks() {
        return this.renderer.getVisibleChunkCount();
    }

    // Overwrite: whether the Impetus build queue is empty
    @Overwrite
    public boolean hasNoChunkUpdates() {
        return this.renderer.isTerrainRenderComplete();
    }

    // Forwards vanilla's update request to the Impetus renderer
    @Inject(method = "setDisplayListEntitiesDirty", at = @At("RETURN"))
    private void onTerrainUpdateScheduled(CallbackInfo ci) {
        this.renderer.scheduleTerrainUpdate();
    }

    // Overwrite: draws the layer through Impetus and returns zero, since vanilla's count is meaningless here
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

    // Overwrite: runs the Impetus visibility update in place of vanilla's chunk graph walk
    @Overwrite
    public void setupTerrain(Entity entity, double tick, ICamera camera, int frame, boolean spectator) {
        RenderDevice.enterManagedCode();

        try {
            // `frustum.culling = false`: the pack wants off-screen geometry drawn too, so the frustum test is
            // replaced with one that accepts everything (the same trick the shadow pass uses).
            com.bdmajora.impetus.umbra.pipeline.UmbraRenderingPipeline pipeline =
                    com.bdmajora.impetus.umbra.Umbra.getRenderingPipeline();
            com.bdmajora.impetus.engine.impl.render.viewport.Viewport viewport =
                    (pipeline != null && pipeline.shouldDisableFrustumCulling())
                            ? unculledViewport(((ViewportProvider) camera).impetus$createViewport())
                            : ((ViewportProvider) camera).impetus$createViewport();
            this.renderer.setupTerrain(viewport, ImpetusWorldRenderer.captureCameraState(tick),
                    frame, spectator, false);
        } finally {
            RenderDevice.exitManagedCode();
        }
    }

    // Same viewport, but with a frustum that accepts every section (frustum.culling = false).
    @Unique
    private static com.bdmajora.impetus.engine.impl.render.viewport.Viewport unculledViewport(
            com.bdmajora.impetus.engine.impl.render.viewport.Viewport source) {
        var transform = source.getTransform();
        return new com.bdmajora.impetus.engine.impl.render.viewport.Viewport(
                (minX, minY, minZ, maxX, maxY, maxZ) -> true,
                new org.joml.Vector3d(transform.x, transform.y, transform.z));
    }

    // Overwrite: schedules Impetus rebuilds for every section the box touches
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

    // Vanilla's chunk task set is always empty since Impetus schedules its own
    @Redirect(method = "updateClouds", at = @At(value = "INVOKE", target = "Ljava/util/Set;isEmpty()Z", ordinal = 1))
    private boolean alwaysHaveNoTasks(Set instance) {
        return true;
    }

    // takes over both cloud modes with SodiumCloudRenderer, which is upstream Sodium's face-culled
    // cloud mesh rather than vanilla's draw-everything-and-hide-it-with-a-depth-prepass one
    // see that class for why the vanilla mesh cannot survive a shader pipeline
    // the pack's clouds directive is not consulted here: GameSettingsCloudsMixin has already folded it
    // into shouldRenderClouds(), so by this point the mode is the effective one and every other caller
    // - notably EntityRenderer#renderCloudsCheck - agrees with it
    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void impetus$renderCloudsSodium(float partialTicks, int pass, double x, double y, double z,
            CallbackInfo ci) {
        int mode = this.mc.gameSettings.shouldRenderClouds();
        if (mode == 0) {
            ci.cancel();
            return;
        }

        if (!ImpetusVintage.options().performance.useFasterClouds
                || !this.world.provider.isSurfaceWorld()
                // A mod owning this dimension's clouds gets vanilla's dispatch, including the Forge render handler
                // that runs ahead of any cloud geometry.
                || this.world.provider.getCloudRenderer() != null
                || !SodiumCloudRenderer.isReady(this.mc)) {
            return;
        }

        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null) {
            pipeline.setPhase(ProgramId.Clouds);
        }

        try {
            float cellSize = com.bdmajora.extras.client.CloudPassState.cellSize();

            if (SodiumCloudRenderer.render(this.mc, this.world, this.renderEngine, this.cloudTickCounter, partialTicks,
                    pass, x, y, z, mode == 2, impetus$cloudRadiusCells(cellSize),
                    ImpetusVintage.options().quality.cloudHeight, cellSize)) {
                ci.cancel();
            }
        } finally {
            if (pipeline != null) {
                pipeline.setPhase(null);
            }
        }
    }

    // the vanilla fallback still honours the cloud-height option
    // the cloud *distance* options are deliberately not applied to it: vanilla's fancy mesh emits its
    // walls under hardcoded l2 > -1 / l2 <= 1 guards that are relative to its own -3..4 tile range, so
    // widening the range without widening those guards just multiplies the wall count
    // the Sodium path owns the distance slider instead, where culling makes it meaningful
    @Redirect(method = "renderClouds", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldProvider;getCloudHeight()F"))
    private float getConfiguredFastCloudHeight(WorldProvider provider) {
        return getConfiguredCloudHeight(provider);
    }

    // Fancy path shares the configured height
    @Redirect(method = "renderCloudsFancy", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldProvider;getCloudHeight()F"))
    private float getConfiguredFancyCloudHeight(WorldProvider provider) {
        return getConfiguredCloudHeight(provider);
    }

    // Cloud height comes from the Impetus option instead of the provider
    private float getConfiguredCloudHeight(WorldProvider provider) {
        return ImpetusVintage.options().quality.cloudHeight;
    }

    // cloud radius in cells
    // clamped at the bottom to vanilla's own extent (8 tiles of 8 cells, so 32 either side of the
    // camera) and at the top to the cloud projection's far plane - renderCloudsCheck builds it at
    // farPlaneDistance * 4, and cells past that are clipped away anyway
    // the distance the user asked for is in blocks, so it is divided by the *effective* cell size
    // rather than by vanilla's 12: raising the cloud scale must make the cells bigger, not push the
    // cloud layer further out
    @Unique
    private int impetus$cloudRadiusCells(float cellSize) {
        int requested = Math.max(8, ImpetusVintage.options().quality.cloudDistance) * 16;
        int farPlane = this.mc.gameSettings.renderDistanceChunks * 16 * 4;
        return Math.max(32, (int) Math.ceil(Math.min(requested, farPlane) / (double) cellSize));
    }

    // Skipped mid world change, since onWorldChanged is about to rebuild everything anyway
    @Inject(method = "loadRenderers", at = @At("RETURN"))
    private void onReload(CallbackInfo ci) {
        // Mid-world-change this reload is for the world being left, and onWorldChanged is about to rebuild the
        // renderer for the incoming one anyway. Doing it here as well only discards every chunk mesh an extra time.
        if (this.impetus$changingWorld) {
            return;
        }

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

    // Overwrite: the C: line from the Impetus renderer
    @Overwrite
    public String getDebugInfoRenders() {
        return this.renderer.getChunksDebugString();
    }

    private final EntityGatherer impetus$entityGatherer = new EntityGatherer();

    private List<Entity>[] impetus$collectedEntities;

    // --- end TEMP DIAGNOSTIC -----------------------------------------------------------------------------------

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
        UmbraRenderingPipeline pipeline = Umbra.getRenderingPipeline();
        if (pipeline != null && pass == 1 && pipeline.isRenderingPostDeferredTranslucents()) {
            // These are entities even when the pack ships no gbuffers_entities_translucent and the phase falls back to
            // gbuffers_textured_lit — which this pipeline also uses for particles. State the stage here rather than
            // derive it from the ProgramId, or a pack reading renderStage would be told "particles".
            pipeline.setPhase(pipeline.getTranslucentEntityPhase(), 11); // MC_RENDER_STAGE_ENTITIES
        }
        EntityPlayerSP player = this.mc.player;
        BlockPos.MutableBlockPos entityBlockPos = new BlockPos.MutableBlockPos();
        // Apply entity distance scaling
        Entity.setRenderDistanceWeight(MathHelper.clamp((double)this.mc.gameSettings.renderDistanceChunks / 8.0D, 1.0D, 2.5D)
                * (ImpetusVintage.options().quality.entityDistance / 100.0D));

        for(Entity entity : impetus$collectedEntities[pass]) {
            boolean isSleeping = renderViewEntity instanceof EntityLivingBase && ((EntityLivingBase) renderViewEntity).isPlayerSleeping();
            boolean isPlayerAttachedEntity = player != null && entity.isRidingOrBeingRiddenBy(player);
            boolean isLocalPlayerBody = player != null && entity == player
                    && (this.mc.gameSettings.thirdPersonView != 0 || isSleeping);

            // Do regular vanilla checks for visibility
            if(!isLocalPlayerBody
                    && !this.renderManager.shouldRender(entity, camera, renderViewX, renderViewY, renderViewZ)
                    && !isPlayerAttachedEntity) {
                continue;
            }

            // Check if any corners of the bounding box are in a visible subchunk
            if(!isLocalPlayerBody && !isPlayerAttachedEntity
                    && !ImpetusWorldRenderer.instance().isEntityVisible(entity)) {
                continue;
            }

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
