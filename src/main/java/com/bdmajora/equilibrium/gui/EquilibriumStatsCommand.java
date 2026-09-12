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
    // Command name, so this is /equilibrium
    @Override
    public String getName() {
        return "equilibrium";
    }

    // Shown by /help
    @Override
    public String getUsage(ICommandSender sender) {
        return "/equilibrium — report which of Impetus' general performance patches are active";
    }

    // Zero so any player can run it; it only reads local config state
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    // Indented lines are detail under a heading, so they are greyed to keep the report readable
    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        for (String line : Equilibrium.statistics()) {
            sender.sendMessage(new TextComponentString(
                    (line.startsWith(" ") ? TextFormatting.GRAY : TextFormatting.AQUA) + line));
        }
    }
}
