package com.bdmajora.fulgor.gui;

import com.bdmajora.fulgor.Fulgor;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

// /fulgor prints how much lighting work was requested vs collapsed/skipped; client-side so it works without the mod on servers, and counters are process-wide (client + integrated server)
public class FulgorStatsCommand extends CommandBase {
    // Command name, so this is /fulgor
    @Override
    public String getName() {
        return "fulgor";
    }

    // Shown by /help
    @Override
    public String getUsage(ICommandSender sender) {
        return "/fulgor — report the work done by Impetus' lighting subsystem";
    }

    // Zero so any player can run it; it only reads counters
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    // Indented lines are detail under a heading, so they are greyed
    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        for (String line : Fulgor.statistics()) {
            sender.sendMessage(new TextComponentString(
                    (line.startsWith(" ") ? TextFormatting.GRAY : TextFormatting.AQUA) + line));
        }
    }
}
