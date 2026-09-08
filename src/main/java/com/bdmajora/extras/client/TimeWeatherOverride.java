package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.network.play.server.SPacketChangeGameState;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;

/**
 * OptiFine's Time and Weather locks, which hold the integrated server's clock and weather where the
 * player put them.
 *
 * <p>Both carry OptiFine's restriction, and for its reason: they change world state, not just what
 * is drawn, so they are confined to a single-player creative world. On a dedicated server there is
 * no {@link WorldServer} on this side to act on, and the options simply do nothing — which is also
 * what OptiFine does.
 *
 * <p>Time is nudged rather than pinned. Setting it to a fixed value every tick would freeze the
 * daylight cycle mid-frame and stop anything that reads elapsed time; pushing it past the boundary
 * only when it strays out of the wanted half keeps the clock running.
 */
public final class TimeWeatherOverride {
    private static final long DAY_LENGTH = 24000L;
    private static final long DAY_START = 1000L;
    private static final long DAY_END = 11000L;
    private static final long NIGHT_START = 14000L;
    private static final long NIGHT_END = 22000L;

    private TimeWeatherOverride() {
    }

    /** Applies both locks. Called from the integrated server's world tick. */
    public static void apply(WorldServer world) {
        ExtrasConfig.ExtraSettings settings = Extras.options().extra;

        if (settings.timeOverride != ExtrasConfig.TimeOverride.DEFAULT) {
            applyTime(world, settings.timeOverride);
        }

        if (settings.weatherOverride != ExtrasConfig.WeatherOverride.DEFAULT) {
            applyWeather(world, settings.weatherOverride);
        }
    }

    private static void applyTime(WorldServer world, ExtrasConfig.TimeOverride override) {
        if (!isCreative(world)) {
            return;
        }

        long time = world.getWorldTime();
        long timeOfDay = time % DAY_LENGTH;

        switch (override) {
            case DAY -> {
                if (timeOfDay <= DAY_START) {
                    world.setWorldTime(time - timeOfDay + DAY_START + 1L);
                } else if (timeOfDay >= DAY_END) {
                    world.setWorldTime(time - timeOfDay + DAY_LENGTH + 1L);
                }
            }
            case NIGHT -> {
                if (timeOfDay <= NIGHT_START) {
                    world.setWorldTime(time - timeOfDay + NIGHT_START + 1L);
                } else if (timeOfDay >= NIGHT_END) {
                    world.setWorldTime(time - timeOfDay + DAY_LENGTH + NIGHT_START + 1L);
                }
            }
            default -> {
            }
        }
    }

    private static void applyWeather(WorldServer world, ExtrasConfig.WeatherOverride override) {
        if (!isCreative(world)) {
            return;
        }

        WorldInfo info = world.getWorldInfo();
        boolean raining = override != ExtrasConfig.WeatherOverride.CLEAR;
        boolean thundering = override == ExtrasConfig.WeatherOverride.THUNDER;

        if (info.isRaining() == raining && info.isThundering() == thundering) {
            return;
        }

        // Zero the countdowns too, or vanilla's weather scheduler undoes this within the tick.
        info.setRaining(raining);
        info.setRainTime(0);
        info.setThundering(thundering);
        info.setThunderTime(0);
        world.setRainStrength(raining ? 1.0F : 0.0F);
        world.setThunderStrength(thundering ? 1.0F : 0.0F);

        // The client renders from its own copy of the weather state, so it has to be told.
        if (world.getMinecraftServer() != null) {
            world.getMinecraftServer().getPlayerList()
                    .sendPacketToAllPlayers(new SPacketChangeGameState(raining ? 1 : 2, 0.0F));
            world.getMinecraftServer().getPlayerList()
                    .sendPacketToAllPlayers(new SPacketChangeGameState(7, raining ? 1.0F : 0.0F));
            world.getMinecraftServer().getPlayerList()
                    .sendPacketToAllPlayers(new SPacketChangeGameState(8, thundering ? 1.0F : 0.0F));
        }
    }

    private static boolean isCreative(WorldServer world) {
        return world.getWorldInfo().getGameType() == GameType.CREATIVE;
    }
}
