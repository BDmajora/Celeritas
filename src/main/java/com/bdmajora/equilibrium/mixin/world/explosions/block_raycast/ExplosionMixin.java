package com.bdmajora.equilibrium.mixin.world.explosions.block_raycast;

import com.bdmajora.equilibrium.common.world.ChunkSectionCursor;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.enchantment.EnchantmentProtection;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Explosion;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rewrites the block-damage half of an explosion.
 *
 * <p>Vanilla fires 1352 rays — every cell on the surface of a 16×16×16 grid — and steps each one 0.3
 * blocks at a time until its energy runs out. For a TNT-sized blast that is around twenty thousand
 * iterations, and each iteration allocates a {@link BlockPos} and performs a full
 * {@code World#getBlockState}, which resolves a chunk and then a section from scratch.
 *
 * <p>Two things about that are wasteful. The rays are much finer than the grid they walk: at 0.3
 * blocks per step, roughly two out of every three steps land in the same block as the step before,
 * and vanilla reads it again anyway. And the reads themselves go the long way round when consecutive
 * steps are, by construction, spatially adjacent.
 *
 * <p>So this tracks the integer position from the previous step and does work only when the ray
 * actually crosses into a new block, reading through a {@link ChunkSectionCursor} that keeps the
 * chunk and section between reads. What remains is one block position allocation and one section
 * lookup per <em>distinct block</em> per ray rather than per step.
 *
 * <p>Skipping a repeated position is not an approximation. The state is the same, so the resistance
 * it subtracts and the decision to add it are the same, and the set already holds it — the only
 * observable difference would be a Forge hook seeing fewer calls for the same block, which is not
 * something either hook is specified to count.
 *
 * <p>Everything else is preserved: ray directions, step size, energy arithmetic, the order positions
 * are visited in, which random generator supplies the per-ray energy, and both Forge hooks. The
 * entity half of the method is transcribed unchanged rather than left to vanilla, because there is no
 * injection point between the two halves that survives another mod reordering the bytecode.
 * Explosions are the most timing-visible thing on the server and a contraption that depends on which
 * blocks a blast destroys has to keep working.
 */
@Mixin(Explosion.class)
public abstract class ExplosionMixin {
    @Shadow
    @Final
    private World world;

    @Shadow
    @Final
    private Entity exploder;

    @Shadow
    @Final
    private float size;

    @Shadow
    @Final
    private double x;

    @Shadow
    @Final
    private double y;

    @Shadow
    @Final
    private double z;

    @Shadow
    @Final
    private List<BlockPos> affectedBlockPositions;

    @Shadow
    @Final
    private Map<EntityPlayer, Vec3d> playerKnockbackMap;

    /**
     * @author JellySquid
     * @reason Read each block along a ray once, through a cached chunk section
     */
    @Overwrite
    public void doExplosionA() {
        Set<BlockPos> affected = new HashSet<>();

        // Loading, because vanilla's getBlockState loads: a blast at the edge of the loaded area
        // really does pull terrain in, and declining to would let it punch straight through.
        ChunkSectionCursor cursor = new ChunkSectionCursor(this.world, true);

        for (int rayX = 0; rayX < 16; ++rayX) {
            for (int rayY = 0; rayY < 16; ++rayY) {
                for (int rayZ = 0; rayZ < 16; ++rayZ) {
                    // Only the surface of the grid; the interior would produce duplicate directions.
                    if (rayX != 0 && rayX != 15 && rayY != 0 && rayY != 15 && rayZ != 0 && rayZ != 15) {
                        continue;
                    }

                    double dirX = rayX / 15.0F * 2.0F - 1.0F;
                    double dirY = rayY / 15.0F * 2.0F - 1.0F;
                    double dirZ = rayZ / 15.0F * 2.0F - 1.0F;

                    double length = Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
                    dirX /= length;
                    dirY /= length;
                    dirZ /= length;

                    // Drawn from the world's random rather than the explosion's own, exactly as
                    // vanilla does. They are different generators and swapping them would change
                    // which blocks a given blast destroys.
                    float energy = this.size * (0.7F + this.world.rand.nextFloat() * 0.6F);

                    double stepX = this.x;
                    double stepY = this.y;
                    double stepZ = this.z;

                    int lastX = Integer.MIN_VALUE;
                    int lastY = Integer.MIN_VALUE;
                    int lastZ = Integer.MIN_VALUE;

                    for (; energy > 0.0F; energy -= 0.22500001F) {
                        int blockX = MathHelper.floor(stepX);
                        int blockY = MathHelper.floor(stepY);
                        int blockZ = MathHelper.floor(stepZ);

                        if (blockX != lastX || blockY != lastY || blockZ != lastZ) {
                            lastX = blockX;
                            lastY = blockY;
                            lastZ = blockZ;

                            IBlockState state = cursor.getBlockState(blockX, blockY, blockZ);

                            BlockPos pos = null;

                            if (state.getMaterial() != Material.AIR) {
                                pos = new BlockPos(blockX, blockY, blockZ);

                                float resistance = this.exploder != null
                                        ? this.exploder.getExplosionResistance((Explosion) (Object) this, this.world, pos, state)
                                        : state.getBlock().getExplosionResistance(this.world, pos, null, (Explosion) (Object) this);

                                energy -= (resistance + 0.3F) * 0.3F;
                            }

                            if (energy > 0.0F) {
                                if (pos == null) {
                                    pos = new BlockPos(blockX, blockY, blockZ);
                                }

                                if (this.exploder == null
                                        || this.exploder.canExplosionDestroyBlock((Explosion) (Object) this, this.world, pos, state, energy)) {
                                    affected.add(pos);
                                }
                            }
                        }

                        stepX += dirX * 0.30000001192092896D;
                        stepY += dirY * 0.30000001192092896D;
                        stepZ += dirZ * 0.30000001192092896D;
                    }
                }
            }
        }

        this.affectedBlockPositions.addAll(affected);

        // ------------------------------------------------------------------ entity damage
        // [VanillaCopy] Explosion#doExplosionA, second half. Unchanged; see the class comment for
        // why it is transcribed rather than left in place.
        float radius = this.size * 2.0F;

        int minX = MathHelper.floor(this.x - radius - 1.0D);
        int maxX = MathHelper.floor(this.x + radius + 1.0D);
        int minY = MathHelper.floor(this.y - radius - 1.0D);
        int maxY = MathHelper.floor(this.y + radius + 1.0D);
        int minZ = MathHelper.floor(this.z - radius - 1.0D);
        int maxZ = MathHelper.floor(this.z + radius + 1.0D);

        List<Entity> entities = this.world.getEntitiesWithinAABBExcludingEntity(this.exploder,
                new AxisAlignedBB(minX, minY, minZ, maxX, maxY, maxZ));

        net.minecraftforge.event.ForgeEventFactory.onExplosionDetonate(this.world, (Explosion) (Object) this, entities, radius);

        Vec3d center = new Vec3d(this.x, this.y, this.z);

        for (int i = 0; i < entities.size(); ++i) {
            Entity entity = entities.get(i);

            if (entity.isImmuneToExplosions()) {
                continue;
            }

            double normalisedDistance = entity.getDistance(this.x, this.y, this.z) / radius;

            if (normalisedDistance > 1.0D) {
                continue;
            }

            double deltaX = entity.posX - this.x;
            double deltaY = entity.posY + entity.getEyeHeight() - this.y;
            double deltaZ = entity.posZ - this.z;

            double distance = MathHelper.sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ);

            if (distance == 0.0D) {
                continue;
            }

            deltaX /= distance;
            deltaY /= distance;
            deltaZ /= distance;

            double exposure = this.world.getBlockDensity(center, entity.getEntityBoundingBox());
            double impact = (1.0D - normalisedDistance) * exposure;

            entity.attackEntityFrom(DamageSource.causeExplosionDamage((Explosion) (Object) this),
                    (float) ((int) ((impact * impact + impact) / 2.0D * 7.0D * radius + 1.0D)));

            double knockback = impact;

            if (entity instanceof EntityLivingBase) {
                knockback = EnchantmentProtection.getBlastDamageReduction((EntityLivingBase) entity, impact);
            }

            entity.motionX += deltaX * knockback;
            entity.motionY += deltaY * knockback;
            entity.motionZ += deltaZ * knockback;

            if (entity instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) entity;

                if (!player.isSpectator() && (!player.isCreative() || !player.capabilities.isFlying)) {
                    this.playerKnockbackMap.put(player, new Vec3d(deltaX * impact, deltaY * impact, deltaZ * impact));
                }
            }
        }
    }
}
