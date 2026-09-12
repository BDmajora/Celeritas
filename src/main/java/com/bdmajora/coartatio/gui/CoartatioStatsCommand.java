package com.bdmajora.coartatio.gui;

import com.bdmajora.coartatio.Coartatio;
import com.bdmajora.coartatio.MemoryReport;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

// /coartatio prints what the memory subsystem saved, in megabytes, each line marked MEASURED or ESTIMATED
// The live heap is printed alongside, since a launch without the mod is the only honest before-and-after
public class CoartatioStatsCommand extends CommandBase {
    // Command name, so this is /coartatio
    @Override
    public String getName() {
        return "coartatio";
    }

    // Shown by /help
    @Override
    public String getUsage(ICommandSender sender) {
        return "/coartatio — report memory saved by Impetus' memory subsystem";
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

        for (String line : Coartatio.statistics()) {
            sender.sendMessage(new TextComponentString(TextFormatting.DARK_GRAY + line));
        }
    }
}
