package com.bdmajora.coarctatio.gui;

import com.bdmajora.coarctatio.Coarctatio;
import com.bdmajora.coarctatio.MemoryReport;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

// /coarctatio prints what the memory subsystem saved in megabytes, each line marked MEASURED or ESTIMATED, alongside the live heap
public class CoarctatioStatsCommand extends CommandBase {
    // Command name, so this is /coarctatio
    @Override
    public String getName() {
        return "coarctatio";
    }

    // Shown by /help
    @Override
    public String getUsage(ICommandSender sender) {
        return "/coarctatio — report memory saved by Impetus' memory subsystem";
    }

    // Zero so any player can run it; it only reads counters
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    // Indented lines are detail under a heading, so they are greyed
    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) {
        for (String line : MemoryReport.lines()) {
            sender.sendMessage(new TextComponentString(
                    (line.startsWith(" ") ? TextFormatting.GRAY : TextFormatting.AQUA) + line));
        }

        for (String line : Coarctatio.statistics()) {
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY + line));
        }
    }
}
