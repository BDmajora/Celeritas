package com.bdmajora.equilibrium.gui;

import com.bdmajora.equilibrium.Equilibrium;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

// /equilibrium - reports active optimizations and who disabled the rest; registered client-side
// so it works in single-player and on servers without the mod
public class EquilibriumStatsCommand extends CommandBase {
    @Override
    public String getName() {
        return "equilibrium";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/equilibrium — report which of Impetus' general performance patches are active";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        for (String line : Equilibrium.statistics()) {
            sender.sendMessage(new TextComponentString(
                    (line.startsWith(" ") ? TextFormatting.GRAY : TextFormatting.AQUA) + line));
        }
    }
}
