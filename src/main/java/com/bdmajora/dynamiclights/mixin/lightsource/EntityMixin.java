package com.bdmajora.dynamiclights.mixin.lightsource;

import com.bdmajora.dynamiclights.DynamicLights;
import com.bdmajora.dynamiclights.DynamicLightsMode;
import com.bdmajora.dynamiclights.client.DynamicLightHandlers;
import com.bdmajora.dynamiclights.client.DynamicLightSource;
import com.bdmajora.dynamiclights.client.DynamicLightsEngine;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// makes every entity a potential light source
// this is the base implementation: burning entities glow, and anything with a registered handler glows
// by whatever that handler reports
// subclasses override impetus$dynamicLightTick() to add their own rules - held items for living
// entities, a fuse ramp for TNT - but the tracking, chunk-rebuild and lightmap machinery all lives here
// each subclass mixin keeps its own luminance field rather than sharing one: a @Unique field cannot be
// shadowed across mixins onto different classes, so a shared field would be silently written by one
// mixin and read by another, which is why every writer also overrides impetus$getLuminance()
@Mixin(Entity.class)
public abstract class EntityMixin implements DynamicLightSource {
    @Shadow
    public World world;

    @Shadow
    public double posX;

    @Shadow
    public double posY;

    @Shadow
    public double posZ;

    @Shadow
    public boolean isDead;

    @Shadow
    public int chunkCoordX;

    @Shadow
    public int chunkCoordZ;

    @Shadow
    public abstract boolean isBurning();

    @Shadow
    public abstract float getEyeHeight();

    @Unique
    private int impetus$luminance;
    @Unique
    private int impetus$lastLuminance;
    @Unique
    private long impetus$lastUpdate;
    @Unique
    private double impetus$prevX;
    @Unique
    private double impetus$prevY;
    @Unique
    private double impetus$prevZ;
    // the chunk sections this entity is currently lighting, allocated on first use
    // this mixin puts these fields on every entity in the world and the overwhelming majority of them
    // never emit light, but upstream allocates the set eagerly - which on a busy client is thousands of
    // hash sets that only ever hold nothing
    @Unique
    private LongOpenHashSet impetus$trackedLitChunkPos;

    // recomputes luminance once per tick
    // onEntityUpdate rather than onUpdate because it is the shared tail every entity's tick runs
    // through - except for the handful that override onUpdate without calling up, which is why
    // EntityHanging and EntityMinecart carry their own hooks
    @Inject(method = "onEntityUpdate", at = @At("TAIL"))
    private void impetus$onTick(CallbackInfo ci) {
        if (!this.world.isRemote) {
            return;
        }

        if (this.isDead) {
            this.impetus$setDynamicLightEnabled(false);
            return;
        }

        this.impetus$dynamicLightTick();
        DynamicLightsEngine.updateTracking(this);
    }

    // Lights the entity's own model by the brighter of its own glow and the light where it stands.
    @Inject(method = "getBrightnessForRender", at = @At("RETURN"), cancellable = true)
    private void impetus$brightnessForRender(CallbackInfoReturnable<Integer> cir) {
        if (!DynamicLights.options().mode.isEnabled()) {
            return;
        }

        cir.setReturnValue(DynamicLights.engine()
                .getLightmapWithDynamicLight((Entity) (Object) this, cir.getReturnValueI()));
    }

    @Inject(method = "onRemovedFromWorld", at = @At("TAIL"))
    private void impetus$onRemoved(CallbackInfo ci) {
        if (this.world.isRemote) {
            this.impetus$setDynamicLightEnabled(false);
        }
    }

    // ------------------------------------------------------------------------------------------
    // DynamicLightSource
    // ------------------------------------------------------------------------------------------

    @Override
    public double impetus$getDynamicLightX() {
        return this.posX;
    }

    // Eye height, not feet: a held torch is at head level, and the falloff is measured from it.
    @Override
    public double impetus$getDynamicLightY() {
        return this.posY + this.getEyeHeight();
    }

    @Override
    public double impetus$getDynamicLightZ() {
        return this.posZ;
    }

    @Override
    public World impetus$getDynamicLightWorld() {
        return this.world;
    }

    @Override
    public void impetus$resetDynamicLight() {
        this.impetus$lastLuminance = 0;
    }

    @Override
    public void impetus$dynamicLightTick() {
        Entity self = (Entity) (Object) this;

        if (!DynamicLights.options().entitiesLightSource || !DynamicLightHandlers.canEntityLightUp(self)) {
            this.impetus$luminance = 0;
            return;
        }

        int burning = this.isBurning() ? 14 : 0;
        this.impetus$luminance = Math.max(burning, DynamicLightHandlers.getLuminanceFrom(self));
    }

