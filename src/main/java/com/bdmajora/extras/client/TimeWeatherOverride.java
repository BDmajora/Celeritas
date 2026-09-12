package com.bdmajora.extras.client;

import com.bdmajora.extras.Extras;
import com.bdmajora.extras.ExtrasConfig;
import net.minecraft.network.play.server.SPacketChangeGameState;
import net.minecraft.world.GameType;
import net.minecraft.world.WorldServer;
import net.minecraft.world.storage.WorldInfo;

// OptiFine's Time and Weather locks, holding the integrated server's clock/weather where the player put them
// Confined to single-player creative like OptiFine, since these change world state, not just what's drawn
// Time is nudged past the boundary rather than pinned, so the daylight cycle keeps running
public final class TimeWeatherOverride {
    private static final long DAY_LENGTH = 24000L;
    private static final long DAY_START = 1000L;
    private static final long DAY_END = 11000L;
    private static final long NIGHT_START = 14000L;
    private static final long NIGHT_END = 22000L;

    private TimeWeatherOverride() {
    }

    // Applies both locks; called from the integrated server's world tick
    public static void apply(WorldServer world) {
        ExtrasConfig.ExtraSettings settings = Extras.options().extra;

        if (settings.timeOverride != ExtrasConfig.TimeOverride.DEFAULT) {
            applyTime(world, settings.timeOverride);
        }

        if (settings.weatherOverride != ExtrasConfig.WeatherOverride.DEFAULT) {
            applyWeather(world, settings.weatherOverride);
        }
    }

    // Nudges the clock past the boundary rather than pinning it, so the cycle keeps advancing
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

    // Forces the world info's rain and thunder flags to the chosen state
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

    // The overrides only apply in creative, matching OptiFine
    private static boolean isCreative(WorldServer world) {
        return world.getWorldInfo().getGameType() == GameType.CREATIVE;
    }
}
