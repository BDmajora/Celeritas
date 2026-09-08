package com.bdmajora.fulgor.gui;

import com.bdmajora.fulgor.Fulgor;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

// /fulgor - prints how much lighting work was requested vs. actually collapsed/skipped
// Registered client-side so it works in single-player and on servers without the mod; counters are
// process-wide (client + integrated server both contribute in single-player)
public class FulgorStatsCommand extends CommandBase {
    @Override
    public String getName() {
        return "fulgor";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/fulgor — report the work done by Impetus' lighting subsystem";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        for (String line : Fulgor.statistics()) {
            sender.sendMessage(new TextComponentString(
                    (line.startsWith(" ") ? TextFormatting.GRAY : TextFormatting.AQUA) + line));
        }
    }
}
