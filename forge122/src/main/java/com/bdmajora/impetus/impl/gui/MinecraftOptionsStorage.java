package com.bdmajora.impetus.impl.gui;

import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import com.bdmajora.impetus.api.options.structure.OptionFlag;
import com.bdmajora.impetus.api.options.structure.OptionStorage;
import com.bdmajora.impetus.ImpetusVintage;

public class MinecraftOptionsStorage implements OptionStorage<GameSettings> {
    private final Minecraft client;

    public MinecraftOptionsStorage() {
        this.client = Minecraft.getMinecraft();
    }

    @Override
    public GameSettings getData() {
        return this.client.gameSettings;
    }

    @Override
    public void save(Set<OptionFlag> flags) {
        this.getData().saveOptions();

        ImpetusVintage.logger().info("Flushed changes to Minecraft configuration");
    }
}