package com.bdmajora.equilibrium.gui;

import com.bdmajora.equilibrium.Equilibrium;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * {@code /equilibrium} — prints which optimizations are active and, for the ones that are not, who
 * turned them off.
 *
 * <p>Registered client-side, so it works in single-player and on any server without the mod.
 *
 * <p>The useful part is the second half of the output. "Twenty-two of twenty-four active" tells you
 * almost nothing; "block.redstone_wire disabled for mod compatibility (SpongeForge)" tells you why
 * the thing you installed this for is not helping, which is the question people actually arrive with.
 */
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