    @Override
    public int impetus$getLuminance() {
        return this.impetus$luminance;
    }

    @Override
    public boolean impetus$shouldUpdateDynamicLight() {
        DynamicLightsMode mode = DynamicLights.options().mode;

        if (!mode.isEnabled()) {
            return false;
        }

        if (mode.hasDelay()) {
            long now = System.currentTimeMillis();
            if (now < this.impetus$lastUpdate + mode.getDelay()) {
                return false;
            }
            this.impetus$lastUpdate = now;
        }

        return true;
    }

    // re-lights the chunks around this entity if it has moved or changed brightness
    // the 0.1-block movement threshold is what keeps a standing-still player from re-meshing its own
    // chunk every frame; below that the falloff shift is not visible anyway
    // eight sections are lit rather than one: the light reaches 7.75 blocks, so it can spill into the
    // three neighbours the entity is closest to and the four diagonals between them
    // which eight depends on where inside its own section the entity sits, which is what the direction
    // walk below works out
    @Override
    public boolean impetus$updateDynamicLight(RenderGlobal renderer) {
        if (!this.impetus$shouldUpdateDynamicLight()) {
            return false;
        }

        double deltaX = this.posX - this.impetus$prevX;
        double deltaY = this.posY - this.impetus$prevY;
        double deltaZ = this.posZ - this.impetus$prevZ;
        int luminance = this.impetus$getLuminance();

        boolean moved = Math.abs(deltaX) > 0.1D || Math.abs(deltaY) > 0.1D || Math.abs(deltaZ) > 0.1D;
        if (!moved && luminance == this.impetus$lastLuminance) {
            return false;
        }

        this.impetus$prevX = this.posX;
        this.impetus$prevY = this.posY;
        this.impetus$prevZ = this.posZ;
        this.impetus$lastLuminance = luminance;

        LongOpenHashSet newPos = luminance > 0 ? new LongOpenHashSet() : null;

        if (luminance > 0) {
            double eyeY = this.posY + this.getEyeHeight();
            BlockPos.MutableBlockPos chunkPos = new BlockPos.MutableBlockPos(
                    this.chunkCoordX, (int) Math.floor(eyeY) >> 4, this.chunkCoordZ);

            DynamicLightsEngine.scheduleChunkRebuild(renderer, chunkPos);
            DynamicLightsEngine.updateTrackedChunks(chunkPos, this.impetus$trackedLitChunkPos, newPos);

            double localX = this.posX - Math.floor(this.posX / 16.0D) * 16.0D;
            double localY = eyeY - Math.floor(eyeY / 16.0D) * 16.0D;
            double localZ = this.posZ - Math.floor(this.posZ / 16.0D) * 16.0D;

            EnumFacing directionX = localX >= 8.0D ? EnumFacing.EAST : EnumFacing.WEST;
            EnumFacing directionY = localY >= 8.0D ? EnumFacing.UP : EnumFacing.DOWN;
            EnumFacing directionZ = localZ >= 8.0D ? EnumFacing.SOUTH : EnumFacing.NORTH;

            for (int i = 0; i < 7; i++) {
                if (i % 4 == 0) {
                    chunkPos.move(directionX);
                } else if (i % 4 == 1) {
                    chunkPos.move(directionZ);
                } else if (i % 4 == 2) {
                    chunkPos.move(directionX.getOpposite());
                } else {
                    chunkPos.move(directionZ.getOpposite());
                    chunkPos.move(directionY);
                }

                DynamicLightsEngine.scheduleChunkRebuild(renderer, chunkPos);
                DynamicLightsEngine.updateTrackedChunks(chunkPos, this.impetus$trackedLitChunkPos, newPos);
            }
        }

        // Whatever is left in the old set is a chunk this source has moved away from, and still needs
        // rebuilding to lose the light.
        this.impetus$scheduleTrackedChunksRebuild(renderer);
        this.impetus$trackedLitChunkPos = newPos;
        return true;
    }

    @Override
    public void impetus$scheduleTrackedChunksRebuild(RenderGlobal renderer) {
        if (this.impetus$trackedLitChunkPos == null || Minecraft.getMinecraft().world != this.world) {
            return;
        }

        for (long pos : this.impetus$trackedLitChunkPos) {
            DynamicLightsEngine.scheduleChunkRebuild(renderer, pos);
        }
    }
}
