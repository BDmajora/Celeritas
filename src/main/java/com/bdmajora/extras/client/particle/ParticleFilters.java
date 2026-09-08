package com.bdmajora.extras.client.particle;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.util.EnumParticleTypes;

/**
 * The named particle switches, resolved by vanilla particle id.
 *
 * <p>Matching on id rather than on the {@link net.minecraft.client.particle.Particle} subclass is
 * what makes the finer OptiFine switches possible at all: several ids share a class.
 * {@code ParticleSuspendedTown} alone backs {@code SUSPENDED_DEPTH} (void), {@code TOWN_AURA} and
 * {@code VILLAGER_HAPPY}, so a class-keyed "void particles" switch would take the villager's happy
 * particles with it. The per-class toggles in {@link ParticleClassRegistry} stay class-keyed because
 * a modded particle has no id we can reason about.
 *
 * <p>The ids are read once into a lookup table: {@code EnumParticleTypes.getParticleID()} is a field
 * read, but this sits on the particle spawn path and the table keeps it to one array index.
 */
public final class ParticleFilters {
    /** Which switch governs each particle id; null means "no named switch". */
    private static final Filter[] BY_ID = buildTable();

    private ParticleFilters() {
    }

    /**
     * Whether a particle with this vanilla id may spawn.
     *
     * <p>Unknown ids — every modded particle — are always allowed through; they are filtered later
     * by class, if the user disabled them.
     */
    public static boolean isAllowed(int particleId) {
        ExtrasConfig options = Extras.options();

        if (!options.particle.all) {
            return false;
        }

        if (particleId < 0 || particleId >= BY_ID.length) {
            return true;
        }

        Filter filter = BY_ID[particleId];
        return filter == null || filter.isEnabled(options);
    }

    private static Filter[] buildTable() {
        int size = 0;
        for (EnumParticleTypes type : EnumParticleTypes.values()) {
            size = Math.max(size, type.getParticleID() + 1);
        }

        Filter[] table = new Filter[size];

        // OptiFine treats explosions and smoke as animations rather than particles; both names are
        // kept where the user expects to find them, and both read the animation switches.
        put(table, Filter.EXPLOSION, EnumParticleTypes.EXPLOSION_NORMAL,
                EnumParticleTypes.EXPLOSION_LARGE, EnumParticleTypes.EXPLOSION_HUGE);
        put(table, Filter.SMOKE, EnumParticleTypes.SMOKE_NORMAL, EnumParticleTypes.SMOKE_LARGE);
        put(table, Filter.FLAME, EnumParticleTypes.FLAME);
        put(table, Filter.REDSTONE, EnumParticleTypes.REDSTONE);
        put(table, Filter.PORTAL, EnumParticleTypes.PORTAL);

        put(table, Filter.WATER, EnumParticleTypes.SUSPENDED);
        put(table, Filter.VOID, EnumParticleTypes.SUSPENDED_DEPTH);
        put(table, Filter.POTION, EnumParticleTypes.SPELL, EnumParticleTypes.SPELL_INSTANT,
                EnumParticleTypes.SPELL_MOB, EnumParticleTypes.SPELL_MOB_AMBIENT,
                EnumParticleTypes.SPELL_WITCH);
        put(table, Filter.DRIPPING, EnumParticleTypes.DRIP_WATER, EnumParticleTypes.DRIP_LAVA);
        put(table, Filter.FIREWORK, EnumParticleTypes.FIREWORKS_SPARK);

        return table;
    }

    private static void put(Filter[] table, Filter filter, EnumParticleTypes... types) {
        for (EnumParticleTypes type : types) {
            table[type.getParticleID()] = filter;
        }
    }

    /** One named switch, as a function of the loaded options. */
    private enum Filter {
        EXPLOSION {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.animation.explosion;
            }
        },
        SMOKE {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.animation.smoke;
            }
        },
        FLAME {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.animation.flame;
            }
        },
        REDSTONE {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.animation.redstone;
            }
        },
        PORTAL {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.particle.portalParticles;
            }
        },
        WATER {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.particle.waterParticles;
            }
        },
        VOID {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.particle.voidParticles;
            }
        },
        POTION {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.particle.potionParticles;
            }
        },
        DRIPPING {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.particle.drippingWaterLava;
            }
        },
        FIREWORK {
            @Override
            boolean isEnabled(ExtrasConfig options) {
                return options.particle.fireworkParticles;
            }
        };

        abstract boolean isEnabled(ExtrasConfig options);
    }
}
